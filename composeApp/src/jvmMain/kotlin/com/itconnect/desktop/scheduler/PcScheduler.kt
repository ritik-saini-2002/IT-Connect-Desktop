package com.itconnect.desktop.scheduler

import com.itconnect.desktop.data.PcControlRepository
import com.itconnect.desktop.data.PcSavedDevice
import com.itconnect.desktop.data.PcSchedule
import com.itconnect.desktop.data.PcStep
import com.itconnect.desktop.network.PcControlApiClient
import com.itconnect.desktop.network.PcControlSettings
import com.itconnect.desktop.network.WakeOnLan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Periodic schedule scanner. Desktop replacement for the Android
 * `PcScheduleWorker` (which relied on WorkManager and was capped at a
 * 15-minute minimum period).
 *
 * On desktop we tick every 60 seconds from a single daemon thread —
 * no battery constraints, no Doze, so the ±60 s tolerance is sufficient.
 * Matches the bug-fix commit (2419cbb) on the Android side that widened
 * the window to 30 minutes catch-up; [PcControlRepository.dueSchedulesNow]
 * carries that logic and this scheduler simply drives it.
 *
 * Dispatch body mirrors the Android worker's `dispatch()` verbatim.
 */
object PcScheduler {

    private val log = LoggerFactory.getLogger("PcScheduler")

    private val exec: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "pc-scheduler").apply { isDaemon = true }
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false

    fun start(repo: PcControlRepository) {
        if (started) return
        started = true
        log.info("scheduler starting — 60 s tick")
        exec.scheduleAtFixedRate({
            scope.launch { tickOnce(repo) }
        }, 0, 60, TimeUnit.SECONDS)
    }

    fun shutdown() {
        exec.shutdownNow()
    }

    private suspend fun tickOnce(repo: PcControlRepository) {
        val due = runCatching { repo.dueSchedulesNow() }
            .onFailure { log.error("dueSchedulesNow failed: {}", it.message, it) }
            .getOrNull()
            .orEmpty()

        if (due.isEmpty()) {
            log.debug("no schedules due")
            return
        }
        log.info("firing {} schedule(s)", due.size)
        for (s in due) {
            val device = runCatching { repo.findDeviceById(s.deviceId) }.getOrNull()
            if (device == null) {
                log.warn("schedule {} references missing device {}", s.id, s.deviceId)
                repo.markScheduleFired(s.id)
                continue
            }
            log.info("-> {} on '{}' (schedule {})", s.action, device.label, s.id)
            runCatching { dispatch(s, device, repo) }
                .onFailure { log.warn("schedule {} dispatch failed: {}", s.id, it.message) }
                .onSuccess   { log.debug("schedule {} dispatched OK", s.id) }
            repo.markScheduleFired(s.id)
        }
    }

    private suspend fun dispatch(s: PcSchedule, device: PcSavedDevice, repo: PcControlRepository) {
        when (s.action) {
            ACTION_WOL -> {
                val mac = device.macAddress ?: return
                WakeOnLan.wake(mac, device.broadcastAddress, device.wolPort)
            }
            ACTION_SHUTDOWN, ACTION_SLEEP, ACTION_LOCK -> {
                val api = PcControlApiClient(
                    PcControlSettings(device.host, device.port, device.secretKey)
                )
                api.executeQuickStep(PcStep(type = "SYSTEM_CMD", value = s.action))
            }
            ACTION_EXECUTE_PLAN -> {
                val pid = s.planId ?: return
                val plan = repo.getPlanById(pid) ?: return
                val api = PcControlApiClient(
                    PcControlSettings(device.host, device.port, device.secretKey)
                )
                api.executePlan(plan)
            }
            else -> log.warn("unknown action '{}' on schedule {}", s.action, s.id)
        }
    }

    const val ACTION_WOL          = "WOL"
    const val ACTION_SHUTDOWN     = "SHUTDOWN"
    const val ACTION_SLEEP        = "SLEEP"
    const val ACTION_LOCK         = "LOCK"
    const val ACTION_EXECUTE_PLAN = "EXECUTE_PLAN"
}

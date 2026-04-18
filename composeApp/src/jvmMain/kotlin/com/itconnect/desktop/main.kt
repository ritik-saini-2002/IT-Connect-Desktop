package com.itconnect.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.itconnect.desktop.app.AppShell
import com.itconnect.desktop.data.PcControlDatabase
import com.itconnect.desktop.data.PcControlRepository
import com.itconnect.desktop.scheduler.PcScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("main")

fun main() {
    log.info("IT Connect Desktop starting…")

    val db = PcControlDatabase.getDatabase()
    val repo = PcControlRepository(
        planDao = db.planDao,
        logDao = db.connectionLogDao,
        deviceDao = db.savedDeviceDao,
        scheduleDao = db.scheduleDao,
    )

    // Seed default plans on first launch; fire-and-forget.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    appScope.launch { repo.seedIfEmpty() }

    // Drive the 60 s schedule tick (replaces Android's WorkManager worker).
    PcScheduler.start(repo)

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "IT Connect",
            state = rememberWindowState(size = DpSize(1400.dp, 900.dp)),
        ) {
            AppShell(repo)
        }
    }
}

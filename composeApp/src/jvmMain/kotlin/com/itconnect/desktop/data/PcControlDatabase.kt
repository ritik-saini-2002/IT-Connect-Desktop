package com.itconnect.desktop.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * JDBC-backed equivalent of the Android app's Room-generated
 * `PcControlDatabase`. The on-disk file is binary-compatible with the Android
 * app's `pc_control.db` — copy an Android DB into
 * `%APPDATA%/ITConnect/pc_control.db` and the desktop reads it transparently.
 *
 * Schema + migrations mirror the Android [MIGRATION_3_4] … [MIGRATION_7_8]
 * objects verbatim; see `docs/DESKTOP_PORT_PLAN.md` §5 for the deviation
 * rationale (no Room → hand-written JDBC).
 */
class PcControlDatabase private constructor(val path: String) {

    private val log = LoggerFactory.getLogger("PcControlDatabase")
    private val writeLock = Mutex()
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path").apply {
        autoCommit = true
        createStatement().use { it.executeUpdate("PRAGMA journal_mode=WAL") }
        createStatement().use { it.executeUpdate("PRAGMA foreign_keys=ON") }
    }

    init { applyMigrations() }

    val planDao         = PcPlanDao(this)
    val connectionLogDao = PcConnectionLogDao(this)
    val savedDeviceDao  = PcSavedDeviceDao(this)
    val scheduleDao     = PcScheduleDao(this)

    internal suspend fun <T> readTx(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) { block(conn) }

    internal suspend fun <T> writeTx(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) { writeLock.withLock { block(conn) } }

    // ── schema versioning ────────────────────────────────────────

    private fun applyMigrations() {
        val current = currentVersion()
        log.info("DB at $path opened at user_version=$current (target=$TARGET_VERSION)")
        if (current == 0) {
            // Fresh database: create the v8 schema directly.
            createFreshSchema()
            setVersion(TARGET_VERSION)
            return
        }
        var v = current
        while (v < TARGET_VERSION) {
            val migration = migrations[v] ?: error("No migration from v$v to v${v + 1}")
            conn.createStatement().use { st ->
                conn.autoCommit = false
                try {
                    for (sql in migration) st.executeUpdate(sql)
                    conn.commit()
                } catch (t: Throwable) {
                    conn.rollback()
                    throw t
                } finally { conn.autoCommit = true }
            }
            v++
            setVersion(v)
            log.info("Migrated DB to v$v")
        }
    }

    private fun currentVersion(): Int = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA user_version").use { rs ->
            if (rs.next()) rs.getInt(1) else 0
        }
    }

    private fun setVersion(v: Int) {
        conn.createStatement().use { it.executeUpdate("PRAGMA user_version = $v") }
    }

    private fun createFreshSchema() {
        conn.createStatement().use { st ->
            st.executeUpdate(SQL_CREATE_PC_PLANS)
            st.executeUpdate(SQL_CREATE_PC_CONNECTION_LOGS)
            st.executeUpdate(SQL_CREATE_PC_SAVED_DEVICES_V8)
            st.executeUpdate(SQL_CREATE_PC_SCHEDULES)
            st.executeUpdate(SQL_CREATE_IDX_PC_SCHEDULES_DEVICE)
        }
    }

    fun close() = conn.close()

    companion object {
        const val TARGET_VERSION = 8

        // Fresh-install schema (what v8 looks like after all migrations).
        private val SQL_CREATE_PC_PLANS = """
            CREATE TABLE IF NOT EXISTS pc_plans (
              planId     TEXT NOT NULL PRIMARY KEY,
              planName   TEXT NOT NULL,
              icon       TEXT NOT NULL DEFAULT '⚡',
              steps_json TEXT NOT NULL DEFAULT '[]',
              createdAt  INTEGER NOT NULL
            )
        """.trimIndent()

        private val SQL_CREATE_PC_CONNECTION_LOGS = """
            CREATE TABLE IF NOT EXISTS pc_connection_logs (
              id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
              pcIp TEXT NOT NULL,
              pcPort INTEGER NOT NULL,
              pcName TEXT NOT NULL,
              userName TEXT NOT NULL,
              userEmail TEXT NOT NULL,
              userRole TEXT NOT NULL,
              userCompany TEXT NOT NULL,
              connectedAt INTEGER NOT NULL,
              disconnectedAt INTEGER NOT NULL,
              sessionDurationMs INTEGER NOT NULL,
              secretKeyHash TEXT NOT NULL,
              agentVersion TEXT NOT NULL
            )
        """.trimIndent()

        private val SQL_CREATE_PC_SAVED_DEVICES_V8 = """
            CREATE TABLE IF NOT EXISTS pc_saved_devices (
              id TEXT NOT NULL PRIMARY KEY,
              label TEXT NOT NULL,
              host TEXT NOT NULL,
              port INTEGER NOT NULL,
              streamPort INTEGER NOT NULL,
              secretKey TEXT NOT NULL,
              isMaster INTEGER NOT NULL,
              pcName TEXT NOT NULL,
              addedAt INTEGER NOT NULL,
              lastUsed INTEGER NOT NULL,
              lastSeenOnline INTEGER NOT NULL,
              thumbnailPath TEXT,
              thumbnailUpdatedAt INTEGER NOT NULL DEFAULT 0,
              macAddress TEXT,
              broadcastAddress TEXT,
              wolPort INTEGER NOT NULL DEFAULT 9,
              certFingerprint TEXT
            )
        """.trimIndent()

        private val SQL_CREATE_PC_SCHEDULES = """
            CREATE TABLE IF NOT EXISTS pc_schedules (
              id TEXT NOT NULL PRIMARY KEY,
              deviceId TEXT NOT NULL,
              action TEXT NOT NULL,
              planId TEXT,
              hour INTEGER NOT NULL,
              minute INTEGER NOT NULL,
              daysMask INTEGER NOT NULL,
              enabled INTEGER NOT NULL,
              lastFiredAt INTEGER NOT NULL DEFAULT 0,
              createdAt INTEGER NOT NULL
            )
        """.trimIndent()

        private const val SQL_CREATE_IDX_PC_SCHEDULES_DEVICE =
            "CREATE INDEX IF NOT EXISTS idx_pc_schedules_device ON pc_schedules(deviceId)"

        // Migrations — each list is executed in a transaction. Mirrors
        // Android's MIGRATION_{X}_{Y} bodies verbatim.
        private val MIGRATION_3_4: List<String> = emptyList() // no schema changes
        private val MIGRATION_4_5: List<String> = listOf(SQL_CREATE_PC_SAVED_DEVICES_V8.replace(
            "thumbnailPath TEXT,\n              thumbnailUpdatedAt INTEGER NOT NULL DEFAULT 0,\n              macAddress TEXT,\n              broadcastAddress TEXT,\n              wolPort INTEGER NOT NULL DEFAULT 9,\n              certFingerprint TEXT",
            "").trimEnd(',', '\n', ' ').replace(",\n            )", "\n            )"))
        private val MIGRATION_5_6: List<String> = listOf(
            "ALTER TABLE pc_saved_devices ADD COLUMN thumbnailPath TEXT",
            "ALTER TABLE pc_saved_devices ADD COLUMN thumbnailUpdatedAt INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE pc_saved_devices ADD COLUMN macAddress TEXT",
            "ALTER TABLE pc_saved_devices ADD COLUMN broadcastAddress TEXT",
            "ALTER TABLE pc_saved_devices ADD COLUMN wolPort INTEGER NOT NULL DEFAULT 9",
        )
        private val MIGRATION_6_7: List<String> = listOf(
            SQL_CREATE_PC_SCHEDULES, SQL_CREATE_IDX_PC_SCHEDULES_DEVICE,
        )
        private val MIGRATION_7_8: List<String> = listOf(
            "ALTER TABLE pc_saved_devices ADD COLUMN certFingerprint TEXT",
        )

        // map of "from-version" → SQL list that bumps to from+1
        private val migrations: Map<Int, List<String>> = mapOf(
            3 to MIGRATION_3_4,
            4 to MIGRATION_4_5,
            5 to MIGRATION_5_6,
            6 to MIGRATION_6_7,
            7 to MIGRATION_7_8,
        )

        @Volatile private var INSTANCE: PcControlDatabase? = null

        fun getDatabase(): PcControlDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: run {
                val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
                val dbDir = File(appData, "ITConnect").apply { mkdirs() }
                val dbFile = File(dbDir, "pc_control.db")
                PcControlDatabase(dbFile.absolutePath).also { INSTANCE = it }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  DAOs — hand-written prepared-statement wrappers.
//  Each DAO owns a MutableStateFlow to publish change events to
//  Compose collectors (replaces Room's observable queries).
// ─────────────────────────────────────────────────────────────

class PcPlanDao(private val db: PcControlDatabase) {
    private val _all = MutableStateFlow<List<PcPlan>>(emptyList())
    val allPlansFlow: Flow<List<PcPlan>> = _all.asStateFlow()

    init { runBlocking { refreshAll() } }

    fun getAllPlans(): Flow<List<PcPlan>> = allPlansFlow

    suspend fun getById(planId: String): PcPlan? = db.readTx { conn ->
        conn.prepareStatement("SELECT * FROM pc_plans WHERE planId = ?").use { ps ->
            ps.setString(1, planId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toPlan() else null }
        }
    }

    suspend fun insert(plan: PcPlan) {
        db.writeTx { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO pc_plans(planId, planName, icon, steps_json, createdAt) VALUES(?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, plan.planId)
                ps.setString(2, plan.planName)
                ps.setString(3, plan.icon)
                ps.setString(4, plan.stepsJson)
                ps.setLong(5, plan.createdAt)
                ps.executeUpdate()
            }
        }
        refreshAll()
    }

    suspend fun delete(plan: PcPlan) {
        db.writeTx { conn ->
            conn.prepareStatement("DELETE FROM pc_plans WHERE planId = ?").use { ps ->
                ps.setString(1, plan.planId)
                ps.executeUpdate()
            }
        }
        refreshAll()
    }

    suspend fun update(plan: PcPlan) = insert(plan) // REPLACE semantics

    suspend fun count(): Int = db.readTx { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM pc_plans").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    private suspend fun refreshAll() {
        val list = db.readTx { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT * FROM pc_plans ORDER BY createdAt DESC").use { rs ->
                    buildList { while (rs.next()) add(rs.toPlan()) }
                }
            }
        }
        _all.value = list
    }

    private fun ResultSet.toPlan() = PcPlan(
        planId    = getString("planId"),
        planName  = getString("planName"),
        icon      = getString("icon"),
        stepsJson = getString("steps_json"),
        createdAt = getLong("createdAt"),
    )
}

class PcConnectionLogDao(private val db: PcControlDatabase) {
    private val _recent = MutableStateFlow<List<PcConnectionLog>>(emptyList())
    val recentLogsFlow: Flow<List<PcConnectionLog>> = _recent.asStateFlow()

    init { runBlocking { refreshRecent() } }

    fun getRecentLogs(): Flow<List<PcConnectionLog>> = recentLogsFlow

    suspend fun getRecentLogsSync(limit: Int = 50): List<PcConnectionLog> = db.readTx { conn ->
        conn.prepareStatement("SELECT * FROM pc_connection_logs ORDER BY connectedAt DESC LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toLog()) } }
        }
    }

    suspend fun insert(logEntry: PcConnectionLog): Long {
        val id = db.writeTx { conn ->
            conn.prepareStatement(
                "INSERT INTO pc_connection_logs(pcIp,pcPort,pcName,userName,userEmail,userRole,userCompany," +
                    "connectedAt,disconnectedAt,sessionDurationMs,secretKeyHash,agentVersion) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setString(1, logEntry.pcIp)
                ps.setInt(2, logEntry.pcPort)
                ps.setString(3, logEntry.pcName)
                ps.setString(4, logEntry.userName)
                ps.setString(5, logEntry.userEmail)
                ps.setString(6, logEntry.userRole)
                ps.setString(7, logEntry.userCompany)
                ps.setLong(8, logEntry.connectedAt)
                ps.setLong(9, logEntry.disconnectedAt)
                ps.setLong(10, logEntry.sessionDurationMs)
                ps.setString(11, logEntry.secretKeyHash)
                ps.setString(12, logEntry.agentVersion)
                ps.executeUpdate()
                ps.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else -1L }
            }
        }
        refreshRecent()
        return id
    }

    suspend fun endSession(logId: Long, time: Long, duration: Long) {
        db.writeTx { conn ->
            conn.prepareStatement(
                "UPDATE pc_connection_logs SET disconnectedAt = ?, sessionDurationMs = ? WHERE id = ?"
            ).use { ps ->
                ps.setLong(1, time); ps.setLong(2, duration); ps.setLong(3, logId)
                ps.executeUpdate()
            }
        }
        refreshRecent()
    }

    suspend fun deleteOlderThan(before: Long) {
        db.writeTx { conn ->
            conn.prepareStatement("DELETE FROM pc_connection_logs WHERE connectedAt < ?").use { ps ->
                ps.setLong(1, before); ps.executeUpdate()
            }
        }
        refreshRecent()
    }

    suspend fun count(): Int = db.readTx { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM pc_connection_logs").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    private suspend fun refreshRecent() { _recent.value = getRecentLogsSync(50) }

    private fun ResultSet.toLog() = PcConnectionLog(
        id                 = getLong("id"),
        pcIp               = getString("pcIp"),
        pcPort             = getInt("pcPort"),
        pcName             = getString("pcName"),
        userName           = getString("userName"),
        userEmail          = getString("userEmail"),
        userRole           = getString("userRole"),
        userCompany        = getString("userCompany"),
        connectedAt        = getLong("connectedAt"),
        disconnectedAt     = getLong("disconnectedAt"),
        sessionDurationMs  = getLong("sessionDurationMs"),
        secretKeyHash      = getString("secretKeyHash"),
        agentVersion       = getString("agentVersion"),
    )
}

class PcSavedDeviceDao(private val db: PcControlDatabase) {
    private val _all = MutableStateFlow<List<PcSavedDevice>>(emptyList())
    val allFlow: Flow<List<PcSavedDevice>> = _all.asStateFlow()

    init { runBlocking { refreshAll() } }

    fun getAll(): Flow<List<PcSavedDevice>> = allFlow

    suspend fun getAllSync(): List<PcSavedDevice> = db.readTx { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM pc_saved_devices ORDER BY lastUsed DESC, addedAt DESC").use { rs ->
                buildList { while (rs.next()) add(rs.toDevice()) }
            }
        }
    }

    suspend fun findByHostPort(host: String, port: Int): PcSavedDevice? = db.readTx { conn ->
        conn.prepareStatement("SELECT * FROM pc_saved_devices WHERE host = ? AND port = ? LIMIT 1").use { ps ->
            ps.setString(1, host); ps.setInt(2, port)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toDevice() else null }
        }
    }

    suspend fun findById(id: String): PcSavedDevice? = db.readTx { conn ->
        conn.prepareStatement("SELECT * FROM pc_saved_devices WHERE id = ? LIMIT 1").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toDevice() else null }
        }
    }

    suspend fun upsert(device: PcSavedDevice) {
        db.writeTx { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO pc_saved_devices(id,label,host,port,streamPort,secretKey,isMaster,pcName," +
                    "addedAt,lastUsed,lastSeenOnline,thumbnailPath,thumbnailUpdatedAt,macAddress," +
                    "broadcastAddress,wolPort,certFingerprint) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, device.id)
                ps.setString(2, device.label)
                ps.setString(3, device.host)
                ps.setInt(4, device.port)
                ps.setInt(5, device.streamPort)
                ps.setString(6, device.secretKey)
                ps.setInt(7, if (device.isMaster) 1 else 0)
                ps.setString(8, device.pcName)
                ps.setLong(9, device.addedAt)
                ps.setLong(10, device.lastUsed)
                ps.setLong(11, device.lastSeenOnline)
                ps.setString(12, device.thumbnailPath)
                ps.setLong(13, device.thumbnailUpdatedAt)
                ps.setString(14, device.macAddress)
                ps.setString(15, device.broadcastAddress)
                ps.setInt(16, device.wolPort)
                ps.setString(17, device.certFingerprint)
                ps.executeUpdate()
            }
        }
        refreshAll()
    }

    suspend fun delete(device: PcSavedDevice) {
        db.writeTx { conn ->
            conn.prepareStatement("DELETE FROM pc_saved_devices WHERE id = ?").use { ps ->
                ps.setString(1, device.id); ps.executeUpdate()
            }
        }
        refreshAll()
    }

    suspend fun touch(id: String, ts: Long = System.currentTimeMillis()) {
        db.writeTx { conn ->
            conn.prepareStatement("UPDATE pc_saved_devices SET lastUsed = ? WHERE id = ?").use { ps ->
                ps.setLong(1, ts); ps.setString(2, id); ps.executeUpdate()
            }
        }
        refreshAll()
    }

    suspend fun markOnline(id: String, ts: Long = System.currentTimeMillis(), pcName: String) {
        db.writeTx { conn ->
            conn.prepareStatement(
                "UPDATE pc_saved_devices SET lastSeenOnline = ?, pcName = ? WHERE id = ?"
            ).use { ps ->
                ps.setLong(1, ts); ps.setString(2, pcName); ps.setString(3, id)
                ps.executeUpdate()
            }
        }
        refreshAll()
    }

    suspend fun setThumbnail(id: String, path: String?, ts: Long) {
        db.writeTx { conn ->
            conn.prepareStatement(
                "UPDATE pc_saved_devices SET thumbnailPath = ?, thumbnailUpdatedAt = ? WHERE id = ?"
            ).use { ps ->
                ps.setString(1, path); ps.setLong(2, ts); ps.setString(3, id)
                ps.executeUpdate()
            }
        }
        refreshAll()
    }

    private suspend fun refreshAll() { _all.value = getAllSync() }

    private fun ResultSet.toDevice() = PcSavedDevice(
        id                 = getString("id"),
        label              = getString("label"),
        host               = getString("host"),
        port               = getInt("port"),
        streamPort         = getInt("streamPort"),
        secretKey          = getString("secretKey"),
        isMaster           = getInt("isMaster") != 0,
        pcName             = getString("pcName"),
        addedAt            = getLong("addedAt"),
        lastUsed           = getLong("lastUsed"),
        lastSeenOnline     = getLong("lastSeenOnline"),
        thumbnailPath      = getString("thumbnailPath"),
        thumbnailUpdatedAt = getLong("thumbnailUpdatedAt"),
        macAddress         = getString("macAddress"),
        broadcastAddress   = getString("broadcastAddress"),
        wolPort            = getInt("wolPort"),
        certFingerprint    = getString("certFingerprint"),
    )
}

class PcScheduleDao(private val db: PcControlDatabase) {
    private val _all = MutableStateFlow<List<PcSchedule>>(emptyList())
    val allFlow: Flow<List<PcSchedule>> = _all.asStateFlow()

    init { runBlocking { refreshAll() } }

    fun getAll(): Flow<List<PcSchedule>> = allFlow

    fun getForDevice(id: String): Flow<List<PcSchedule>> {
        // Simple: derive from _all; list is small.
        val filtered = MutableStateFlow(_all.value.filter { it.deviceId == id }.sortedWith(
            compareBy({ it.hour }, { it.minute }),
        ))
        // A full reactive implementation would combine flows; callers that
        // need live updates should observe `allFlow` and filter client-side.
        return filtered.asStateFlow()
    }

    suspend fun getEnabledSync(): List<PcSchedule> = db.readTx { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM pc_schedules WHERE enabled = 1").use { rs ->
                buildList { while (rs.next()) add(rs.toSchedule()) }
            }
        }
    }

    suspend fun upsert(s: PcSchedule) {
        db.writeTx { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO pc_schedules(id,deviceId,action,planId,hour,minute,daysMask,enabled,lastFiredAt,createdAt)" +
                    " VALUES(?,?,?,?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, s.id); ps.setString(2, s.deviceId); ps.setString(3, s.action)
                ps.setString(4, s.planId); ps.setInt(5, s.hour); ps.setInt(6, s.minute)
                ps.setInt(7, s.daysMask); ps.setInt(8, if (s.enabled) 1 else 0)
                ps.setLong(9, s.lastFiredAt); ps.setLong(10, s.createdAt)
                ps.executeUpdate()
            }
        }
        refreshAll()
    }

    suspend fun delete(s: PcSchedule) {
        db.writeTx { conn ->
            conn.prepareStatement("DELETE FROM pc_schedules WHERE id = ?").use { ps ->
                ps.setString(1, s.id); ps.executeUpdate()
            }
        }
        refreshAll()
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        db.writeTx { conn ->
            conn.prepareStatement("UPDATE pc_schedules SET enabled = ? WHERE id = ?").use { ps ->
                ps.setInt(1, if (enabled) 1 else 0); ps.setString(2, id); ps.executeUpdate()
            }
        }
        refreshAll()
    }

    suspend fun markFired(id: String, ts: Long) {
        db.writeTx { conn ->
            conn.prepareStatement("UPDATE pc_schedules SET lastFiredAt = ? WHERE id = ?").use { ps ->
                ps.setLong(1, ts); ps.setString(2, id); ps.executeUpdate()
            }
        }
        refreshAll()
    }

    private suspend fun refreshAll() {
        _all.value = db.readTx { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT * FROM pc_schedules ORDER BY hour, minute").use { rs ->
                    buildList { while (rs.next()) add(rs.toSchedule()) }
                }
            }
        }
    }

    private fun ResultSet.toSchedule() = PcSchedule(
        id          = getString("id"),
        deviceId    = getString("deviceId"),
        action      = getString("action"),
        planId      = getString("planId"),
        hour        = getInt("hour"),
        minute      = getInt("minute"),
        daysMask    = getInt("daysMask"),
        enabled     = getInt("enabled") != 0,
        lastFiredAt = getLong("lastFiredAt"),
        createdAt   = getLong("createdAt"),
    )
}

// ─────────────────────────────────────────────────────────────
//  REPOSITORY — mirrors Android surface so ported ViewModels
//  compile unchanged.
// ─────────────────────────────────────────────────────────────

class PcControlRepository(
    private val planDao: PcPlanDao,
    private val logDao: PcConnectionLogDao? = null,
    private val deviceDao: PcSavedDeviceDao? = null,
    private val scheduleDao: PcScheduleDao? = null,
) {
    val allPlans: Flow<List<PcPlan>> = planDao.getAllPlans()

    suspend fun insertPlan(plan: PcPlan) = planDao.insert(plan)
    suspend fun deletePlan(plan: PcPlan) = planDao.delete(plan)
    suspend fun updatePlan(plan: PcPlan) = planDao.update(plan)
    suspend fun getPlanById(id: String) = planDao.getById(id)

    suspend fun seedIfEmpty() {
        if (planDao.count() > 0) return
        val samples = listOf(
            PcPlan.create("sys_lock",      "Lock PC",         steps = listOf(PcStep("SYSTEM_CMD", "LOCK"))),
            PcPlan.create("sys_sleep",     "Sleep PC",        steps = listOf(PcStep("SYSTEM_CMD", "SLEEP"))),
            PcPlan.create("sys_shutdown",  "Shutdown",        steps = listOf(PcStep("SYSTEM_CMD", "SHUTDOWN"))),
            PcPlan.create("sys_restart",   "Restart",         steps = listOf(PcStep("SYSTEM_CMD", "RESTART"))),
            PcPlan.create("prod_screenshot", "Screenshot",    steps = listOf(PcStep("SYSTEM_CMD", "SCREENSHOT"))),
            PcPlan.create("prod_mute",     "Toggle Mute",     steps = listOf(PcStep("SYSTEM_CMD", "MUTE"))),
            PcPlan.create("prod_desktop",  "Show Desktop",    steps = listOf(PcStep("KEY_PRESS", "WIN+D"))),
        )
        samples.forEach { planDao.insert(it) }
    }

    val connectionLogs: Flow<List<PcConnectionLog>>? get() = logDao?.getRecentLogs()

    suspend fun logConnection(logEntry: PcConnectionLog): Long =
        logDao?.insert(logEntry) ?: -1L

    suspend fun endConnectionSession(logId: Long) {
        if (logId <= 0) return
        val now = System.currentTimeMillis()
        val logs = logDao?.getRecentLogsSync(1) ?: return
        val session = logs.find { it.id == logId } ?: return
        val duration = now - session.connectedAt
        logDao.endSession(logId, now, duration)
    }

    suspend fun getRecentConnectionLogs(): List<PcConnectionLog> =
        logDao?.getRecentLogsSync(50) ?: emptyList()

    suspend fun cleanOldLogs(daysToKeep: Int = 30) {
        val cutoff = System.currentTimeMillis() - (daysToKeep * 86_400_000L)
        logDao?.deleteOlderThan(cutoff)
    }

    val savedDevices: Flow<List<PcSavedDevice>> =
        deviceDao?.getAll() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun saveDevice(d: PcSavedDevice) { deviceDao?.upsert(d) }
    suspend fun deleteDevice(d: PcSavedDevice) { deviceDao?.delete(d) }
    suspend fun touchDevice(id: String) { deviceDao?.touch(id) }
    suspend fun markDeviceOnline(id: String, pcName: String) {
        deviceDao?.markOnline(id, System.currentTimeMillis(), pcName)
    }
    suspend fun findDeviceByAddress(host: String, port: Int): PcSavedDevice? =
        deviceDao?.findByHostPort(host, port)

    suspend fun findDeviceById(id: String): PcSavedDevice? = deviceDao?.findById(id)

    suspend fun setDeviceThumbnail(id: String, path: String?, ts: Long = System.currentTimeMillis()) {
        deviceDao?.setThumbnail(id, path, ts)
    }

    val allSchedules: Flow<List<PcSchedule>> =
        scheduleDao?.getAll() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    fun schedulesForDevice(deviceId: String): Flow<List<PcSchedule>> =
        scheduleDao?.getForDevice(deviceId) ?: kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun upsertSchedule(s: PcSchedule) { scheduleDao?.upsert(s) }
    suspend fun deleteSchedule(s: PcSchedule) { scheduleDao?.delete(s) }
    suspend fun setScheduleEnabled(id: String, enabled: Boolean) {
        scheduleDao?.setEnabled(id, enabled)
    }

    /**
     * Mirrors the Android fix (commit 2419cbb) for the 60% silent-miss bug.
     * Checks enabled schedules whose wall-clock has arrived within the catch-up
     * window and haven't already fired for today's slot.
     */
    suspend fun dueSchedulesNow(
        cal: Calendar = Calendar.getInstance(),
    ): List<PcSchedule> {
        val all    = scheduleDao?.getEnabledSync() ?: return emptyList()
        val now    = cal.timeInMillis
        val dowBit = 1 shl (cal.get(Calendar.DAY_OF_WEEK) - 1)

        return all.filter { s ->
            if ((s.daysMask and dowBit) == 0) return@filter false
            val schedCal = (cal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, s.hour)
                set(Calendar.MINUTE, s.minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val delta = now - schedCal.timeInMillis
            if (delta < -PRE_FIRE_TOLERANCE_MS) return@filter false
            if (delta >  CATCH_UP_MS)           return@filter false
            s.lastFiredAt < schedCal.timeInMillis
        }
    }

    suspend fun markScheduleFired(id: String, ts: Long = System.currentTimeMillis()) {
        scheduleDao?.markFired(id, ts)
    }

    companion object {
        private const val PRE_FIRE_TOLERANCE_MS = 90_000L
        private const val CATCH_UP_MS           = 30 * 60_000L
    }
}

package com.itconnect.desktop.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

// ─────────────────────────────────────────────────────────────
//  STEP MODEL
// ─────────────────────────────────────────────────────────────

data class PcStep(
    val type    : String,
    val value   : String       = "",
    val args    : List<String> = emptyList(),
    val x       : Int          = 0,
    val y       : Int          = 0,
    val button  : String       = "left",
    val double  : Boolean      = false,
    val action  : String       = "",
    val from    : String       = "",
    val to      : String       = "",
    val ms      : Int          = 1000,
    val amount  : Int          = 3,
    val message : String       = ""
)

enum class PcStepType(val display: String, val description: String) {
    LAUNCH_APP  ("Launch App",     "Open any application"),
    KILL_APP    ("Kill App",       "Close a running process"),
    KEY_PRESS   ("Key Press",      "Press a key or shortcut"),
    TYPE_TEXT   ("Type Text",      "Type text on PC"),
    MOUSE_CLICK ("Mouse Click",    "Click at screen position"),
    MOUSE_MOVE  ("Mouse Move",     "Move mouse to position"),
    MOUSE_SCROLL("Scroll",         "Scroll mouse wheel"),
    RUN_SCRIPT  ("Run Script",     "Execute .py/.bat/.ps1"),
    OPEN_FILE   ("Open File",      "Open file with default app"),
    FILE_OP     ("File Op",        "Copy / Move / Delete files"),
    SYSTEM_CMD  ("System Command", "Lock/Sleep/Volume etc"),
    WAIT        ("Wait",           "Pause between steps")
}

fun PcStep.hasFilePath(): Boolean = when (type) {
    "LAUNCH_APP"  -> args.isNotEmpty() && args[0].isNotBlank()
    "OPEN_FILE"   -> value.isNotBlank()
    "RUN_SCRIPT"  -> value.isNotBlank()
    else          -> false
}

fun PcStep.filePathArg(): String = when (type) {
    "LAUNCH_APP"  -> args.firstOrNull() ?: ""
    "OPEN_FILE"   -> value
    "RUN_SCRIPT"  -> value
    else          -> ""
}

fun PcStep.fileExtension(): String =
    filePathArg().substringAfterLast('.', "").lowercase()

fun PcStep.fileIconVector(): ImageVector {
    val ext = fileExtension()
    return when {
        ext in listOf("mp4","mkv","avi","mov","wmv")         -> Icons.Default.Movie
        ext in listOf("mp3","wav","flac","aac","m4a")         -> Icons.Default.MusicNote
        ext in listOf("jpg","jpeg","png","gif","bmp","webp")  -> Icons.Default.Image
        ext == "pdf"                                          -> Icons.Default.PictureAsPdf
        ext in listOf("doc","docx","rtf")                    -> Icons.Default.Description
        ext in listOf("xls","xlsx","csv")                    -> Icons.Default.GridOn
        ext in listOf("ppt","pptx")                          -> Icons.Default.Slideshow
        ext in listOf("py","bat","ps1","sh","cmd")           -> Icons.Default.Code
        ext in listOf("txt","log","md")                      -> Icons.Default.Article
        ext in listOf("zip","rar","7z","tar","gz")           -> Icons.Default.FolderZip
        ext in listOf("exe","msi")                           -> Icons.Default.Computer
        value.startsWith("http")                             -> Icons.Default.Language
        else                                                  -> Icons.Default.InsertDriveFile
    }
}

val PC_COMMON_KEYS = listOf(
    "F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12",
    "ENTER","ESC","SPACE","TAB","BACKSPACE","DELETE",
    "UP","DOWN","LEFT","RIGHT","HOME","END","PAGE_UP","PAGE_DOWN",
    "CTRL+C","CTRL+V","CTRL+Z","CTRL+S","CTRL+A",
    "ALT+F4","ALT+TAB","WIN+D","WIN+L","WIN+R","WIN+E",
    "WIN+TAB","WIN+I","WIN+A","WIN+S","CTRL+SHIFT+ESC"
)

val PC_SYSTEM_COMMANDS = listOf(
    "LOCK","SLEEP","SHUTDOWN","RESTART",
    "VOLUME_UP","VOLUME_DOWN","MUTE","VOLUME_SET",
    "SCREENSHOT","OPEN_URL","OPEN_FOLDER","WIN_R",
    "TASK_MANAGER","SETTINGS","CONTROL_PANEL",
    "DISPLAY_INTERNAL","DISPLAY_CLONE","DISPLAY_EXTEND","DISPLAY_EXTERNAL"
)

val PC_FILE_ACTIONS = listOf("COPY","MOVE","DELETE","MKDIR","RENAME")

// ─────────────────────────────────────────────────────────────
//  SERIALIZER
// ─────────────────────────────────────────────────────────────

object PcStepSerializer {
    private val gson = Gson()

    fun toJson(steps: List<PcStep>): String = gson.toJson(steps)

    fun fromJson(json: String): List<PcStep> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val type = object : TypeToken<List<PcStep>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun stepToJson(step: PcStep): String = gson.toJson(step)

    fun stepFromJson(json: String): PcStep? = try {
        gson.fromJson(json, PcStep::class.java)
    } catch (e: Exception) { null }
}

// ─────────────────────────────────────────────────────────────
//  PLAN ENTITY  (table: pc_plans)
// ─────────────────────────────────────────────────────────────

data class PcPlan(
    val planId    : String,
    val planName  : String,
    val icon      : String = "⚡",
    val stepsJson : String = "[]",
    val createdAt : Long   = System.currentTimeMillis()
) {
    val steps: List<PcStep>
        get() = PcStepSerializer.fromJson(stepsJson)

    companion object {
        fun create(
            planId   : String,
            planName : String,
            icon     : String       = "⚡",
            steps    : List<PcStep> = emptyList()
        ) = PcPlan(
            planId    = planId,
            planName  = planName,
            icon      = icon,
            stepsJson = PcStepSerializer.toJson(steps)
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  CONNECTION LOG  (table: pc_connection_logs)
// ─────────────────────────────────────────────────────────────

data class PcConnectionLog(
    val id          : Long   = 0,
    val pcIp        : String = "",
    val pcPort      : Int    = 5000,
    val pcName      : String = "",
    val userName    : String = "",
    val userEmail   : String = "",
    val userRole    : String = "",
    val userCompany : String = "",
    val connectedAt : Long   = System.currentTimeMillis(),
    val disconnectedAt: Long = 0,
    val sessionDurationMs: Long = 0,
    val secretKeyHash: String = "",
    val agentVersion: String = ""
)

// ─────────────────────────────────────────────────────────────
//  SAVED DEVICE  (table: pc_saved_devices)
// ─────────────────────────────────────────────────────────────

data class PcSavedDevice(
    val id        : String = UUID.randomUUID().toString(),
    val label     : String = "",
    val host      : String = "",
    val port      : Int    = 5000,
    val streamPort: Int    = 5001,
    val secretKey : String = "",
    val isMaster  : Boolean = false,
    val pcName    : String = "",
    val addedAt   : Long   = System.currentTimeMillis(),
    val lastUsed  : Long   = 0L,
    val lastSeenOnline: Long = 0L,
    val thumbnailPath     : String? = null,
    val thumbnailUpdatedAt: Long    = 0L,
    val macAddress        : String? = null,
    val broadcastAddress  : String? = null,
    val wolPort           : Int     = 9,
    val certFingerprint   : String? = null,
)

// ─────────────────────────────────────────────────────────────
//  SCHEDULE  (table: pc_schedules)
// ─────────────────────────────────────────────────────────────

data class PcSchedule(
    val id          : String = UUID.randomUUID().toString(),
    val deviceId    : String,
    val action      : String,
    val planId      : String? = null,
    val hour        : Int,
    val minute      : Int,
    val daysMask    : Int,
    val enabled     : Boolean = true,
    val lastFiredAt : Long    = 0L,
    val createdAt   : Long    = System.currentTimeMillis()
)

// ─────────────────────────────────────────────────────────────
//  BROWSE / NETWORK / TRANSFER MODELS (not persisted)
// ─────────────────────────────────────────────────────────────

data class PcDrive(
    val letter  : String,
    val label   : String,
    val freeGb  : Float,
    val totalGb : Float,
)

data class PcFileItem(
    val name      : String,
    val path      : String,
    val isDir     : Boolean,
    val sizeKb    : Long    = 0,
    val extension : String  = "",
    val modTime   : Long    = 0,
)

data class PcInstalledApp(
    val name      : String,
    val exePath   : String,
    val icon      : String  = "",
    val isRunning : Boolean = false,
)

enum class PcFileFilter(val extensions: List<String>, val label: String) {
    ALL    (emptyList(),                                           "All Files"),
    MEDIA  (listOf("mp4","mkv","avi","mp3","wav","flac","mov"),   "Media"),
    DOCS   (listOf("pdf","docx","doc","pptx","ppt","xlsx","txt"), "Documents"),
    SCRIPTS(listOf("py","bat","ps1","sh","cmd"),                  "Scripts"),
    IMAGES (listOf("jpg","jpeg","png","gif","bmp","webp"),        "Images"),
}

data class PcRecentPath(
    val path  : String,
    val label : String,
    val isApp : Boolean = false,
    val icon  : String  = "",
)

data class PcNetworkResult<T>(
    val success : Boolean,
    val data    : T?      = null,
    val error   : String? = null,
)

data class PcPingResponse(val status: String, val pc_name: String)
data class PcExecuteResponse(val status: String, val plan: String? = null)
data class PcMouseDelta(val dx: Float, val dy: Float)
data class PcMouseClick(val button: String = "left", val double: Boolean = false)
data class PcMouseScroll(val amount: Int)
data class PcKeyCommand(val value: String)
data class PcTypeCommand(val value: String)

data class PcTransferProgress(
    val fileName    : String,
    val bytesTotal  : Long,
    val bytesDone   : Long,
    val speedBps    : Long,
    val isUpload    : Boolean,
    val isDone      : Boolean     = false,
    val error       : String?     = null,
) {
    val progressFraction: Float
        get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f

    val speedLabel: String get() = when {
        speedBps > 1_000_000 -> "%.1f MB/s".format(speedBps / 1_000_000.0)
        speedBps > 1_000     -> "%.0f KB/s".format(speedBps / 1_000.0)
        else                 -> "$speedBps B/s"
    }

    val etaLabel: String get() {
        if (speedBps <= 0 || isDone) return ""
        val remaining = (bytesTotal - bytesDone).coerceAtLeast(0)
        val secs = remaining / speedBps
        return when {
            secs > 3600 -> "${secs / 3600}h ${(secs % 3600) / 60}m"
            secs > 60   -> "${secs / 60}m ${secs % 60}s"
            else        -> "${secs}s"
        }
    }

    val sizeLabel: String get() {
        val total = fmtBytes(bytesTotal)
        val done  = fmtBytes(bytesDone)
        return "$done / $total"
    }

    private fun fmtBytes(b: Long): String = when {
        b > 1_000_000_000 -> "%.1f GB".format(b / 1e9)
        b > 1_000_000     -> "%.1f MB".format(b / 1e6)
        b > 1_000         -> "%.0f KB".format(b / 1e3)
        else              -> "$b B"
    }
}

data class PcOpenWithChoice(
    val appName : String,
    val exePath : String,
    val icon    : String = "",
)

data class PcOpenWithDialog(
    val filePath : String,
    val choices  : List<PcOpenWithChoice>,
)

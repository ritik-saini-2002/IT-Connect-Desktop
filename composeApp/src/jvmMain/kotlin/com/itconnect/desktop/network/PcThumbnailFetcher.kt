package com.itconnect.desktop.network

import com.itconnect.desktop.data.PcSavedDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Base64

/**
 * Fetches a low-res JPEG from the agent's `/screen/capture` endpoint and
 * caches it under `%APPDATA%/ITConnect/pc_thumbs/<deviceId>.jpg`. Returns the
 * final absolute path on success.
 *
 * Desktop port of the Android [PcThumbnailFetcher]:
 *  - Context → absolute APPDATA dir (same dir the DB lives in).
 *  - `android.util.Base64` → `java.util.Base64`.
 *  - SLF4J replaces android.util.Log.
 */
object PcThumbnailFetcher {

    private val log = LoggerFactory.getLogger("PcThumbnail")
    private const val DIR_NAME  = "pc_thumbs"
    private const val QUALITY   = 20
    private const val SCALE_DIV = 6

    private fun thumbDir(): File {
        val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
        return File(File(appData, "ITConnect"), DIR_NAME).apply { mkdirs() }
    }

    suspend fun fetch(device: PcSavedDevice): String? = withContext(Dispatchers.IO) {
        if (device.host.isBlank() || device.secretKey.isBlank()) return@withContext null
        val api = PcControlApiClient(
            PcControlSettings(device.host, device.port, device.secretKey)
        )
        val result = api.captureScreen(quality = QUALITY, scale = SCALE_DIV)
        val b64 = result.data?.takeIf { result.success } ?: return@withContext null

        val bytes = runCatching { Base64.getDecoder().decode(b64) }
            .getOrNull()
            ?.takeIf { it.size > 256 }
            ?: return@withContext null

        writeAtomic(device.id, bytes)
    }

    fun cachedFile(deviceId: String): File = File(thumbDir(), "$deviceId.jpg")

    fun deleteCached(deviceId: String) {
        runCatching { cachedFile(deviceId).delete() }
    }

    private fun writeAtomic(deviceId: String, bytes: ByteArray): String? {
        val dir = thumbDir()
        val tmp = File(dir, "$deviceId.tmp")
        val out = File(dir, "$deviceId.jpg")
        return try {
            tmp.writeBytes(bytes)
            if (out.exists()) out.delete()
            if (tmp.renameTo(out)) out.absolutePath else null
        } catch (e: Exception) {
            log.warn("thumb write failed for {}: {}", deviceId, e.message)
            runCatching { tmp.delete() }
            null
        }
    }
}

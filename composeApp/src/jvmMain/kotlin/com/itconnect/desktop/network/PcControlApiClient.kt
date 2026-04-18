package com.itconnect.desktop.network

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.itconnect.desktop.data.PcDrive
import com.itconnect.desktop.data.PcExecuteResponse
import com.itconnect.desktop.data.PcFileFilter
import com.itconnect.desktop.data.PcFileItem
import com.itconnect.desktop.data.PcInstalledApp
import com.itconnect.desktop.data.PcNetworkResult
import com.itconnect.desktop.data.PcOpenWithChoice
import com.itconnect.desktop.data.PcOpenWithDialog
import com.itconnect.desktop.data.PcPingResponse
import com.itconnect.desktop.data.PcPlan
import com.itconnect.desktop.data.PcRecentPath
import com.itconnect.desktop.data.PcStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.ByteString.Companion.encodeUtf8
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

private val JSON_MT   = "application/json; charset=utf-8".toMediaType()
private val BINARY_MT = "application/octet-stream".toMediaType()

private const val CHUNK_SIZE      = 4 * 1024 * 1024   // 4 MB per chunk
private const val SOCKET_BUF      = 8 * 1024 * 1024   // 8 MB socket buffer
private const val CONNECT_TIMEOUT = 6L
private const val READ_TIMEOUT    = 0L                 // 0 = infinite (streaming)
private const val WRITE_TIMEOUT   = 0L                 // 0 = infinite (large upload)
private const val PING_TIMEOUT    = 3L

private val apiLog = LoggerFactory.getLogger("PcControlApi")

// ── Base client ──────────────────────────────────────────────────────────────

abstract class PcBaseClient(protected val settings: PcControlSettings) {

    protected val gson = Gson()

    private val opsDispatcher = Dispatcher().apply {
        maxRequests        = 128
        maxRequestsPerHost = 64
    }

    protected val http = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .socketFactory(tunedSocketFactory())
        .dispatcher(opsDispatcher)
        .addInterceptor(PrivateNetworkInterceptor())
        .applyPinning(settings)
        .build()

    protected val httpFast = OkHttpClient.Builder()
        .connectTimeout(PING_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(PING_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(PING_TIMEOUT, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .dispatcher(opsDispatcher)
        .addInterceptor(PrivateNetworkInterceptor())
        .applyPinning(settings)
        .build()

    protected val httpTransfer = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .socketFactory(tunedSocketFactory())
        .connectionPool(ConnectionPool(5, 60, TimeUnit.SECONDS))
        .addInterceptor(PrivateNetworkInterceptor())
        .applyPinning(settings)
        .build()

    private fun tunedSocketFactory(): javax.net.SocketFactory =
        object : javax.net.SocketFactory() {
            private val delegate = javax.net.SocketFactory.getDefault()
            private fun tune(s: java.net.Socket): java.net.Socket {
                runCatching {
                    s.sendBufferSize    = SOCKET_BUF
                    s.receiveBufferSize = SOCKET_BUF
                    s.tcpNoDelay        = true
                    s.keepAlive         = true
                }
                return s
            }
            override fun createSocket() = tune(delegate.createSocket())
            override fun createSocket(host: String, port: Int) = tune(delegate.createSocket(host, port))
            override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int) = tune(delegate.createSocket(host, port, localHost, localPort))
            override fun createSocket(host: java.net.InetAddress, port: Int) = tune(delegate.createSocket(host, port))
            override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int) = tune(delegate.createSocket(address, port, localAddress, localPort))
        }

    protected fun baseRequest(path: String): Request.Builder {
        val hostName = runCatching { InetAddress.getLocalHost().hostName }.getOrElse { "Windows" }
        return Request.Builder()
            .url("${settings.baseUrl}$path")
            .header("X-Secret-Key", settings.secretKey)
            .header("X-Device-Name", "$hostName/Windows")
            .header("X-Device-Id",   "${hostName.lowercase()}_desktop")
    }

    protected suspend fun get(path: String): PcNetworkResult<String> =
        withContext(Dispatchers.IO) {
            try {
                http.newCall(baseRequest(path).get().build()).execute().use { r ->
                    if (r.isSuccessful) PcNetworkResult(true, r.body?.string())
                    else PcNetworkResult(false, error = "HTTP ${r.code}")
                }
            } catch (e: Exception) {
                PcNetworkResult(false, error = e.message ?: "Network error")
            }
        }

    suspend fun post(path: String, body: Any): PcNetworkResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val rb = gson.toJson(body).toRequestBody(JSON_MT)
                http.newCall(baseRequest(path).post(rb).build()).execute().use { r ->
                    if (r.isSuccessful) PcNetworkResult(true, r.body?.string())
                    else PcNetworkResult(false, error = "HTTP ${r.code}")
                }
            } catch (e: Exception) {
                PcNetworkResult(false, error = e.message ?: "Network error")
            }
        }

    protected fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

// ── Plan / screen / admin client ─────────────────────────────────────────────

class PcControlApiClient(settings: PcControlSettings) : PcBaseClient(settings) {

    suspend fun ping(): PcNetworkResult<PcPingResponse> = withContext(Dispatchers.IO) {
        try {
            httpFast.newCall(baseRequest("/ping").get().build()).execute().use { r ->
                if (r.isSuccessful)
                    PcNetworkResult(true, gson.fromJson(r.body?.string(), PcPingResponse::class.java))
                else
                    PcNetworkResult(false, error = "HTTP ${r.code}")
            }
        } catch (e: Exception) {
            PcNetworkResult(false, error = e.message ?: "Unreachable")
        }
    }

    suspend fun executePlan(plan: PcPlan): PcNetworkResult<PcExecuteResponse> {
        LiveStreamGate.bump()
        val stepsArray = JsonParser.parseString(plan.stepsJson).asJsonArray
        val payload    = com.google.gson.JsonObject().apply {
            addProperty("planName", plan.planName)
            add("steps", stepsArray)
        }
        val result = post("/execute", payload)
        return if (result.success)
            PcNetworkResult(true, gson.fromJson(result.data, PcExecuteResponse::class.java))
        else PcNetworkResult(false, error = result.error)
    }

    suspend fun executeQuickStep(step: PcStep): PcNetworkResult<String> {
        LiveStreamGate.bump()
        return post("/quick", gson.toJsonTree(step).asJsonObject)
    }

    suspend fun getProcesses(): PcNetworkResult<List<String>> {
        val r = get("/processes")
        if (!r.success) return PcNetworkResult(false, error = r.error)
        return try {
            val map = gson.fromJson(r.data, Map::class.java)
            @Suppress("UNCHECKED_CAST")
            PcNetworkResult(true, map["processes"] as? List<String> ?: emptyList())
        } catch (e: Exception) { PcNetworkResult(false, error = e.message) }
    }

    suspend fun getScreenSize(): PcNetworkResult<Pair<Int, Int>> {
        val r = get("/screen_size")
        if (!r.success) return PcNetworkResult(false, error = r.error)
        return try {
            val map = gson.fromJson(r.data, Map::class.java)
            val w   = (map["width"]  as? Double)?.toInt() ?: 1920
            val h   = (map["height"] as? Double)?.toInt() ?: 1080
            PcNetworkResult(true, Pair(w, h))
        } catch (e: Exception) { PcNetworkResult(false, error = e.message) }
    }

    suspend fun captureScreen(quality: Int = 25, scale: Int = 4): PcNetworkResult<String> {
        val r = get("/screen/capture?q=$quality&s=$scale")
        if (!r.success) return PcNetworkResult(false, error = r.error)
        return try {
            val map = gson.fromJson(r.data, Map::class.java)
            val img = (map["data"] as? String) ?: (map["image"] as? String)
                ?: return PcNetworkResult(false, error = "No image")
            PcNetworkResult(true, img)
        } catch (e: Exception) { PcNetworkResult(false, error = e.message) }
    }

    /** Single-frame JPEG fallback when MJPEG stream is unavailable. */
    suspend fun fetchScreenFrame(quality: Int = 25, scale: Int = 4): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = baseRequest("/screen/capture?q=$quality&s=$scale").get().build()
                httpFast.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val body = r.body?.string() ?: return@use null
                    val obj  = org.json.JSONObject(body)
                    if (!obj.optBoolean("ok", false)) return@use null
                    val b64  = obj.optString("data", "").ifBlank { return@use null }
                    Base64.getDecoder().decode(b64)
                }
            }.getOrNull()
        }

    /**
     * Opens the agent's MJPEG screen stream and invokes [onFrame] with each
     * JPEG payload. Runs until cancelled or the body ends. See Android doc
     * for the full boundary-parsing rationale.
     */
    suspend fun streamScreen(
        width  : Int = 1366,
        quality: Int = 40,
        fps    : Int = 30,
        onFrame: suspend (ByteArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val host = settings.pcIpAddress
        if (host.isBlank()) return@withContext
        val scheme = if (settings.certFingerprint.isNullOrBlank()) "http" else "https"
        val url = "$scheme://$host:${settings.streamPort}" +
            "/screen/stream?q=$quality&w=$width&fps=$fps" +
            "&key=${URLEncoder.encode(settings.secretKey, "UTF-8")}"
        val req = Request.Builder()
            .url(url)
            .header("X-Secret-Key", settings.secretKey)
            .header("Cache-Control", "no-cache")
            .build()

        val call = httpTransfer.newCall(req)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    apiLog.warn("stream HTTP {}", resp.code)
                    return@use
                }
                val source = resp.body?.source() ?: return@use
                val boundary = "\r\n--frame".encodeUtf8()
                val headerEnd = "\r\n\r\n".encodeUtf8()

                while (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive != false) {
                    val headersEnd = source.indexOf(headerEnd)
                    if (headersEnd < 0L) break
                    source.skip(headersEnd + headerEnd.size)

                    val nextBoundary = source.indexOf(boundary)
                    if (nextBoundary < 0L) break
                    val jpeg = source.readByteArray(nextBoundary)
                    source.skip(2)

                    if (jpeg.isNotEmpty()) onFrame(jpeg)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            apiLog.warn("stream error: {}", e.message)
        } finally {
            runCatching { call.cancel() }
        }
    }

    suspend fun pollOpenWithDialog(): PcNetworkResult<PcOpenWithDialog?> {
        val r = get("/dialog/openwith/poll")
        if (!r.success) return PcNetworkResult(true, null)
        return try {
            val json   = gson.fromJson(r.data, Map::class.java)
            val hasDlg = json["has_dialog"] as? Boolean ?: false
            if (!hasDlg) return PcNetworkResult(true, null)
            val filePath = json["file_path"] as? String ?: ""
            @Suppress("UNCHECKED_CAST")
            val rawChoices = json["choices"] as? List<Map<String, Any>> ?: emptyList()
            val choices = rawChoices.map {
                PcOpenWithChoice(
                    appName = it["name"] as? String ?: "",
                    exePath = it["exe"]  as? String ?: "",
                    icon    = it["icon"] as? String ?: "📦",
                )
            }
            PcNetworkResult(true, PcOpenWithDialog(filePath, choices))
        } catch (e: Exception) { PcNetworkResult(false, error = e.message) }
    }

    suspend fun resolveOpenWithDialog(exePath: String): PcNetworkResult<String> =
        post("/dialog/openwith/resolve", mapOf("exe" to exePath))

    // ── Master key admin endpoints ──────────────────────────────────────────

    suspend fun getConnectedUsers(masterKey: String): PcNetworkResult<String> =
        adminGet("/connections", masterKey)

    suspend fun kickUser(masterKey: String, deviceId: String): PcNetworkResult<String> =
        adminPost("/connections/kick", masterKey, mapOf("device_id" to deviceId))

    suspend fun changeSecretKey(masterKey: String, newKey: String): PcNetworkResult<String> =
        adminPost("/settings/key", masterKey, mapOf("new_key" to newKey))

    suspend fun getConnectionLogs(masterKey: String): PcNetworkResult<String> =
        adminGet("/connections/logs", masterKey)

    private suspend fun adminGet(path: String, masterKey: String): PcNetworkResult<String> =
        withContext(Dispatchers.IO) {
            try {
                http.newCall(
                    Request.Builder()
                        .url("${settings.baseUrl}$path")
                        .header("X-Secret-Key", masterKey)
                        .get().build()
                ).execute().use { r ->
                    if (r.isSuccessful) PcNetworkResult(true, r.body?.string())
                    else PcNetworkResult(false, error = "HTTP ${r.code}")
                }
            } catch (e: Exception) { PcNetworkResult(false, error = e.message) }
        }

    private suspend fun adminPost(path: String, masterKey: String, body: Any): PcNetworkResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val rb = gson.toJson(body).toRequestBody(JSON_MT)
                http.newCall(
                    Request.Builder()
                        .url("${settings.baseUrl}$path")
                        .header("X-Secret-Key", masterKey)
                        .post(rb).build()
                ).execute().use { r ->
                    if (r.isSuccessful) PcNetworkResult(true, r.body?.string())
                    else PcNetworkResult(false, error = "HTTP ${r.code}")
                }
            } catch (e: Exception) { PcNetworkResult(false, error = e.message) }
        }

    // ── Agent self-update ────────────────────────────────────────────────────

    private val httpAgentUpdate by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .addInterceptor(PrivateNetworkInterceptor())
            .applyPinning(settings)
            .build()
    }

    suspend fun uploadAgentCode(masterKey: String, code: ByteArray): PcNetworkResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val b64    = Base64.getEncoder().encodeToString(code)
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(code)
                    .joinToString("") { "%02x".format(it) }
                val body = gson.toJson(mapOf("code" to b64, "sha256" to digest))
                    .toRequestBody(JSON_MT)
                httpAgentUpdate.newCall(
                    Request.Builder()
                        .url("${settings.baseUrl}/agent/update")
                        .header("X-Secret-Key", masterKey)
                        .post(body).build()
                ).execute().use { r ->
                    if (r.isSuccessful) PcNetworkResult(true, r.body?.string())
                    else PcNetworkResult(false, error = "HTTP ${r.code}: ${r.body?.string().orEmpty()}")
                }
            } catch (e: Exception) { PcNetworkResult(false, error = e.message) }
        }

    suspend fun rollbackAgent(masterKey: String): PcNetworkResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val empty = "{}".toRequestBody(JSON_MT)
                httpAgentUpdate.newCall(
                    Request.Builder()
                        .url("${settings.baseUrl}/agent/rollback")
                        .header("X-Secret-Key", masterKey)
                        .post(empty).build()
                ).execute().use { r ->
                    if (r.isSuccessful) PcNetworkResult(true, r.body?.string())
                    else PcNetworkResult(false, error = "HTTP ${r.code}: ${r.body?.string().orEmpty()}")
                }
            } catch (e: Exception) { PcNetworkResult(false, error = e.message) }
        }

    suspend fun getAgentVersion(masterKey: String): PcNetworkResult<String> =
        adminGet("/agent/version", masterKey)
}

// ── Browse / file-transfer client ───────────────────────────────────────────

class PcControlBrowseClient(settings: PcControlSettings) : PcBaseClient(settings) {

    suspend fun getDrives(): PcNetworkResult<List<PcDrive>> =
        parseList(get("/browse/drives"))

    suspend fun browseDir(
        path   : String,
        filter : PcFileFilter = PcFileFilter.ALL,
    ): PcNetworkResult<List<PcFileItem>> {
        val fp = if (filter.extensions.isEmpty()) ""
        else "&exts=${filter.extensions.joinToString(",")}"
        return parseList(get("/browse/dir?path=${enc(path)}$fp"))
    }

    suspend fun searchFiles(
        rootPath   : String,
        query      : String,
        maxResults : Int = 100,
    ): PcNetworkResult<List<PcFileItem>> {
        if (query.isBlank() || rootPath.isBlank())
            return PcNetworkResult(true, emptyList())
        return parseList(get("/browse/search?path=${enc(rootPath)}&q=${enc(query)}&maxResults=$maxResults"))
    }

    suspend fun getInstalledApps(): PcNetworkResult<List<PcInstalledApp>> =
        parseList(get("/browse/apps"))

    suspend fun getSpecialFolders(): PcNetworkResult<List<Map<String, Any>>> {
        val r = get("/browse/special")
        if (!r.success) return PcNetworkResult(false, error = r.error)
        return try {
            PcNetworkResult(true, gson.fromJson(r.data, object : TypeToken<List<Map<String, Any>>>() {}.type))
        } catch (e: Exception) { PcNetworkResult(false, error = e.message) }
    }

    suspend fun getRecentPaths(): PcNetworkResult<List<PcRecentPath>> =
        parseList(get("/browse/recent"))

    private inline fun <reified T> parseList(r: PcNetworkResult<String>): PcNetworkResult<List<T>> {
        if (!r.success) return PcNetworkResult(false, error = r.error)
        return try {
            val type = TypeToken.getParameterized(List::class.java, T::class.java).type
            PcNetworkResult(true, gson.fromJson(r.data, type))
        } catch (e: Exception) { PcNetworkResult(false, error = e.message) }
    }

    // ── Download — streams to OutputStream ──────────────────────────────────
    suspend fun downloadFile(
        remotePath : String,
        outputStream: OutputStream,
        onProgress : (Long, Long, Long) -> Unit,
    ): PcNetworkResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "${settings.baseUrl}/file/download?path=${enc(remotePath)}"
            val req = Request.Builder()
                .url(url)
                .header("X-Secret-Key", settings.secretKey)
                .header("Accept-Encoding", "identity")
                .get().build()

            val resp = httpTransfer.newCall(req).execute()
            if (!resp.isSuccessful)
                return@withContext PcNetworkResult(false, error = "HTTP ${resp.code}")

            val total  = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            val source = resp.body?.source()
                ?: return@withContext PcNetworkResult(false, error = "No body")

            var done         = 0L
            var lastReportMs = System.currentTimeMillis()
            var lastBytes    = 0L
            val readBuf = ByteArray(64 * 1024)

            outputStream.buffered(256 * 1024).use { out ->
                while (true) {
                    val n = source.read(readBuf)
                    if (n == -1) break
                    out.write(readBuf, 0, n)
                    done += n
                    val now  = System.currentTimeMillis()
                    val dtMs = now - lastReportMs
                    if (dtMs >= 200) {
                        val speed = if (dtMs > 0) ((done - lastBytes) * 1000L) / dtMs else 0L
                        onProgress(done, total, speed)
                        lastReportMs = now; lastBytes = done
                    }
                }
                out.flush()
            }
            source.close()
            onProgress(done, total, 0L)
            PcNetworkResult(true, Unit)
        } catch (e: Exception) {
            PcNetworkResult(false, error = e.message ?: "Download failed")
        }
    }

    private val CHUNKED_THRESHOLD = 16 * 1024 * 1024

    suspend fun uploadFile(
        inputStream  : InputStream,
        fileSize     : Long,
        fileName     : String,
        remotePath   : String,
        onProgress   : (Long, Long, Long) -> Unit,
    ): PcNetworkResult<String> = withContext(Dispatchers.IO) {
        if (fileSize <= CHUNKED_THRESHOLD) {
            uploadSingleStream(inputStream, fileSize, fileName, remotePath, onProgress)
        } else {
            uploadChunkedStream(inputStream, fileSize, fileName, remotePath, onProgress)
        }
    }

    private suspend fun uploadSingleStream(
        inputStream : InputStream,
        fileSize    : Long,
        fileName    : String,
        remotePath  : String,
        onProgress  : (Long, Long, Long) -> Unit,
    ): PcNetworkResult<String> = withContext(Dispatchers.IO) {
        try {
            val total        = fileSize
            var done         = 0L
            var lastReportMs = System.currentTimeMillis()
            var lastBytes    = 0L

            val filePart = object : RequestBody() {
                override fun contentType()   = BINARY_MT
                override fun contentLength() = total
                override fun writeTo(sink: BufferedSink) {
                    val buf = ByteArray(64 * 1024)
                    inputStream.use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            sink.write(buf, 0, n)
                            done += n
                            val now  = System.currentTimeMillis()
                            val dtMs = now - lastReportMs
                            if (dtMs >= 200) {
                                val speed = if (dtMs > 0) ((done - lastBytes) * 1000L) / dtMs else 0L
                                onProgress(done, total, speed)
                                lastReportMs = now; lastBytes = done
                            }
                        }
                    }
                    onProgress(total, total, 0L)
                }
            }

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("dest", remotePath)
                .addFormDataPart("file", fileName, filePart)
                .build()

            val req  = Request.Builder()
                .url("${settings.baseUrl}/file/upload?dest=${enc(remotePath)}")
                .header("X-Secret-Key", settings.secretKey)
                .post(multipart)
                .build()

            httpTransfer.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) PcNetworkResult(true, resp.body?.string())
                else PcNetworkResult(false, error = "HTTP ${resp.code}: ${resp.body?.string()}")
            }
        } catch (e: Exception) {
            PcNetworkResult(false, error = e.message ?: "Upload failed")
        }
    }

    private suspend fun uploadChunkedStream(
        inputStream : InputStream,
        fileSize    : Long,
        fileName    : String,
        remotePath  : String,
        onProgress  : (Long, Long, Long) -> Unit,
    ): PcNetworkResult<String> = withContext(Dispatchers.IO) {
        try {
            val total       = fileSize
            val totalChunks = ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
            var done        = 0L
            var lastReportMs = System.currentTimeMillis()
            var lastBytes    = 0L
            val chunkBuf = ByteArray(CHUNK_SIZE)

            inputStream.use { input ->
                var index = 0
                while (true) {
                    var bytesRead = 0
                    while (bytesRead < CHUNK_SIZE) {
                        val n = input.read(chunkBuf, bytesRead, CHUNK_SIZE - bytesRead)
                        if (n == -1) break
                        bytesRead += n
                    }
                    if (bytesRead == 0) break

                    val chunkSize = bytesRead
                    val capturedIndex = index

                    val body = object : RequestBody() {
                        override fun contentType()   = BINARY_MT
                        override fun contentLength() = chunkSize.toLong()
                        override fun writeTo(sink: BufferedSink) { sink.write(chunkBuf, 0, chunkSize) }
                    }

                    val url = "${settings.baseUrl}/file/upload/chunk" +
                        "?name=${enc(fileName)}&dest=${enc(remotePath)}" +
                        "&index=$capturedIndex&total=$totalChunks"
                    val req = Request.Builder()
                        .url(url)
                        .header("X-Secret-Key", settings.secretKey)
                        .post(body)
                        .build()

                    httpTransfer.newCall(req).execute().use { resp ->
                        resp.body?.string()
                        if (!resp.isSuccessful)
                            return@withContext PcNetworkResult<String>(
                                false, error = "Chunk $capturedIndex failed: HTTP ${resp.code}"
                            )
                    }

                    done += chunkSize
                    val now  = System.currentTimeMillis()
                    val dtMs = now - lastReportMs
                    if (dtMs >= 200 || index == totalChunks - 1) {
                        val speed = if (dtMs > 0) ((done - lastBytes) * 1000L) / dtMs else 0L
                        onProgress(done, total, speed)
                        lastReportMs = now; lastBytes = done
                    }
                    index++
                }
            }
            PcNetworkResult(true, "Chunked upload complete: $fileName")
        } catch (e: Exception) {
            PcNetworkResult(false, error = e.message ?: "Chunked upload failed")
        }
    }
}

// ── Input client ────────────────────────────────────────────────────────────

class PcControlInputClient(settings: PcControlSettings) : PcBaseClient(settings) {

    suspend fun moveMouse(dx: Float, dy: Float): PcNetworkResult<String> {
        LiveStreamGate.bump()
        return post("/input/mouse/move", mapOf("dx" to dx, "dy" to dy))
    }

    suspend fun clickMouse(button: String = "left", double: Boolean = false): PcNetworkResult<String> {
        LiveStreamGate.bump()
        return post("/input/mouse/click", mapOf("button" to button, "double" to double))
    }

    suspend fun scrollMouse(amount: Int, horizontal: Boolean = false): PcNetworkResult<String> {
        LiveStreamGate.bump()
        return post("/input/mouse/scroll", mapOf("amount" to amount, "horizontal" to horizontal))
    }

    suspend fun mouseButtonDown(button: String = "left"): PcNetworkResult<String> {
        LiveStreamGate.bump()
        return post("/input/mouse/down", mapOf("button" to button))
    }

    suspend fun mouseButtonUp(button: String = "left"): PcNetworkResult<String> {
        LiveStreamGate.bump()
        return post("/input/mouse/up", mapOf("button" to button))
    }

    suspend fun pressKey(key: String): PcNetworkResult<String> {
        LiveStreamGate.bump()
        return post("/input/keyboard/key", mapOf("value" to key))
    }

    suspend fun typeText(text: String): PcNetworkResult<String> {
        LiveStreamGate.bump()
        return post("/input/keyboard/type", mapOf("value" to text))
    }

    suspend fun holdKey(keyName: String): PcNetworkResult<String> {
        LiveStreamGate.bump()
        return post("/input/keyboard/hold", mapOf("value" to keyName))
    }

    suspend fun releaseKey(keyName: String): PcNetworkResult<String> {
        LiveStreamGate.bump()
        return post("/input/keyboard/release", mapOf("value" to keyName))
    }

    suspend fun minimizeApp(name: String) = post("/app/minimize", mapOf("name" to name))
    suspend fun restoreApp(name: String) = post("/app/restore", mapOf("name" to name))

    suspend fun fetchScreenSnapshot(): PcNetworkResult<String> = withContext(Dispatchers.IO) {
        try {
            http.newCall(baseRequest("/screen/snapshot").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use PcNetworkResult<String>(false, error = "HTTP ${resp.code}")
                val body = resp.body?.string() ?: return@use PcNetworkResult<String>(false, error = "Empty")
                val json = org.json.JSONObject(body)
                if (!json.optBoolean("ok", false)) PcNetworkResult(false, error = "Agent error")
                else PcNetworkResult(true, json.optString("data"))
            }
        } catch (e: Exception) { PcNetworkResult(false, error = e.message) }
    }

    suspend fun fetchScreenInfo(): PcNetworkResult<org.json.JSONObject> = withContext(Dispatchers.IO) {
        try {
            http.newCall(baseRequest("/screen/info").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use PcNetworkResult<org.json.JSONObject>(false, error = "HTTP ${resp.code}")
                val body = resp.body?.string() ?: return@use PcNetworkResult<org.json.JSONObject>(false, error = "Empty")
                PcNetworkResult(true, org.json.JSONObject(body))
            }
        } catch (e: Exception) { PcNetworkResult(false, error = e.message) }
    }

    suspend fun getVolume(): PcNetworkResult<String> = get("/system/volume")
    suspend fun setVolume(level: Int): PcNetworkResult<String> = post("/system/volume/set", mapOf("level" to level))
    suspend fun getBrightness(): PcNetworkResult<String> = get("/system/brightness")
    suspend fun setBrightness(level: Int): PcNetworkResult<String> = post("/system/brightness/set", mapOf("level" to level))
}

// ── Realtime-priority gate ──────────────────────────────────────────────────

/**
 * Realtime-priority gate. Every input path calls [bump]; the MJPEG reader
 * polls [isInputActive] and drops frames while the gate is hot, freeing the
 * radio / socket for outbound input POSTs. Ported from Android; on JVM we
 * use `System.nanoTime()` for the monotonic clock instead of
 * `SystemClock.elapsedRealtime()`.
 */
object LiveStreamGate {
    const val QUIET_MS: Long = 200L
    @Volatile private var lastInputAtMs: Long = 0L

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    fun bump() { lastInputAtMs = nowMs() }
    fun isInputActive(): Boolean = nowMs() - lastInputAtMs < QUIET_MS
}

// ── Conditional cert pinning ────────────────────────────────────────────────

/**
 * When a saved device carries a SHA-256 cert fingerprint, every OkHttpClient
 * in [PcBaseClient] pins the agent host to that hash. Self-signed agents
 * without SAN → hostname verifier is permissive (the pin is the trust
 * anchor). Silent no-op when fingerprint is null/blank or URL is http://.
 *
 * Accepts 64-char hex (colons optional) or exact OkHttp `sha256/<base64>`.
 */
internal fun OkHttpClient.Builder.applyPinning(settings: PcControlSettings): OkHttpClient.Builder {
    val fp   = settings.certFingerprint?.trim()?.takeIf { it.isNotBlank() } ?: return this
    val host = settings.pcIpAddress.takeIf { it.isNotBlank() } ?: return this
    val pin  = toOkHttpPin(fp) ?: return this

    val pinner = CertificatePinner.Builder().add(host, pin).build()
    certificatePinner(pinner)
    hostnameVerifier { _, _ -> true }
    return this
}

private fun toOkHttpPin(raw: String): String? {
    val cleaned = raw.trim()
    if (cleaned.startsWith("sha256/")) return cleaned
    val hex = cleaned.replace(":", "").replace(" ", "").lowercase()
    if (hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
        val bytes = ByteArray(32) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) +
                Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return "sha256/" + Base64.getEncoder().encodeToString(bytes)
    }
    return null
}

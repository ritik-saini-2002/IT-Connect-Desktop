package com.itconnect.desktop.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress

/**
 * OkHttp interceptor that blocks cleartext HTTP to public (non-RFC 1918) IPs.
 * LAN communication (PC Control, PocketBase) remains allowed over HTTP.
 *
 * Ported verbatim from the Android core module.
 */
class PrivateNetworkInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.scheme == "http") {
            val host = url.host
            if (!isPrivateOrLocal(host)) {
                throw IOException(
                    "Cleartext HTTP blocked to public host: $host. Use HTTPS or a private LAN address."
                )
            }
        }
        return chain.proceed(request)
    }

    private fun isPrivateOrLocal(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") return true
        return try {
            val addr = InetAddress.getByName(host)
            addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
        } catch (_: Exception) {
            false
        }
    }
}

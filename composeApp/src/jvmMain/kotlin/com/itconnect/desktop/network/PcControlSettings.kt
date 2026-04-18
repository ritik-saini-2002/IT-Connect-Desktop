package com.itconnect.desktop.network

/**
 * Connection settings for a single target PC / agent.
 *
 * Ported from Android `com.example.ritik_2.windowscontrol.PcControlSettings`
 * verbatim — the desktop build shares identical semantics so the same saved
 * device rows (v8 schema) resolve to the same agent without adaptation.
 *
 * - [certFingerprint] non-null/blank ⇒ scheme flips to HTTPS + the OkHttp
 *   client pins the chain (see `applyPinning`).
 */
data class PcControlSettings(
    val pcIpAddress    : String  = "",
    val port           : Int     = 5000,
    val secretKey      : String  = "",
    val certFingerprint: String? = null,
    val streamPort     : Int     = 5001,
) {
    val baseUrl get() =
        "${if (certFingerprint.isNullOrBlank()) "http" else "https"}://$pcIpAddress:$port"
    val isConfigured get() = pcIpAddress.isNotEmpty() && secretKey.isNotEmpty()
}

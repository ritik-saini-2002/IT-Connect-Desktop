package com.itconnect.desktop.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * UDP discovery client. Broadcasts "PCAGENT_DISCOVER_V1" to the LAN and
 * emits each agent reply as a [DiscoveredAgent] over a Flow.
 *
 * Desktop port: the Android variant used WifiManager to derive DHCP
 * broadcast addresses. On JVM we enumerate every active IPv4 NIC's
 * broadcast address via [NetworkInterface] (matches the agent's fallback
 * path on the Android side). Multicast-lock logic removed — Windows
 * does not gate UDP broadcast receive.
 */
class PcLanScanner {

    data class DiscoveredAgent(
        val pcName    : String,
        val host      : String,
        val ip        : String,
        val port      : Int,
        val streamPort: Int,
        val os        : String,
        val version   : String,
        val connected : Int,
    )

    companion object {
        const val DISCOVERY_PORT = 5002
        const val PROBE_PAYLOAD  = "PCAGENT_DISCOVER_V1"
    }

    fun scan(durationMs: Long = 4_000L): Flow<DiscoveredAgent> = channelFlow {
        val seen = mutableSetOf<String>()

        val socket = DatagramSocket().apply {
            broadcast = true
            soTimeout = 500
        }

        val receiver = launch(Dispatchers.IO) {
            val buf = ByteArray(2048)
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val obj  = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    if (obj.optString("proto") != "PCAGENT") continue
                    val ip   = obj.optString("ip", packet.address?.hostAddress.orEmpty())
                    val port = obj.optInt("port", 5000)
                    val key  = "$ip:$port"
                    if (key in seen) continue
                    seen += key
                    trySend(
                        DiscoveredAgent(
                            pcName     = obj.optString("pc_name", obj.optString("host", ip)),
                            host       = obj.optString("host", ip),
                            ip         = ip,
                            port       = port,
                            streamPort = obj.optInt("stream_port", 5001),
                            os         = obj.optString("os", ""),
                            version    = obj.optString("version", ""),
                            connected  = obj.optInt("connected", 0),
                        )
                    )
                } catch (_: java.net.SocketTimeoutException) { /* loop */ }
                catch (_: Exception) { /* swallow malformed packets */ }
            }
        }

        val payload = PROBE_PAYLOAD.toByteArray(Charsets.UTF_8)
        val targets = broadcastAddresses()
        withContext(Dispatchers.IO) {
            repeat(3) {
                for (addr in targets) {
                    runCatching {
                        socket.send(
                            DatagramPacket(payload, payload.size, addr, DISCOVERY_PORT)
                        )
                    }
                }
                delay(400)
            }
        }

        delay(durationMs)
        receiver.cancel()
        runCatching { socket.close() }
    }

    /** Enumerate broadcast addresses from every active IPv4 interface. */
    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .filter { it.address is Inet4Address && it.broadcast != null }
                .forEach { out += it.broadcast }
        }
        return out.distinct()
    }
}

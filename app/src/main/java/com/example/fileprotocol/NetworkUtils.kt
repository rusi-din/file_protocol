package com.example.fileprotocol

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections


object NetworkUtils {
    fun getPrivateIpv4Addresses(): List<String> {
        return Collections.list(NetworkInterface.getNetworkInterfaces())
            .orEmpty()
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotBlank() && isPrivateIpv4(it) }
            .distinct()
            .sorted()
            .toList()
    }

    fun getReachableBaseUrls(port: Int, localHostname: String? = null): List<String> {
        val urls = mutableListOf<String>()
        // localHostname already contains the full ".local" suffix when returned by LocalDnsAdvertiser
        if (!localHostname.isNullOrBlank()) {
            urls += "http://$localHostname:$port"
        }

        urls += getPrivateIpv4Addresses().map { "http://$it:$port" }
        return urls.distinct()
    }

    /**
     * Runs a lightweight HTTP GET to the given base URL and returns true when the
     * server responds with a 2xx status within [timeoutMs] milliseconds.
     */
    fun isServerReachable(baseUrl: String, timeoutMs: Int = 2_000): Boolean {
        return runCatching {
            val url = java.net.URL("$baseUrl/")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        }.getOrDefault(false)
    }

    /**
     * Returns a human-readable diagnostics report as a multi-line string.
     * Runs network I/O so must be called off the main thread.
     */
    fun runDiagnostics(
        port: Int?,
        localHostname: String?,
        sharedFileCount: Int,
        serverRunning: Boolean,
    ): String {
        val sb = StringBuilder()

        // 1. Network addresses
        val addresses = getPrivateIpv4Addresses()
        sb.appendLine("Network addresses: ${if (addresses.isEmpty()) "none found" else addresses.joinToString()}")

        // 2. Hotspot detection heuristic (192.168.43.x / 192.168.x.x on wlan)
        val hotspotAddress = addresses.firstOrNull { it.startsWith("192.168.43.") }
        sb.appendLine("Hotspot interface: ${hotspotAddress ?: "not detected (may be AP-only mode)"}")

        // 3. mDNS hostname
        sb.appendLine("mDNS hostname: ${localHostname ?: "not advertised"}")

        // 4. Shared files
        sb.appendLine("Shared files in HotspotDrop: $sharedFileCount")

        // 5. Server reachability
        if (!serverRunning || port == null) {
            sb.appendLine("Server reachability: server not running")
        } else {
            val ipUrls = addresses.map { "http://$it:$port" }
            val allUrls = buildList {
                if (!localHostname.isNullOrBlank()) add("http://$localHostname:$port")
                addAll(ipUrls)
            }
            allUrls.forEach { url ->
                val reachable = isServerReachable(url)
                sb.appendLine("  $url → ${if (reachable) "OK" else "unreachable"}")
            }
        }

        return sb.toString().trimEnd()
    }

    private fun isPrivateIpv4(address: String): Boolean {
        return address.startsWith("192.168.") ||
            address.startsWith("10.") ||
            address.matches(Regex("""172\.(1[6-9]|2\d|3[0-1])\..+"""))
    }
}

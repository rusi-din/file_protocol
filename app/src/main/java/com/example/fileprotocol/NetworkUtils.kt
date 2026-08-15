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
     * Returns the phone's IP address on the hotspot subnet, which is also the
     * gateway IP that DHCP hands to connecting clients as their DNS server.
     *
     * Android hotspot subnets and their typical gateway IPs:
     *   192.168.43.0/24  → 192.168.43.1   (AOSP default)
     *   192.168.49.0/24  → 192.168.49.1   (Wi-Fi Direct / some OEMs)
     *   10.0.0.0/24      → 10.0.0.1       (some OEMs)
     *
     * We match any address ending in .1 on one of these subnets, which is the
     * address the DNS server should bind to and return in A-record answers.
     */
    fun getHotspotGatewayIp(): String? {
        return getPrivateIpv4Addresses().firstOrNull { ip ->
            (ip.startsWith("192.168.43.") ||
                ip.startsWith("192.168.49.") ||
                ip.startsWith("10.0.0.")) &&
                ip.endsWith(".1")
        }
            // If the device uses a non-standard subnet or the last octet isn't
            // .1, fall back to any hotspot-range address.
            ?: getPrivateIpv4Addresses().firstOrNull { ip ->
                ip.startsWith("192.168.43.") ||
                    ip.startsWith("192.168.49.") ||
                    ip.startsWith("10.0.0.")
            }
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
        mdnsError: String?,
        dnsName: String?,
        dnsError: String?,
        sharedFileCount: Int,
        serverRunning: Boolean,
    ): String {
        val sb = StringBuilder()

        // 1. Network addresses
        val addresses = getPrivateIpv4Addresses()
        sb.appendLine("Network addresses: ${if (addresses.isEmpty()) "none found" else addresses.joinToString()}")

        // 2. Hotspot interface detection
        val hotspotAddress = getHotspotGatewayIp()
        sb.appendLine("Hotspot gateway IP: ${hotspotAddress ?: "not detected (non-standard subnet or AP-only mode)"}")

        // 3. Hotspot DNS server (friendly name)
        when {
            dnsName != null  -> sb.appendLine("Hotspot DNS: active — clients resolve \"$dnsName\" to ${addresses.firstOrNull() ?: "?"}")
            dnsError != null -> sb.appendLine("Hotspot DNS: failed — $dnsError (port 53 may be taken by system dnsmasq)")
            else             -> sb.appendLine("Hotspot DNS: not started")
        }

        // 4. mDNS status
        when {
            localHostname != null -> sb.appendLine("mDNS: advertised as $localHostname")
            mdnsError != null     -> sb.appendLine("mDNS: failed — $mdnsError")
            else                  -> sb.appendLine("mDNS: not started")
        }
        sb.appendLine("mDNS note: Android hotspot blocks multicast on most devices — use the friendly name or IP URL instead.")

        // 5. Shared files
        sb.appendLine("Shared files in HotspotDrop: $sharedFileCount")

        // 6. Server reachability
        if (!serverRunning || port == null) {
            sb.appendLine("Server reachability: server not running")
        } else {
            val allUrls = buildList {
                if (!dnsName.isNullOrBlank())      add("http://$dnsName:$port")
                if (!localHostname.isNullOrBlank()) add("http://$localHostname:$port")
                addAll(addresses.map { "http://$it:$port" })
            }
            allUrls.forEach { url ->
                val reachable = isServerReachable(url)
                sb.appendLine("  $url → ${if (reachable) "OK ✓" else "unreachable ✗"}")
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

package com.example.fileprotocol

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections


object NetworkUtils {
    fun getReachableBaseUrls(port: Int): List<String> {
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
            .map { "http://$it:$port" }
            .toList()
    }

    private fun isPrivateIpv4(address: String): Boolean {
        return address.startsWith("192.168.") ||
            address.startsWith("10.") ||
            address.matches(Regex("""172\.(1[6-9]|2\d|3[0-1])\..+"""))
    }
}

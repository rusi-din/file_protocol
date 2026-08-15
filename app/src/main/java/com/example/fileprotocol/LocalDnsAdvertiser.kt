package com.example.fileprotocol

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Advertises the file server over mDNS (Bonjour/Zeroconf) so that clients on
 * the same network can discover it via `hotspotdrop.local` without knowing the
 * IP address.
 *
 * ⚠️  Android hotspot limitation: most Android devices do NOT bridge multicast
 * packets between the AP interface and connected clients at the kernel/driver
 * level. As a result, mDNS announcements sent from the phone may not be
 * visible to hotspot clients even though the registration succeeds here.
 * The IP-based URL is always the reliable fallback.
 *
 * When mDNS does work (Wi-Fi infrastructure mode, some hotspot drivers), the
 * `.local` hostname is a convenient alias.
 */
class LocalDnsAdvertiser(private val context: Context) {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null

    /** Bare hostname without the `.local` TLD. */
    val localHostname = "hotspotdrop"

    /**
     * Starts mDNS advertisement.
     *
     * @return The advertised hostname (`hotspotdrop.local`) on success, or
     *         `null` if registration failed. Check [lastError] for details.
     */
    var lastError: String? = null
        private set

    fun start(ipAddress: String, port: Int): String? {
        lastError = null
        return runCatching {
            acquireMulticastLock()

            // Bind JmDNS to the specific interface IP so it uses the correct
            // network interface (hotspot or Wi-Fi) rather than defaulting to
            // whatever Java picks as the "primary" interface.
            val inetAddr = java.net.InetAddress.getByName(ipAddress)
            val localJmDns = JmDNS.create(inetAddr, localHostname)

            val localServiceInfo = ServiceInfo.create(
                "_http._tcp.local.",
                "Hotspot Drop",
                port,
                "path=/",
            )

            localJmDns.registerService(localServiceInfo)
            jmdns = localJmDns
            serviceInfo = localServiceInfo

            Log.d(TAG, "mDNS registered: $localHostname.local on $ipAddress:$port")
            "$localHostname.local"
        }.getOrElse { e ->
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "mDNS registration failed: $lastError", e)
            stop()
            null
        }
    }

    fun stop() {
        runCatching { serviceInfo?.let { jmdns?.unregisterService(it) } }
        runCatching { jmdns?.close() }
        runCatching { multicastLock?.release() }
        serviceInfo = null
        jmdns = null
        multicastLock = null
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return

        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("hotspot-drop-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    companion object {
        private const val TAG = "LocalDnsAdvertiser"
    }
}

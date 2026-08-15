package com.example.fileprotocol

import android.content.Context
import android.net.wifi.WifiManager
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

class LocalDnsAdvertiser(private val context: Context) {
    private var multicastLock: WifiManager.MulticastLock? = null
    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null

    val localHostname = "hotspotdrop"

    fun start(ipAddress: String, port: Int): String? {
        return runCatching {
            acquireMulticastLock()

            val localJmDns = JmDNS.create(java.net.InetAddress.getByName(ipAddress), localHostname)
            val localServiceInfo = ServiceInfo.create(
                "_http._tcp.local.",
                "Hotspot Drop",
                port,
                "path=/",
            )

            localJmDns.registerService(localServiceInfo)
            jmdns = localJmDns
            serviceInfo = localServiceInfo

            "$localHostname.local"
        }.getOrElse {
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
        if (multicastLock?.isHeld == true) {
            return
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("hotspot-drop-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }
}

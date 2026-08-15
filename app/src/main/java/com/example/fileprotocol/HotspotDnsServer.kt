package com.example.fileprotocol

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Minimal UDP DNS server that intercepts A-record queries for [hostname] and
 * returns [resolvedIp], letting every other query fall through as NXDOMAIN so
 * the client's normal DNS resolver handles real internet names.
 *
 * Why this CAN work on Android hotspot
 * --------------------------------------
 * Android's dnsmasq assigns the phone's own IP as the DNS server in DHCP
 * leases it hands to hotspot clients.  If we can bind port 53 before dnsmasq
 * does (or if dnsmasq is not running), we intercept those queries and the
 * client can type  http://drop:PORT  instead of the raw IP.
 *
 * Why it sometimes CANNOT work
 * -----------------------------
 * On many Android versions dnsmasq is started by the system as root when
 * hotspot is enabled and it already holds port 53.  A non-root app will get
 * EADDRINUSE or EACCES.  In that case [lastError] is set, [start] returns
 * false, and the app falls back to showing the raw IP URL.
 *
 * We bind to 0.0.0.0 (wildcard) rather than a specific interface IP so that:
 *   • we don't accidentally miss the right interface
 *   • the socket works even if the hotspot IP changes between start() calls
 */
class HotspotDnsServer(
    private val hostname: String,    // e.g. "drop" — bare label, no dots
    private val resolvedIp: String,  // phone's IP to return in A records
) {
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    var lastError: String? = null
        private set

    /** Starts the DNS server on port 53. Returns true on success. */
    fun start(): Boolean {
        lastError = null
        return try {
            // Bind wildcard so we receive queries on every interface
            val s = DatagramSocket(DNS_PORT, InetAddress.getByName("0.0.0.0"))
            s.reuseAddress = true
            socket = s
            running = true
            thread = Thread({ serve(s) }, "hotspot-dns").apply {
                isDaemon = true
                start()
            }
            Log.d(TAG, "DNS server listening on *:$DNS_PORT, resolving \"$hostname\" → $resolvedIp")
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "DNS server failed to bind port $DNS_PORT: $lastError", e)
            false
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    val isRunning: Boolean get() = running && socket != null

    // -------------------------------------------------------------------------
    // Serve loop
    // -------------------------------------------------------------------------

    private fun serve(s: DatagramSocket) {
        val buf = ByteArray(512)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                val query = packet.data.copyOf(packet.length)
                val response = buildResponse(query)
                if (response != null) {
                    s.send(DatagramPacket(response, response.size, packet.address, packet.port))
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "DNS serve error: ${e.message}")
                break
            }
        }
        Log.d(TAG, "DNS server stopped")
    }

    // -------------------------------------------------------------------------
    // DNS packet construction  (RFC 1035)
    // -------------------------------------------------------------------------

    private fun buildResponse(query: ByteArray): ByteArray? {
        if (query.size < 12) return null

        // Transaction ID — use unsigned read to avoid sign-extension on bytes > 0x7F
        val txId = (query[0].toInt() and 0xFF) shl 8 or (query[1].toInt() and 0xFF)

        val qdCount = (query[4].toInt() and 0xFF) shl 8 or (query[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        val (qname, afterName) = parseName(query, 12) ?: return null
        if (afterName + 4 > query.size) return null

        val qtype  = (query[afterName    ].toInt() and 0xFF) shl 8 or (query[afterName + 1].toInt() and 0xFF)
        val qclass = (query[afterName + 2].toInt() and 0xFF) shl 8 or (query[afterName + 3].toInt() and 0xFF)

        Log.v(TAG, "DNS query: \"$qname\" type=$qtype")

        // Match bare label OR fully-qualified (e.g. "drop" or "drop.")
        val isOurName = qname.equals(hostname, ignoreCase = true) ||
            qname.equals("$hostname.", ignoreCase = true)

        return if (isOurName && qtype == QTYPE_A) {
            Log.d(TAG, "DNS: resolving \"$qname\" → $resolvedIp")
            buildAnswerResponse(query, txId, qclass)
        } else {
            buildNxdomainResponse(query, txId)
        }
    }

    private fun buildAnswerResponse(question: ByteArray, txId: Int, qclass: Int): ByteArray {
        val ipBytes = resolvedIp.split(".").map { it.toInt().and(0xFF).toByte() }.toByteArray()
        val out = ByteArrayBuilder()

        out.writeHeader(txId, flags = 0x8180, qdCount = 1, anCount = 1)
        // Echo question section
        out.write(question, fromOffset = 12)

        // Answer RR — name pointer back to offset 12 (0xC0 0x0C)
        out.writeByte(0xC0); out.writeByte(0x0C)
        // TYPE A
        out.writeByte(0x00); out.writeByte(QTYPE_A)
        // CLASS (echo client's class, typically IN=1)
        out.writeByte((qclass shr 8) and 0xFF); out.writeByte(qclass and 0xFF)
        // TTL = 30 s (short so changes propagate quickly)
        out.writeByte(0x00); out.writeByte(0x00); out.writeByte(0x00); out.writeByte(0x1E)
        // RDLENGTH = 4
        out.writeByte(0x00); out.writeByte(0x04)
        // RDATA = IP
        ipBytes.forEach { out.writeByte(it.toInt() and 0xFF) }

        return out.toByteArray()
    }

    private fun buildNxdomainResponse(question: ByteArray, txId: Int): ByteArray {
        val out = ByteArrayBuilder()
        // Flags: QR=1 RD=1 RA=1 RCODE=3 (NXDOMAIN)
        out.writeHeader(txId, flags = 0x8183, qdCount = 1, anCount = 0)
        out.write(question, fromOffset = 12)
        return out.toByteArray()
    }

    // -------------------------------------------------------------------------
    // DNS name parser
    // -------------------------------------------------------------------------

    /**
     * Parses a DNS QNAME starting at [startOffset].
     * Returns the first label (e.g. "drop" from "drop.lan.") and the offset
     * immediately after the terminating zero byte.
     */
    private fun parseName(data: ByteArray, startOffset: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var i = startOffset
        while (i < data.size) {
            val len = data[i].toInt() and 0xFF
            when {
                len == 0 -> { i++; break }
                len and 0xC0 == 0xC0 -> { i += 2; break } // compression pointer
                else -> {
                    i++
                    if (i + len > data.size) return null
                    labels += String(data, i, len, Charsets.US_ASCII)
                    i += len
                }
            }
        }
        return Pair(labels.firstOrNull().orEmpty(), i)
    }

    // -------------------------------------------------------------------------
    // Tiny byte-array builder to avoid mutable List<Byte> boxing
    // -------------------------------------------------------------------------

    private class ByteArrayBuilder {
        private val buf = java.io.ByteArrayOutputStream(64)

        fun writeByte(v: Int) { buf.write(v and 0xFF) }

        fun writeHeader(txId: Int, flags: Int, qdCount: Int, anCount: Int) {
            writeByte(txId shr 8);    writeByte(txId)
            writeByte(flags shr 8);   writeByte(flags)
            writeByte(qdCount shr 8); writeByte(qdCount)
            writeByte(anCount shr 8); writeByte(anCount)
            writeByte(0); writeByte(0) // NSCOUNT
            writeByte(0); writeByte(0) // ARCOUNT
        }

        fun write(src: ByteArray, fromOffset: Int = 0) {
            buf.write(src, fromOffset, src.size - fromOffset)
        }

        fun toByteArray(): ByteArray = buf.toByteArray()
    }

    companion object {
        const val DNS_PORT = 53
        const val DEFAULT_HOSTNAME = "drop"
        private const val QTYPE_A = 1
        private const val TAG = "HotspotDnsServer"
    }
}

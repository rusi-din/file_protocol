package com.example.fileprotocol

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Minimal UDP DNS server that runs on port 53 and responds to A-record queries
 * for [hostname] (case-insensitive, without trailing dot) with [resolvedIp].
 *
 * Every other query gets an NXDOMAIN response so the client falls through to
 * its normal DNS resolver for real internet addresses.
 *
 * Why this works on Android hotspot
 * ----------------------------------
 * Android's built-in DHCP server (dnsmasq) tells connecting clients to use the
 * phone's own IP (e.g. 192.168.43.1) as their DNS server.  By listening on
 * port 53 of that same IP we intercept those DNS queries before they leave the
 * device.  No multicast, no mDNS — just plain unicast UDP that works on every
 * Android hotspot.
 *
 * The client can then type  http://drop:8080  (or whatever [hostname] is set
 * to) instead of the raw IP address.
 *
 * Limitation: port 53 requires INTERNET permission (already granted) but on
 * some devices a system dnsmasq process may already hold port 53.  If binding
 * fails [lastError] will be set and the server won't start; the IP URL remains
 * the reliable fallback.
 */
class HotspotDnsServer(
    private val hostname: String,   // e.g. "drop"  — no dots, no trailing dot
    private val resolvedIp: String, // phone's hotspot IP, e.g. "192.168.43.1"
) {
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    var lastError: String? = null
        private set

    /** Starts the DNS server. Returns true on success, false if the port is unavailable. */
    fun start(): Boolean {
        lastError = null
        return try {
            val s = DatagramSocket(DNS_PORT, InetAddress.getByName(resolvedIp))
            socket = s
            running = true
            thread = Thread({ serve(s) }, "hotspot-dns").apply {
                isDaemon = true
                start()
            }
            Log.d(TAG, "DNS server listening on $resolvedIp:$DNS_PORT for \"$hostname\"")
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "DNS server failed to start: $lastError", e)
            false
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    // -------------------------------------------------------------------------
    // Core loop
    // -------------------------------------------------------------------------

    private fun serve(s: DatagramSocket) {
        val buf = ByteArray(512)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                val query = packet.data.copyOf(packet.length)
                val response = buildResponse(query) ?: continue
                val reply = DatagramPacket(response, response.size, packet.address, packet.port)
                s.send(reply)
            } catch (_: Exception) {
                // socket closed on stop() — exit loop
                break
            }
        }
    }

    // -------------------------------------------------------------------------
    // DNS packet builder
    //
    // DNS wire format (RFC 1035):
    //   Header  : 12 bytes
    //   Question: variable
    //   Answer  : variable (only in responses)
    // -------------------------------------------------------------------------

    private fun buildResponse(query: ByteArray): ByteArray? {
        if (query.size < 12) return null

        val txId = query[0].toInt() shl 8 or (query[1].toInt() and 0xFF)
        val questionCount = query[4].toInt() shl 8 or (query[5].toInt() and 0xFF)
        if (questionCount < 1) return null

        // Parse the first question's QNAME starting at offset 12
        val (qname, nextOffset) = parseName(query, 12) ?: return null
        if (nextOffset + 4 > query.size) return null

        val qtype  = query[nextOffset].toInt() shl 8 or (query[nextOffset + 1].toInt() and 0xFF)
        val qclass = query[nextOffset + 2].toInt() shl 8 or (query[nextOffset + 3].toInt() and 0xFF)

        // Only handle A queries (type 1) for our hostname
        val isOurName = qname.equals(hostname, ignoreCase = true) ||
            qname.equals("$hostname.", ignoreCase = true)
        val isAQuery = qtype == 1 // A record

        return if (isOurName && isAQuery) {
            buildAnswerResponse(query, txId, qclass)
        } else {
            buildNxdomainResponse(query, txId)
        }
    }

    /** Builds a positive A-record response pointing to [resolvedIp]. */
    private fun buildAnswerResponse(question: ByteArray, txId: Int, qclass: Int): ByteArray {
        val ip = resolvedIp.split(".").map { it.toInt().toByte() }.toByteArray()
        val out = mutableListOf<Byte>()

        // Header
        out += headerBytes(txId, flags = 0x8180, qdCount = 1, anCount = 1)

        // Echo the question section (everything from offset 12 onward)
        question.drop(12).forEach { out += it }

        // Answer RR: pointer to question name (0xC00C = pointer to offset 12)
        out += 0xC0.toByte(); out += 0x0C.toByte()
        // TYPE A (1), CLASS IN (1)
        out += 0x00.toByte(); out += 0x01.toByte()
        out += 0x00.toByte(); out += (qclass and 0xFF).toByte()
        // TTL: 60 seconds
        out += 0x00.toByte(); out += 0x00.toByte(); out += 0x00.toByte(); out += 0x3C.toByte()
        // RDLENGTH: 4
        out += 0x00.toByte(); out += 0x04.toByte()
        // RDATA: IP address
        ip.forEach { out += it }

        return out.toByteArray()
    }

    /** Builds an NXDOMAIN (name not found) response so normal DNS resolution continues. */
    private fun buildNxdomainResponse(question: ByteArray, txId: Int): ByteArray {
        val out = mutableListOf<Byte>()
        // Flags: QR=1 AA=0 TC=0 RD=1 RA=1 RCODE=3 (NXDOMAIN)
        out += headerBytes(txId, flags = 0x8183, qdCount = 1, anCount = 0)
        question.drop(12).forEach { out += it }
        return out.toByteArray()
    }

    private fun headerBytes(txId: Int, flags: Int, qdCount: Int, anCount: Int): List<Byte> =
        listOf(
            (txId shr 8).toByte(),   (txId and 0xFF).toByte(),
            (flags shr 8).toByte(),  (flags and 0xFF).toByte(),
            (qdCount shr 8).toByte(),(qdCount and 0xFF).toByte(),
            (anCount shr 8).toByte(),(anCount and 0xFF).toByte(),
            0x00.toByte(), 0x00.toByte(), // NSCOUNT
            0x00.toByte(), 0x00.toByte(), // ARCOUNT
        )

    // -------------------------------------------------------------------------
    // DNS name parser — returns (dotted-label-string, offset-after-name)
    // -------------------------------------------------------------------------

    private fun parseName(data: ByteArray, startOffset: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var i = startOffset
        while (i < data.size) {
            val len = data[i].toInt() and 0xFF
            if (len == 0) { i++; break }
            // Compression pointer (top 2 bits set) — not expected in queries but handle gracefully
            if (len and 0xC0 == 0xC0) { i += 2; break }
            i++
            if (i + len > data.size) return null
            labels += String(data, i, len, Charsets.US_ASCII)
            i += len
        }
        // Return the first label only (e.g. "drop" from "drop.local") so partial
        // matches like "drop.lan" or bare "drop" all resolve correctly.
        return Pair(labels.firstOrNull() ?: "", i)
    }

    private operator fun MutableList<Byte>.plusAssign(b: Byte) { add(b) }
    private operator fun MutableList<Byte>.plusAssign(b: Int)  { add(b.toByte()) }

    companion object {
        const val DNS_PORT = 53
        const val DEFAULT_HOSTNAME = "drop"
        private const val TAG = "HotspotDnsServer"
    }
}

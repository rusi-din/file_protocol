package com.example.fileprotocol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var server: HotspotFileServer? = null
    private var dnsAdvertiser: LocalDnsAdvertiser? = null
    private var hotspotDns: HotspotDnsServer? = null
    private var currentUrls: List<String> = emptyList()
    private var currentHostname: String? = null   // mDNS .local name, if registered
    private var currentPort: Int? = null
    private var currentIp: String? = null
    private var dnsHostname: String? = null        // friendly DNS name, e.g. "drop"

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var folderText: TextView
    private lateinit var lastUploadText: TextView
    private lateinit var portInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var copyButton: Button
    private lateinit var diagButton: Button
    private lateinit var diagResultText: TextView

    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText     = findViewById(R.id.statusText)
        urlText        = findViewById(R.id.urlText)
        folderText     = findViewById(R.id.folderText)
        lastUploadText = findViewById(R.id.lastUploadText)
        portInput      = findViewById(R.id.portInput)
        startButton    = findViewById(R.id.startButton)
        stopButton     = findViewById(R.id.stopButton)
        copyButton     = findViewById(R.id.copyButton)
        diagButton     = findViewById(R.id.diagButton)
        diagResultText = findViewById(R.id.diagResultText)

        folderText.text = getString(R.string.saved_folder_value)

        startButton.setOnClickListener { startServer() }
        stopButton.setOnClickListener { stopServer() }
        copyButton.setOnClickListener { copyFirstUrl() }
        diagButton.setOnClickListener { runDiagnostics() }
    }

    override fun onDestroy() {
        stopServer()
        ioExecutor.shutdown()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Server lifecycle
    // -------------------------------------------------------------------------

    private fun startServer() {
        val port = portInput.text.toString().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            Toast.makeText(this, "Enter a port between 1024 and 65535.", Toast.LENGTH_SHORT).show()
            return
        }

        stopServer()

        val localServer = HotspotFileServer(applicationContext, port) { fileName ->
            runOnUiThread {
                lastUploadText.text = getString(R.string.last_upload_prefix, fileName)
            }
        }

        try {
            localServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = localServer
            currentPort = port

            // Prefer the hotspot gateway IP (e.g. 192.168.43.1) because that is
            // the IP clients use as DNS server.  Fall back to any private IP.
            val hotspotIp = NetworkUtils.getHotspotGatewayIp()
            val primaryIp = hotspotIp ?: NetworkUtils.getPrivateIpv4Addresses().firstOrNull()
            currentIp = primaryIp

            // --- mDNS (best-effort, unreliable on Android hotspot) ---
            val advertiser = if (primaryIp != null) {
                LocalDnsAdvertiser(applicationContext).also { dnsAdvertiser = it }
            } else null
            val mdnsName = advertiser?.start(primaryIp!!, port)
            currentHostname = mdnsName

            // --- Hotspot DNS server on port 53 ---
            // Binds 0.0.0.0 so it works regardless of which interface is active.
            // resolvedIp is what we return in A-record answers — must be the IP
            // the client can actually reach, i.e. the hotspot gateway.
            val dnsStarted = if (primaryIp != null) {
                val dns = HotspotDnsServer(
                    hostname   = HotspotDnsServer.DEFAULT_HOSTNAME,
                    resolvedIp = primaryIp,
                )
                hotspotDns = dns
                dns.start()
            } else false

            dnsHostname = if (dnsStarted) HotspotDnsServer.DEFAULT_HOSTNAME else null

            // Build URL list — friendly name first so copyFirstUrl() copies it
            val friendlyUrl = if (dnsStarted && primaryIp != null) {
                "http://${HotspotDnsServer.DEFAULT_HOSTNAME}:$port"
            } else null
            currentUrls = buildList {
                if (friendlyUrl != null) add(friendlyUrl)
                if (primaryIp != null)   add("http://$primaryIp:$port")
                if (mdnsName != null)    add("http://$mdnsName:$port")
            }.distinct()

            statusText.text = "${getString(R.string.status_running)} on port $port"
            urlText.text = buildUrlDisplayText(
                friendlyUrl  = friendlyUrl,
                primaryIp    = primaryIp,
                port         = port,
                dnsStarted   = dnsStarted,
                dnsError     = hotspotDns?.lastError,
            )

            startButton.isEnabled = false
            stopButton.isEnabled = true
            Toast.makeText(this, "Server started.", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            server = null
            currentPort = null
            currentIp = null
            currentHostname = null
            dnsHostname = null
            currentUrls = emptyList()
            statusText.text = getString(R.string.status_stopped)
            urlText.text = error.message ?: getString(R.string.url_unavailable)
            Toast.makeText(this, "Could not start server.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopServer() {
        hotspotDns?.stop()
        hotspotDns = null
        dnsAdvertiser?.stop()
        dnsAdvertiser = null
        server?.stop()
        server = null
        currentPort = null
        currentIp = null
        currentHostname = null
        dnsHostname = null
        currentUrls = emptyList()
        statusText.text = getString(R.string.status_stopped)
        urlText.text = getString(R.string.url_unavailable)
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    /**
     * Builds the multi-line URL text shown in the UI.
     *
     * Shows the friendly DNS name first when available, always shows the raw
     * IP as a fallback, and explains why DNS isn't working when it fails.
     */
    private fun buildUrlDisplayText(
        friendlyUrl: String?,
        primaryIp: String?,
        port: Int,
        dnsStarted: Boolean,
        dnsError: String?,
    ): String {
        if (primaryIp == null) return getString(R.string.url_unavailable)

        val lines = mutableListOf<String>()

        if (dnsStarted && friendlyUrl != null) {
            lines += "★ $friendlyUrl"
        }

        lines += "http://$primaryIp:$port"

        if (!dnsStarted) {
            val reason = if (dnsError != null) "DNS port 53 unavailable" else "DNS not started"
            lines += "($reason — use IP above)"
        }

        return lines.joinToString("\n")
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    private fun runDiagnostics() {
        diagResultText.text = getString(R.string.diag_running)
        diagResultText.visibility = View.VISIBLE
        diagButton.isEnabled = false

        val snapshotPort      = currentPort
        val snapshotHostname  = currentHostname
        val snapshotMdnsError = dnsAdvertiser?.lastError
        val snapshotDnsName   = dnsHostname
        val snapshotDnsError  = hotspotDns?.lastError
        val snapshotServer    = server
        val serverRunning     = snapshotServer != null

        ioExecutor.execute {
            val sharedFileCount = snapshotServer?.listSharedFiles()?.size ?: 0
            val report = NetworkUtils.runDiagnostics(
                port           = snapshotPort,
                localHostname  = snapshotHostname,
                mdnsError      = snapshotMdnsError,
                dnsName        = snapshotDnsName,
                dnsError       = snapshotDnsError,
                sharedFileCount = sharedFileCount,
                serverRunning  = serverRunning,
            )
            runOnUiThread {
                diagResultText.text = report
                diagButton.isEnabled = true
            }
        }
    }

    // -------------------------------------------------------------------------
    // Clipboard
    // -------------------------------------------------------------------------

    private fun copyFirstUrl() {
        val url = currentUrls.firstOrNull()
        if (url == null) {
            Toast.makeText(this, "Start the server first.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Hotspot URL", url))
        Toast.makeText(this, "URL copied.", Toast.LENGTH_SHORT).show()
    }
}

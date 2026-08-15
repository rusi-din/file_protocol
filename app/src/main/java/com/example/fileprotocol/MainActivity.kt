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
    private var currentUrls: List<String> = emptyList()
    private var currentHostname: String? = null
    private var currentPort: Int? = null

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

        statusText    = findViewById(R.id.statusText)
        urlText       = findViewById(R.id.urlText)
        folderText    = findViewById(R.id.folderText)
        lastUploadText = findViewById(R.id.lastUploadText)
        portInput     = findViewById(R.id.portInput)
        startButton   = findViewById(R.id.startButton)
        stopButton    = findViewById(R.id.stopButton)
        copyButton    = findViewById(R.id.copyButton)
        diagButton    = findViewById(R.id.diagButton)
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

            val primaryIpAddress = NetworkUtils.getPrivateIpv4Addresses().firstOrNull()
            val localHostname = if (primaryIpAddress == null) {
                null
            } else {
                LocalDnsAdvertiser(applicationContext).also { advertiser ->
                    dnsAdvertiser = advertiser
                }.start(primaryIpAddress, port)
            }
            currentHostname = localHostname
            currentUrls = NetworkUtils.getReachableBaseUrls(port, localHostname)

            statusText.text = "${getString(R.string.status_running)} on port $port"
            urlText.text = if (currentUrls.isEmpty()) {
                getString(R.string.url_unavailable)
            } else {
                currentUrls.joinToString(separator = "\n")
            }
            startButton.isEnabled = false
            stopButton.isEnabled = true

            Toast.makeText(this, "Upload server started.", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            server = null
            currentPort = null
            currentHostname = null
            currentUrls = emptyList()
            statusText.text = getString(R.string.status_stopped)
            urlText.text = error.message ?: getString(R.string.url_unavailable)
            Toast.makeText(this, "Could not start server.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopServer() {
        dnsAdvertiser?.stop()
        dnsAdvertiser = null
        server?.stop()
        server = null
        currentPort = null
        currentHostname = null
        currentUrls = emptyList()
        statusText.text = getString(R.string.status_stopped)
        urlText.text = getString(R.string.url_unavailable)
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    private fun runDiagnostics() {
        diagResultText.text = getString(R.string.diag_running)
        diagResultText.visibility = View.VISIBLE
        diagButton.isEnabled = false

        val snapshotPort = currentPort
        val snapshotHostname = currentHostname
        val snapshotServer = server
        val serverRunning = snapshotServer != null

        ioExecutor.execute {
            val sharedFileCount = snapshotServer?.listSharedFiles()?.size ?: 0
            val report = NetworkUtils.runDiagnostics(
                port = snapshotPort,
                localHostname = snapshotHostname,
                sharedFileCount = sharedFileCount,
                serverRunning = serverRunning,
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

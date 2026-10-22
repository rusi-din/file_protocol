package com.example.fileprotocol

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var server: HotspotFileServer? = null
    private var dnsAdvertiser: LocalDnsAdvertiser? = null
    private var hotspotDns: HotspotDnsServer? = null
    private var currentUrls: List<String> = emptyList()
    private var currentHostname: String? = null
    private var currentPort: Int? = null
    private var currentIp: String? = null
    private var dnsHostname: String? = null

    // Host UI Elements
    private lateinit var tabLayout: TabLayout
    private lateinit var hostView: ScrollView
    private lateinit var clientView: LinearLayout
    private lateinit var statusBadge: TextView
    private lateinit var urlText: TextView
    private lateinit var portInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var copyButton: Button
    private lateinit var diagButton: Button
    private lateinit var diagResultText: TextView
    private lateinit var qrImageView: ImageView
    private lateinit var hostTextInput: EditText
    private lateinit var hostSendTextButton: Button

    // Client UI Elements
    private lateinit var clientServerUrlInput: EditText
    private lateinit var clientPinInput: EditText
    private lateinit var clientConnectButton: Button
    private lateinit var clientSubTabs: TabLayout
    private lateinit var clientFilesContainer: LinearLayout
    private lateinit var clientTextsContainer: LinearLayout
    private lateinit var clientSendContainer: ScrollView
    private lateinit var clientFilesRecyclerView: RecyclerView
    private lateinit var clientTextsRecyclerView: RecyclerView
    private lateinit var clientSendTextInput: EditText
    private lateinit var clientSendTextBtn: Button
    private lateinit var clientUploadFileBtn: Button

    // Adapters & Client
    private lateinit var hotspotClient: HotspotClient
    private lateinit var filesAdapter: FilesAdapter
    private lateinit var textsAdapter: TextsAdapter

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { uploadFileToServer(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hotspotClient = HotspotClient(applicationContext)

        initViews()
        setupHostListeners()
        setupClientListeners()
        setupTabs()
    }

    override fun onDestroy() {
        stopServer()
        ioExecutor.shutdown()
        super.onDestroy()
    }

    private fun initViews() {
        tabLayout = findViewById(R.id.tabLayout)
        hostView = findViewById(R.id.hostView)
        clientView = findViewById(R.id.clientView)

        statusBadge = findViewById(R.id.statusBadge)
        urlText = findViewById(R.id.urlText)
        portInput = findViewById(R.id.portInput)
        pinInput = findViewById(R.id.pinInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        copyButton = findViewById(R.id.copyButton)
        diagButton = findViewById(R.id.diagButton)
        diagResultText = findViewById(R.id.diagResultText)
        qrImageView = findViewById(R.id.qrImageView)
        hostTextInput = findViewById(R.id.hostTextInput)
        hostSendTextButton = findViewById(R.id.hostSendTextButton)

        clientServerUrlInput = findViewById(R.id.clientServerUrlInput)
        clientPinInput = findViewById(R.id.clientPinInput)
        clientConnectButton = findViewById(R.id.clientConnectButton)
        clientSubTabs = findViewById(R.id.clientSubTabs)
        clientFilesContainer = findViewById(R.id.clientFilesContainer)
        clientTextsContainer = findViewById(R.id.clientTextsContainer)
        clientSendContainer = findViewById(R.id.clientSendContainer)
        clientFilesRecyclerView = findViewById(R.id.clientFilesRecyclerView)
        clientTextsRecyclerView = findViewById(R.id.clientTextsRecyclerView)
        clientSendTextInput = findViewById(R.id.clientSendTextInput)
        clientSendTextBtn = findViewById(R.id.clientSendTextBtn)
        clientUploadFileBtn = findViewById(R.id.clientUploadFileBtn)

        // Setup Client Adapters
        filesAdapter = FilesAdapter { file -> downloadFileFromClient(file) }
        clientFilesRecyclerView.layoutManager = LinearLayoutManager(this)
        clientFilesRecyclerView.adapter = filesAdapter

        textsAdapter = TextsAdapter { text -> copyToClipboard("Shared Text", text) }
        clientTextsRecyclerView.layoutManager = LinearLayoutManager(this)
        clientTextsRecyclerView.adapter = textsAdapter
    }

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (tab?.position == 0) {
                    hostView.visibility = View.VISIBLE
                    clientView.visibility = View.GONE
                } else {
                    hostView.visibility = View.GONE
                    clientView.visibility = View.VISIBLE
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        clientSubTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                clientFilesContainer.visibility = if (tab?.position == 0) View.VISIBLE else View.GONE
                clientTextsContainer.visibility = if (tab?.position == 1) View.VISIBLE else View.GONE
                clientSendContainer.visibility = if (tab?.position == 2) View.VISIBLE else View.GONE
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupHostListeners() {
        startButton.setOnClickListener { startServer() }
        stopButton.setOnClickListener { stopServer() }
        copyButton.setOnClickListener { copyFirstUrl() }
        diagButton.setOnClickListener { runDiagnostics() }

        hostSendTextButton.setOnClickListener {
            val s = server
            if (s == null) {
                Toast.makeText(this, "Please start the server first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val text = hostTextInput.text.toString().trim()
            if (text.isNotBlank()) {
                s.addTextSnippet(text)
                hostTextInput.setText("")
                Toast.makeText(this, "Text snippet shared on server!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter text to share.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupClientListeners() {
        clientConnectButton.setOnClickListener { refreshClientData() }

        clientSendTextBtn.setOnClickListener {
            val serverUrl = clientServerUrlInput.text.toString().trim()
            val pin = clientPinInput.text.toString().trim().ifBlank { null }
            val text = clientSendTextInput.text.toString().trim()
            if (serverUrl.isBlank() || text.isBlank()) {
                Toast.makeText(this, "Enter server URL and text", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            hotspotClient.sendText(serverUrl, text, pin) { result ->
                runOnUiThread {
                    if (result.isSuccess) {
                        clientSendTextInput.setText("")
                        Toast.makeText(this, "Text sent to server!", Toast.LENGTH_SHORT).show()
                        refreshClientData()
                    } else {
                        Toast.makeText(this, "Failed to send text", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        clientUploadFileBtn.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }
    }

    private fun refreshClientData() {
        val serverUrl = clientServerUrlInput.text.toString().trim()
        val pin = clientPinInput.text.toString().trim().ifBlank { null }
        if (serverUrl.isBlank()) return

        hotspotClient.fetchFiles(serverUrl, pin) { result ->
            runOnUiThread {
                result.getOrNull()?.let { filesAdapter.updateFiles(it) }
            }
        }

        hotspotClient.fetchTexts(serverUrl, pin) { result ->
            runOnUiThread {
                result.getOrNull()?.let { textsAdapter.updateTexts(it) }
            }
        }
    }

    private fun downloadFileFromClient(file: HotspotFileServer.SharedFile) {
        val serverUrl = clientServerUrlInput.text.toString().trim().removeSuffix("/")
        val downloadUrl = "$serverUrl/download?id=${file.id}"
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle(file.displayName)
            .setDescription("Downloading file from Hotspot Drop")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file.displayName)

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(this, "Downloading ${file.displayName}...", Toast.LENGTH_SHORT).show()
    }

    private fun uploadFileToServer(uri: Uri) {
        val serverUrl = clientServerUrlInput.text.toString().trim()
        val pin = clientPinInput.text.toString().trim().ifBlank { null }
        if (serverUrl.isBlank()) {
            Toast.makeText(this, "Enter server URL first", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Uploading file...", Toast.LENGTH_SHORT).show()
        hotspotClient.uploadFile(serverUrl, uri, pin) { result ->
            runOnUiThread {
                if (result.getOrDefault(false)) {
                    Toast.makeText(this, "Upload complete!", Toast.LENGTH_SHORT).show()
                    refreshClientData()
                } else {
                    Toast.makeText(this, "Upload failed.", Toast.LENGTH_SHORT).show()
                }
            }
        }
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

        val pin = pinInput.text.toString().trim().ifBlank { null }

        stopServer()

        val localServer = HotspotFileServer(
            context = applicationContext,
            port = port,
            pinCode = pin,
            onFileSaved = { fileName ->
                runOnUiThread {
                    Toast.makeText(this, "Received file: $fileName", Toast.LENGTH_SHORT).show()
                }
            },
            onTextReceived = { text ->
                runOnUiThread {
                    Toast.makeText(this, "New text snippet received", Toast.LENGTH_SHORT).show()
                }
            }
        )

        try {
            localServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = localServer
            currentPort = port

            val hotspotIp = NetworkUtils.getHotspotGatewayIp()
            val primaryIp = hotspotIp ?: NetworkUtils.getPrivateIpv4Addresses().firstOrNull()
            currentIp = primaryIp

            val advertiser = if (primaryIp != null) {
                LocalDnsAdvertiser(applicationContext).also { dnsAdvertiser = it }
            } else null
            val mdnsName = advertiser?.start(primaryIp!!, port)
            currentHostname = mdnsName

            val dnsStarted = if (primaryIp != null) {
                val dns = HotspotDnsServer(
                    hostname = HotspotDnsServer.DEFAULT_HOSTNAME,
                    resolvedIp = primaryIp,
                )
                hotspotDns = dns
                dns.start()
            } else false

            dnsHostname = if (dnsStarted) HotspotDnsServer.DEFAULT_HOSTNAME else null

            val friendlyUrl = if (dnsStarted && primaryIp != null) {
                "http://${HotspotDnsServer.DEFAULT_HOSTNAME}:$port"
            } else null
            currentUrls = buildList {
                if (friendlyUrl != null) add(friendlyUrl)
                if (primaryIp != null) add("http://$primaryIp:$port")
                if (mdnsName != null) add("http://$mdnsName:$port")
            }.distinct()

            statusBadge.text = "RUNNING"
            statusBadge.setTextColor(0xFF15803D.toInt())
            statusBadge.setBackgroundColor(0xFFDCFCE7.toInt())

            val primaryUrl = currentUrls.firstOrNull() ?: "http://localhost:$port"
            urlText.text = buildUrlDisplayText(
                friendlyUrl = friendlyUrl,
                primaryIp = primaryIp,
                port = port,
                dnsStarted = dnsStarted,
                dnsError = hotspotDns?.lastError,
            )

            // Render QR Code bitmap
            val qrBitmap = QrCodeGenerator.generateQrBitmap(primaryUrl)
            qrImageView.setImageBitmap(qrBitmap)
            qrImageView.visibility = View.VISIBLE

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
            statusBadge.text = "STOPPED"
            statusBadge.setTextColor(0xFFDC2626.toInt())
            statusBadge.setBackgroundColor(0xFFFEE2E2.toInt())
            urlText.text = error.message ?: getString(R.string.url_unavailable)
            qrImageView.visibility = View.GONE
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

        statusBadge.text = "STOPPED"
        statusBadge.setTextColor(0xFFDC2626.toInt())
        statusBadge.setBackgroundColor(0xFFFEE2E2.toInt())
        urlText.text = getString(R.string.url_unavailable)
        qrImageView.visibility = View.GONE
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

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

    private fun runDiagnostics() {
        diagResultText.text = getString(R.string.diag_running)
        diagResultText.visibility = View.VISIBLE
        diagButton.isEnabled = false

        val snapshotPort = currentPort
        val snapshotHostname = currentHostname
        val snapshotMdnsError = dnsAdvertiser?.lastError
        val snapshotDnsName = dnsHostname
        val snapshotDnsError = hotspotDns?.lastError
        val snapshotServer = server
        val serverRunning = snapshotServer != null

        ioExecutor.execute {
            val sharedFileCount = snapshotServer?.listSharedFiles()?.size ?: 0
            val report = NetworkUtils.runDiagnostics(
                port = snapshotPort,
                localHostname = snapshotHostname,
                mdnsError = snapshotMdnsError,
                dnsName = snapshotDnsName,
                dnsError = snapshotDnsError,
                sharedFileCount = sharedFileCount,
                serverRunning = serverRunning,
            )
            runOnUiThread {
                diagResultText.text = report
                diagButton.isEnabled = true
            }
        }
    }

    private fun copyFirstUrl() {
        val url = currentUrls.firstOrNull()
        if (url == null) {
            Toast.makeText(this, "Start the server first.", Toast.LENGTH_SHORT).show()
            return
        }
        copyToClipboard("Hotspot URL", url)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
    }
}

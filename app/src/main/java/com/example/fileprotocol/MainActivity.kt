package com.example.fileprotocol

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
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

    // UI Elements - General
    private lateinit var headerStatusBadge: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var hostView: ScrollView
    private lateinit var clientView: LinearLayout

    // Host View Elements
    private lateinit var peersConnectedText: TextView
    private lateinit var qrImageView: ImageView
    private lateinit var urlText: TextView
    private lateinit var copyButton: Button
    private lateinit var serverEngineStatusBadge: TextView
    private lateinit var serverEngineListeningText: TextView
    private lateinit var portInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var diagButton: Button
    private lateinit var diagResultText: TextView
    private lateinit var hostTextInput: EditText
    private lateinit var hostUploadFileBtn: Button
    private lateinit var hostSendTextButton: Button
    private lateinit var hostVaultBadge: TextView
    private lateinit var hostRefreshBtn: ImageButton
    private lateinit var hostItemsRecyclerView: RecyclerView

    // Client View Elements
    private lateinit var transferStatusTitle: TextView
    private lateinit var transferStatusSpeed: TextView
    private lateinit var transferProgressBar: LinearProgressIndicator
    private lateinit var clientServerUrlInput: EditText
    private lateinit var clientPinInput: EditText
    private lateinit var clientConnectButton: Button
    private lateinit var clientStatusFeedbackText: TextView
    private lateinit var clientSubTabs: TabLayout
    private lateinit var clientFilesRecyclerView: RecyclerView
    private lateinit var clientTextsRecyclerView: RecyclerView
    private lateinit var clientSendContainer: LinearLayout
    private lateinit var clientSendTextInput: EditText
    private lateinit var clientSendTextBtn: Button
    private lateinit var clientUploadFileBtn: Button

    // Adapters & Client
    private lateinit var hotspotClient: HotspotClient
    private lateinit var filesAdapter: FilesAdapter
    private lateinit var textsAdapter: TextsAdapter
    private lateinit var hostItemsAdapter: TextsAdapter

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { uploadFileFromApp(it) }
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
        headerStatusBadge = findViewById(R.id.headerStatusBadge)
        tabLayout = findViewById(R.id.tabLayout)
        hostView = findViewById(R.id.hostView)
        clientView = findViewById(R.id.clientView)

        // Host View
        peersConnectedText = findViewById(R.id.peersConnectedText)
        qrImageView = findViewById(R.id.qrImageView)
        urlText = findViewById(R.id.urlText)
        copyButton = findViewById(R.id.copyButton)
        serverEngineStatusBadge = findViewById(R.id.serverEngineStatusBadge)
        serverEngineListeningText = findViewById(R.id.serverEngineListeningText)
        portInput = findViewById(R.id.portInput)
        pinInput = findViewById(R.id.pinInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        diagButton = findViewById(R.id.diagButton)
        diagResultText = findViewById(R.id.diagResultText)
        hostTextInput = findViewById(R.id.hostTextInput)
        hostUploadFileBtn = findViewById(R.id.hostUploadFileBtn)
        hostSendTextButton = findViewById(R.id.hostSendTextButton)
        hostVaultBadge = findViewById(R.id.hostVaultBadge)
        hostRefreshBtn = findViewById(R.id.hostRefreshBtn)
        hostItemsRecyclerView = findViewById(R.id.hostItemsRecyclerView)

        // Client View
        transferStatusTitle = findViewById(R.id.transferStatusTitle)
        transferStatusSpeed = findViewById(R.id.transferStatusSpeed)
        transferProgressBar = findViewById(R.id.transferProgressBar)
        clientServerUrlInput = findViewById(R.id.clientServerUrlInput)
        clientPinInput = findViewById(R.id.clientPinInput)
        clientConnectButton = findViewById(R.id.clientConnectButton)
        clientStatusFeedbackText = findViewById(R.id.clientStatusFeedbackText)
        clientSubTabs = findViewById(R.id.clientSubTabs)
        clientFilesRecyclerView = findViewById(R.id.clientFilesRecyclerView)
        clientTextsRecyclerView = findViewById(R.id.clientTextsRecyclerView)
        clientSendContainer = findViewById(R.id.clientSendContainer)
        clientSendTextInput = findViewById(R.id.clientSendTextInput)
        clientSendTextBtn = findViewById(R.id.clientSendTextBtn)
        clientUploadFileBtn = findViewById(R.id.clientUploadFileBtn)

        // Setup Adapters
        filesAdapter = FilesAdapter { file -> downloadFileFromClient(file) }
        clientFilesRecyclerView.layoutManager = LinearLayoutManager(this)
        clientFilesRecyclerView.adapter = filesAdapter

        textsAdapter = TextsAdapter { text -> copyToClipboard("Shared Note", text) }
        clientTextsRecyclerView.layoutManager = LinearLayoutManager(this)
        clientTextsRecyclerView.adapter = textsAdapter

        hostItemsAdapter = TextsAdapter { text -> copyToClipboard("Vault Item", text) }
        hostItemsRecyclerView.layoutManager = LinearLayoutManager(this)
        hostItemsRecyclerView.adapter = hostItemsAdapter
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
                clientFilesRecyclerView.visibility = if (tab?.position == 0) View.VISIBLE else View.GONE
                clientTextsRecyclerView.visibility = if (tab?.position == 1) View.VISIBLE else View.GONE
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
        hostRefreshBtn.setOnClickListener { refreshHostItems() }

        hostUploadFileBtn.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        hostSendTextButton.setOnClickListener {
            val s = server
            if (s == null) {
                Toast.makeText(this, "Please start the server first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            var text = hostTextInput.text.toString().trim()
            if (text.isBlank()) {
                // Auto-sync system clipboard if input field is empty
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    text = clipData.getItemAt(0).text?.toString().orEmpty()
                }
            }

            if (text.isNotBlank()) {
                s.addTextSnippet(text)
                hostTextInput.setText("")
                Toast.makeText(this, "Broadcast sent to connected peers!", Toast.LENGTH_SHORT).show()
                refreshHostItems()
            } else {
                Toast.makeText(this, "Enter text or copy something to clipboard first.", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Enter server URL and note text", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            hotspotClient.sendText(serverUrl, text, pin) { result ->
                runOnUiThread {
                    if (result.isSuccess) {
                        clientSendTextInput.setText("")
                        Toast.makeText(this, "Note sent to host!", Toast.LENGTH_SHORT).show()
                        refreshClientData()
                    } else {
                        Toast.makeText(this, "Failed to send note to host", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        clientUploadFileBtn.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }
    }

    private fun refreshHostItems() {
        val s = server
        if (s == null) {
            hostVaultBadge.text = "0 items"
            hostItemsAdapter.updateTexts(emptyList())
            return
        }

        val files = s.listSharedFiles()
        val snippets = s.getTextSnippets()

        hostVaultBadge.text = "${files.size + snippets.size} items"

        val combinedList = mutableListOf<HotspotFileServer.TextSnippet>()
        files.forEach { f ->
            combinedList.add(
                HotspotFileServer.TextSnippet(
                    id = f.id,
                    text = "📄 ${f.displayName} (${f.size / 1024} KB)"
                )
            )
        }
        snippets.forEach { t ->
            combinedList.add(
                HotspotFileServer.TextSnippet(
                    id = t.id,
                    text = "📝 ${t.text}"
                )
            )
        }

        hostItemsAdapter.updateTexts(combinedList)
    }

    private fun refreshClientData() {
        val serverUrl = clientServerUrlInput.text.toString().trim()
        val pin = clientPinInput.text.toString().trim().ifBlank { null }
        if (serverUrl.isBlank()) {
            clientStatusFeedbackText.text = "Please enter a valid host URL."
            return
        }

        clientStatusFeedbackText.text = "Connecting to $serverUrl..."
        transferStatusTitle.text = "Connecting..."
        transferStatusSpeed.text = "0 KB/s"

        hotspotClient.fetchFiles(serverUrl, pin) { fileResult ->
            hotspotClient.fetchTexts(serverUrl, pin) { textResult ->
                runOnUiThread {
                    if (fileResult.isSuccess && textResult.isSuccess) {
                        transferStatusTitle.text = "Connected to Host"
                        transferStatusSpeed.text = "Ready"
                        transferProgressBar.progress = 100
                        clientStatusFeedbackText.text = "Connected successfully to $serverUrl"

                        fileResult.getOrNull()?.let { filesAdapter.updateFiles(it) }
                        textResult.getOrNull()?.let { textsAdapter.updateTexts(it) }
                        Toast.makeText(this, "Connected and fetched vault items!", Toast.LENGTH_SHORT).show()
                    } else {
                        transferStatusTitle.text = "Connection Failed"
                        transferStatusSpeed.text = "Error"
                        val err = fileResult.exceptionOrNull()?.message ?: textResult.exceptionOrNull()?.message
                        clientStatusFeedbackText.text = "Connection failed: ${err ?: "Could not reach host"}"
                    }
                }
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

    private fun uploadFileFromApp(uri: Uri) {
        // If server host is running, we can also save locally
        val activeServer = server
        val serverUrl = clientServerUrlInput.text.toString().trim()
        val pin = clientPinInput.text.toString().trim().ifBlank { null }

        if (activeServer != null && hostView.visibility == View.VISIBLE) {
            // Save file directly on Host
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val tempFile = java.io.File(cacheDir, "host_upload_${System.currentTimeMillis()}")
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                    val resolver = contentResolver
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "host_file_${System.currentTimeMillis()}")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, HotspotFileServer.SHARED_FOLDER_PATH)
                    }
                    val target = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    target?.let { t ->
                        resolver.openOutputStream(t)?.use { o -> tempFile.inputStream().copyTo(o) }
                    }
                    tempFile.delete()
                }
                Toast.makeText(this, "File added to Hotspot Vault!", Toast.LENGTH_SHORT).show()
                refreshHostItems()
            } catch (e: Exception) {
                Toast.makeText(this, "Error adding file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (serverUrl.isBlank()) {
            Toast.makeText(this, "Enter server URL or start host first", Toast.LENGTH_SHORT).show()
            return
        }

        transferStatusTitle.text = "Uploading File..."
        transferStatusSpeed.text = "Uploading"
        hotspotClient.uploadFile(serverUrl, uri, pin) { result ->
            runOnUiThread {
                if (result.getOrDefault(false)) {
                    transferStatusTitle.text = "Upload Complete"
                    transferStatusSpeed.text = "100%"
                    Toast.makeText(this, "Upload complete!", Toast.LENGTH_SHORT).show()
                    refreshClientData()
                } else {
                    transferStatusTitle.text = "Upload Failed"
                    transferStatusSpeed.text = "Error"
                    Toast.makeText(this, "Upload failed.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Server Lifecycle
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
                    refreshHostItems()
                }
            },
            onTextReceived = { text ->
                runOnUiThread {
                    Toast.makeText(this, "New text note received", Toast.LENGTH_SHORT).show()
                    refreshHostItems()
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

            headerStatusBadge.text = "● Server Active: $port"
            headerStatusBadge.setTextColor(0xFF16A34A.toInt())
            headerStatusBadge.setBackgroundColor(0xFFDCFCE7.toInt())

            serverEngineStatusBadge.text = "● Online"
            serverEngineStatusBadge.setTextColor(0xFF16A34A.toInt())

            val primaryUrl = currentUrls.firstOrNull() ?: "http://localhost:$port"
            urlText.text = primaryUrl
            serverEngineListeningText.text = "Listening on ${primaryIp ?: "127.0.0.1"}:$port"

            // Render QR Code bitmap
            val qrBitmap = QrCodeGenerator.generateQrBitmap(primaryUrl)
            qrImageView.setImageBitmap(qrBitmap)

            startButton.isEnabled = false
            stopButton.isEnabled = true
            Toast.makeText(this, "Server running on port $port.", Toast.LENGTH_SHORT).show()
            refreshHostItems()
        } catch (error: Exception) {
            server = null
            currentPort = null
            currentIp = null
            currentHostname = null
            dnsHostname = null
            currentUrls = emptyList()

            headerStatusBadge.text = "● Ready"
            headerStatusBadge.setTextColor(0xFF16A34A.toInt())
            headerStatusBadge.setBackgroundColor(0xFFDCFCE7.toInt())

            serverEngineStatusBadge.text = "● Offline"
            serverEngineStatusBadge.setTextColor(0xFFEF4444.toInt())

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

        headerStatusBadge.text = "● Ready"
        headerStatusBadge.setTextColor(0xFF16A34A.toInt())
        headerStatusBadge.setBackgroundColor(0xFFDCFCE7.toInt())

        serverEngineStatusBadge.text = "● Offline"
        serverEngineStatusBadge.setTextColor(0xFFEF4444.toInt())
        serverEngineListeningText.text = "Server stopped"

        urlText.text = "http://192.168.43.1:8080"
        startButton.isEnabled = true
        stopButton.isEnabled = false
        refreshHostItems()
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
        val url = currentUrls.firstOrNull() ?: urlText.text.toString()
        copyToClipboard("Endpoint Link", url)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied link to clipboard!", Toast.LENGTH_SHORT).show()
    }
}

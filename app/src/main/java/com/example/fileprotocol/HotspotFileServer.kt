package com.example.fileprotocol

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.text.TextUtils
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.io.InputStream
import java.net.URLConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class HotspotFileServer(
    private val context: Context,
    port: Int,
    var pinCode: String? = null,
    private val onFileSaved: (String) -> Unit = {},
    private val onTextReceived: (String) -> Unit = {},
) : NanoHTTPD(port) {

    private val textSnippets = CopyOnWriteArrayList<TextSnippet>()
    private val nextSnippetId = AtomicLong(1)

    data class TextSnippet(
        val id: Long,
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class SharedFile(
        val id: Long,
        val displayName: String,
        val size: Long,
        val mimeType: String,
    )

    fun addTextSnippet(text: String): TextSnippet {
        val snippet = TextSnippet(nextSnippetId.getAndIncrement(), text)
        textSnippets.add(0, snippet) // Newest first
        onTextReceived(text)
        return snippet
    }

    fun getTextSnippets(): List<TextSnippet> = textSnippets.toList()

    fun deleteTextSnippet(id: Long): Boolean {
        return textSnippets.removeIf { it.id == id }
    }

    fun clearTextSnippets() {
        textSnippets.clear()
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            // Check PIN authentication if required
            val activePin = pinCode
            if (!activePin.isNullOrBlank() && !isAuthenticated(session, activePin)) {
                if (session.uri == "/api/login" && session.method == Method.POST) {
                    return handleLogin(session, activePin)
                }
                if (session.uri.startsWith("/api/") || session.uri == "/files" || session.uri == "/texts") {
                    return jsonResponse(Status.UNAUTHORIZED, """{"error":"Authentication required","authRequired":true}""")
                }
                return htmlResponse(loginPage(isError = false))
            }

            when {
                // Public / Info endpoint
                session.uri == "/api/info" && session.method == Method.GET ->
                    handleInfo()

                // Web UI main page
                session.method == Method.GET && (session.uri == "/" || session.uri == "/index.html") ->
                    htmlResponse(sharedPage())

                // Download file
                session.method == Method.GET && session.uri == "/download" ->
                    handleDownload(session, inline = false)

                // Inline preview file
                session.method == Method.GET && session.uri == "/preview" ->
                    handleDownload(session, inline = true)

                // Upload file
                session.method == Method.POST && session.uri == "/upload" ->
                    handleUpload(session)

                // Files JSON API
                session.method == Method.GET && session.uri == "/files" ->
                    jsonFileList()

                // Text Snippets JSON API
                session.method == Method.GET && session.uri == "/texts" ->
                    jsonTextList()

                // Create Text Snippet API & Form submit
                session.method == Method.POST && (session.uri == "/text" || session.uri == "/api/text") ->
                    handleCreateText(session)

                // Delete Text Snippet API
                (session.method == Method.POST || session.method == Method.DELETE) && session.uri == "/text/delete" ->
                    handleDeleteText(session)

                else -> newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (error: Exception) {
            if (session.uri.startsWith("/api/")) {
                jsonResponse(Status.INTERNAL_ERROR, """{"error":${jsonString(error.message ?: "Server error")}}""")
            } else {
                htmlResponse(
                    sharedPage("Error: ${error.message ?: "Unknown error"}", isError = true),
                    Status.INTERNAL_ERROR,
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Auth Helpers
    // -------------------------------------------------------------------------

    private fun isAuthenticated(session: IHTTPSession, requiredPin: String): Boolean {
        // Check cookie, header 'X-Pin', or query param 'pin'
        val cookiePin = session.headers["cookie"]?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("auth_pin=") }
            ?.substringAfter("auth_pin=")

        val headerPin = session.headers["x-pin"]
        val paramPin = session.parameters["pin"]?.firstOrNull()

        return cookiePin == requiredPin || headerPin == requiredPin || paramPin == requiredPin
    }

    private fun handleLogin(session: IHTTPSession, requiredPin: String): Response {
        val bodyFiles = HashMap<String, String>()
        session.parseBody(bodyFiles)
        val submittedPin = session.parameters["pin"]?.firstOrNull() ?: bodyFiles["postData"]
        if (submittedPin == requiredPin) {
            val response = jsonResponse(Status.OK, """{"success":true}""")
            response.addHeader("Set-Cookie", "auth_pin=$requiredPin; Path=/; SameSite=Lax")
            return response
        }
        return jsonResponse(Status.UNAUTHORIZED, """{"error":"Invalid PIN"}""")
    }

    private fun handleInfo(): Response {
        val fileCount = listSharedFiles().size
        val textCount = textSnippets.size
        val pinRequired = !pinCode.isNullOrBlank()
        val json = """{"authRequired":$pinRequired,"fileCount":$fileCount,"textCount":$textCount}"""
        return jsonResponse(Status.OK, json)
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private fun handleUpload(session: IHTTPSession): Response {
        val bodyFiles = HashMap<String, String>()
        session.parseBody(bodyFiles)

        val tempFilePath = bodyFiles["file"]
            ?: return if (isJsonRequest(session)) {
                jsonResponse(Status.BAD_REQUEST, """{"error":"No file uploaded"}""")
            } else {
                htmlResponse(sharedPage("No file was selected.", isError = true), Status.BAD_REQUEST)
            }

        val tempFile = File(tempFilePath)
        val originalName = session.parameters["file"]
            ?.firstOrNull()
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            .orEmpty()
            .ifBlank { "upload-${System.currentTimeMillis()}" }

        return try {
            val savedName = saveToDownloads(tempFile, sanitizeFilename(originalName))
            onFileSaved(savedName)
            if (isJsonRequest(session)) {
                jsonResponse(Status.OK, """{"success":true,"name":${jsonString(savedName)}}""")
            } else {
                htmlResponse(sharedPage("Uploaded \u201c$savedName\u201d successfully."))
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun handleDownload(session: IHTTPSession, inline: Boolean): Response {
        val fileId = session.parameters["id"]?.firstOrNull()?.toLongOrNull()
            ?: return htmlResponse(sharedPage("Missing file id.", isError = true), Status.BAD_REQUEST)

        val sharedFile = findSharedFile(fileId)
            ?: return htmlResponse(sharedPage("File not found or access denied.", isError = true), Status.NOT_FOUND)

        val fileUri = android.content.ContentUris.withAppendedId(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            fileId,
        )
        val inputStream = context.contentResolver.openInputStream(fileUri)
            ?: return htmlResponse(sharedPage("Could not open the selected file.", isError = true), Status.INTERNAL_ERROR)

        return streamDownload(sharedFile.displayName, sharedFile.mimeType, inputStream, inline)
    }

    private fun handleCreateText(session: IHTTPSession): Response {
        val bodyFiles = HashMap<String, String>()
        session.parseBody(bodyFiles)

        var textContent = session.parameters["text"]?.firstOrNull()
            ?: bodyFiles["postData"]
            ?: bodyFiles["text"]

        if (textContent.isNullOrBlank()) {
            return if (isJsonRequest(session)) {
                jsonResponse(Status.BAD_REQUEST, """{"error":"Text snippet cannot be empty"}""")
            } else {
                htmlResponse(sharedPage("Text cannot be empty.", isError = true), Status.BAD_REQUEST)
            }
        }

        // Clean up quoted JSON postData if sent as raw string
        if (textContent.startsWith("\"") && textContent.endsWith("\"") && textContent.length >= 2) {
            textContent = textContent.substring(1, textContent.length - 1)
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }

        val snippet = addTextSnippet(textContent)

        return if (isJsonRequest(session)) {
            jsonResponse(
                Status.OK,
                """{"success":true,"id":${snippet.id},"text":${jsonString(snippet.text)},"timestamp":${snippet.timestamp}}""",
            )
        } else {
            htmlResponse(sharedPage("Text snippet shared successfully!"))
        }
    }

    private fun handleDeleteText(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()?.toLongOrNull()
            ?: return jsonResponse(Status.BAD_REQUEST, """{"error":"Missing id"}""")

        val removed = deleteTextSnippet(id)
        return jsonResponse(Status.OK, """{"success":$removed}""")
    }

    private fun jsonFileList(): Response {
        val files = listSharedFiles()
        val json = buildString {
            append("[")
            files.forEachIndexed { index, f ->
                if (index > 0) append(",")
                append(
                    """{"id":${f.id},"name":${jsonString(f.displayName)},"size":${f.size},"mime":${jsonString(f.mimeType)},"url":"/download?id=${f.id}","previewUrl":"/preview?id=${f.id}"}"""
                )
            }
            append("]")
        }
        return jsonResponse(Status.OK, json)
    }

    private fun jsonTextList(): Response {
        val snippets = getTextSnippets()
        val json = buildString {
            append("[")
            snippets.forEachIndexed { index, s ->
                if (index > 0) append(",")
                append(
                    """{"id":${s.id},"text":${jsonString(s.text)},"timestamp":${s.timestamp}}"""
                )
            }
            append("]")
        }
        return jsonResponse(Status.OK, json)
    }

    // -------------------------------------------------------------------------
    // Storage helpers
    // -------------------------------------------------------------------------

    private fun saveToDownloads(sourceFile: File, displayName: String): String {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, guessMimeType(displayName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, SHARED_FOLDER_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: error("Could not create a file in Downloads.")

        resolver.openOutputStream(targetUri)?.use { output ->
            sourceFile.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Could not open output stream for upload.")

        resolver.update(
            targetUri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )

        return displayName
    }

    fun listSharedFiles(): List<SharedFile> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("$SHARED_FOLDER_PATH%")
        val orderBy = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        return context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            orderBy,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SharedFile(
                            id = cursor.getLong(idCol),
                            displayName = cursor.getString(nameCol) ?: "file",
                            size = cursor.getLong(sizeCol),
                            mimeType = cursor.getString(mimeCol) ?: "application/octet-stream",
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun findSharedFile(fileId: Long): SharedFile? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        return context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.MediaColumns._ID} = ?",
            arrayOf(fileId.toString()),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null

            val relativePath = cursor.getString(
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH),
            ).orEmpty()
            if (!relativePath.startsWith(SHARED_FOLDER_PATH)) return@use null

            SharedFile(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
                displayName = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME),
                ) ?: "file",
                size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
                mimeType = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE),
                ) ?: "application/octet-stream",
            )
        }
    }

    // -------------------------------------------------------------------------
    // Response helpers
    // -------------------------------------------------------------------------

    private fun streamDownload(
        fileName: String,
        mimeType: String,
        inputStream: InputStream,
        inline: Boolean,
    ): Response {
        val safeFileName = fileName.replace("\"", "_")
        val disposition = if (inline) "inline" else "attachment; filename=\"$safeFileName\""
        return newChunkedResponse(Status.OK, mimeType, inputStream).apply {
            addHeader("Content-Disposition", disposition)
            addHeader("Cache-Control", "no-store")
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    private fun htmlResponse(body: String, status: Response.IStatus = Status.OK): Response {
        return newFixedLengthResponse(status, "text/html; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun jsonResponse(status: Response.IStatus, json: String): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
            addHeader("Access-Control-Allow-Headers", "Content-Type, X-Pin")
        }
    }

    private fun isJsonRequest(session: IHTTPSession): Boolean {
        val accept = session.headers["accept"].orEmpty()
        val contentType = session.headers["content-type"].orEmpty()
        return accept.contains("application/json") || contentType.contains("application/json")
    }

    // -------------------------------------------------------------------------
    // HTML page builders
    // -------------------------------------------------------------------------

    private fun loginPage(isError: Boolean): String {
        val errorMsg = if (isError) "<p class=\"error\">Incorrect PIN code. Please try again.</p>" else ""
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>Hotspot Drop - Authentication Required</title>
              <style>
                *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                  background: #f4f6f9; color: #1e293b; display: flex; align-items: center; justify-content: center; min-height: 100vh; padding: 20px;
                }
                .card { background: #fff; border-radius: 16px; padding: 32px; width: 100%; max-width: 400px; box-shadow: 0 10px 25px rgba(0,0,0,0.08); text-align: center; }
                h1 { font-size: 1.5rem; margin-bottom: 8px; color: #0f172a; }
                p { color: #64748b; font-size: 0.95rem; margin-bottom: 24px; }
                input { width: 100%; padding: 12px 16px; font-size: 1.2rem; border: 2px solid #e2e8f0; border-radius: 10px; margin-bottom: 16px; text-align: center; letter-spacing: 4px; outline: none; }
                input:focus { border-color: #2563eb; }
                button { width: 100%; padding: 12px; background: #2563eb; color: white; border: none; border-radius: 10px; font-size: 1rem; font-weight: 600; cursor: pointer; transition: background .2s; }
                button:hover { background: #1d4ed8; }
                .error { color: #ef4444; font-size: 0.88rem; margin-bottom: 12px; }
              </style>
            </head>
            <body>
              <div class="card">
                <div style="font-size:2.5rem;margin-bottom:12px;">🔒</div>
                <h1>Protected Network</h1>
                <p>This Hotspot Drop server requires a PIN code to access.</p>
                $errorMsg
                <input type="password" id="pinInput" placeholder="Enter PIN" autofocus />
                <button onclick="submitPin()">Connect</button>
              </div>
              <script>
                function submitPin() {
                  const pin = document.getElementById('pinInput').value;
                  fetch('/api/login', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                    body: 'pin=' + encodeURIComponent(pin)
                  }).then(r => r.json()).then(data => {
                    if (data.success) {
                      document.cookie = "auth_pin=" + pin + "; path=/";
                      window.location.reload();
                    } else {
                      alert('Invalid PIN');
                    }
                  }).catch(() => alert('Connection error'));
                }
                document.getElementById('pinInput').addEventListener('keypress', e => {
                  if (e.key === 'Enter') submitPin();
                });
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun sharedPage(message: String? = null, isError: Boolean = false): String {
        val sharedFiles = listSharedFiles()
        val snippets = getTextSnippets()
        val safeMessage = message?.let { TextUtils.htmlEncode(it) }
        val messageHtml = if (safeMessage == null) {
            ""
        } else {
            val bgColor = if (isError) "#fef2f2" else "#f0fdf4"
            val textColor = if (isError) "#991b1b" else "#166534"
            val borderColor = if (isError) "#fecaca" else "#bbf7d0"
            "<div class=\"banner\" style=\"background:$bgColor;color:$textColor;border:1px solid $borderColor;\">$safeMessage</div>"
        }

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>Hotspot Drop</title>
              <style>
                *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
                :root {
                  --primary: #2563eb;
                  --primary-hover: #1d4ed8;
                  --bg: #f8fafc;
                  --card-bg: #ffffff;
                  --text-main: #0f172a;
                  --text-muted: #64748b;
                  --border: #e2e8f0;
                  --shadow: 0 4px 20px -2px rgba(0, 0, 0, 0.05);
                }
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                  background: var(--bg);
                  color: var(--text-main);
                  padding: 20px 16px 40px;
                }
                .container { max-width: 680px; margin: 0 auto; }
                header {
                  display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px;
                }
                .logo { display: flex; align-items: center; gap: 10px; font-size: 1.5rem; font-weight: 700; color: var(--text-main); }
                .logo-icon { width: 38px; height: 38px; background: #dbeafe; color: #2563eb; border-radius: 10px; display: flex; align-items: center; justify-content: center; font-size: 1.2rem; }

                .card {
                  background: var(--card-bg);
                  border-radius: 16px;
                  padding: 20px;
                  box-shadow: var(--shadow);
                  border: 1px solid var(--border);
                  margin-bottom: 20px;
                }
                .card-title {
                  font-size: 1.1rem; font-weight: 600; margin-bottom: 14px; display: flex; align-items: center; justify-content: space-between;
                }

                /* Tabs / Modes */
                .tabs { display: flex; gap: 8px; margin-bottom: 16px; background: #e2e8f0; padding: 4px; border-radius: 10px; }
                .tab-btn {
                  flex: 1; padding: 8px 12px; border: none; background: transparent; border-radius: 8px; font-weight: 600; font-size: 0.9rem; color: var(--text-muted); cursor: pointer; transition: all .2s;
                }
                .tab-btn.active { background: #fff; color: var(--primary); box-shadow: 0 2px 6px rgba(0,0,0,0.06); }

                /* Drop Zone */
                .drop-zone {
                  border: 2px dashed #93c5fd;
                  border-radius: 12px;
                  padding: 28px 16px;
                  text-align: center;
                  background: #f0f6ff;
                  transition: all .2s;
                  cursor: pointer;
                }
                .drop-zone:hover, .drop-zone.dragover { background: #e0edff; border-color: var(--primary); }
                .drop-zone strong { display: block; font-size: 1.05rem; margin-bottom: 4px; color: #1e3a8a; }
                .hint { color: var(--text-muted); font-size: .85rem; margin-top: 4px; }

                .btn {
                  display: inline-block;
                  padding: 9px 18px;
                  border: 0;
                  border-radius: 8px;
                  background: var(--primary);
                  color: #fff;
                  font-weight: 600;
                  font-size: 0.9rem;
                  cursor: pointer;
                  transition: background .2s;
                }
                .btn:hover { background: var(--primary-hover); }
                .btn-secondary { background: #f1f5f9; color: #334155; }
                .btn-secondary:hover { background: #e2e8f0; }
                .btn-sm { padding: 5px 10px; font-size: 0.8rem; border-radius: 6px; }

                /* Text Box */
                textarea {
                  width: 100%; border: 1px solid var(--border); border-radius: 10px; padding: 12px; font-family: inherit; font-size: 0.95rem; outline: none; resize: vertical; min-height: 80px; margin-bottom: 10px;
                }
                textarea:focus { border-color: var(--primary); }

                /* Progress bar */
                .progress-wrap { display: none; height: 6px; background: #e2e8f0; border-radius: 3px; margin-top: 12px; overflow: hidden; }
                .progress-bar { height: 100%; background: var(--primary); width: 0%; transition: width .15s; }
                .status { margin-top: 8px; font-size: .88rem; color: var(--text-muted); }

                .banner { padding: 10px 14px; border-radius: 8px; font-size: .9rem; font-weight: 500; margin-top: 14px; }

                /* Filter / Search */
                .search-box {
                  width: 100%; padding: 8px 12px; border: 1px solid var(--border); border-radius: 8px; font-size: 0.9rem; margin-bottom: 12px; outline: none;
                }

                /* Lists */
                .item-list { list-style: none; }
                .list-item {
                  display: flex; align-items: center; gap: 12px; padding: 12px 0; border-top: 1px solid var(--border);
                }
                .list-item:first-child { border-top: 0; }
                .item-icon { font-size: 1.5rem; flex-shrink: 0; }
                .item-info { flex: 1; min-width: 0; }
                .item-name { font-weight: 600; font-size: 0.95rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                .item-sub { color: var(--text-muted); font-size: 0.8rem; margin-top: 2px; }
                .item-actions { display: flex; gap: 6px; flex-shrink: 0; }

                .badge {
                  background: #dbeafe; color: #1e40af; font-size: .75rem; font-weight: 600; border-radius: 99px; padding: 2px 8px; margin-left: 6px;
                }

                /* Text Snippet Card */
                .text-card {
                  background: #f8fafc; border: 1px solid var(--border); border-radius: 10px; padding: 12px; margin-bottom: 10px; position: relative;
                }
                .text-content { font-size: 0.92rem; white-space: pre-wrap; word-break: break-word; color: #1e293b; }
                .text-footer { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; font-size: 0.78rem; color: var(--text-muted); }

                /* Modal Preview */
                .modal { display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.6); z-index: 100; align-items: center; justify-content: center; padding: 20px; }
                .modal-content { background: #fff; border-radius: 16px; max-width: 90vw; max-height: 85vh; padding: 20px; overflow: auto; text-align: center; position: relative; }
                .modal-close { position: absolute; top: 10px; right: 14px; font-size: 1.5rem; cursor: pointer; color: var(--text-muted); }
                .modal img { max-width: 100%; max-height: 70vh; border-radius: 8px; }
                .modal iframe { width: 100%; height: 60vh; border: none; }
              </style>
            </head>
            <body>
              <div class="container">
                <header>
                  <div class="logo">
                    <div class="logo-icon">⚡</div>
                    Hotspot Drop
                  </div>
                  <span id="liveStatus" style="font-size:0.8rem; color:#16a34a; font-weight:600; display:flex; align-items:center; gap:4px;">● Connected</span>
                </header>

                <!-- Input Section -->
                <div class="card">
                  <div class="tabs">
                    <button class="tab-btn active" onclick="switchTab('fileTab')">📁 Upload File</button>
                    <button class="tab-btn" onclick="switchTab('textTab')">📝 Share Text</button>
                  </div>

                  <!-- File Upload Tab -->
                  <div id="fileTab">
                    <div id="dropZone" class="drop-zone">
                      <strong>Drag & drop file here</strong>
                      <div class="hint">or click to browse from device</div>
                      <input id="fileInput" type="file" style="display:none" />
                      <button id="pickButton" class="btn" type="button" style="margin-top:10px;">Choose File</button>
                      <div class="progress-wrap" id="progressWrap">
                        <div class="progress-bar" id="progressBar"></div>
                      </div>
                      <div id="status" class="status">Ready</div>
                    </div>
                  </div>

                  <!-- Share Text Tab -->
                  <div id="textTab" style="display:none;">
                    <textarea id="textInput" placeholder="Type or paste small text / code / link here..."></textarea>
                    <div style="display:flex; justify-content:flex-end;">
                      <button class="btn" onclick="sendText()">Post Text Snippet</button>
                    </div>
                  </div>

                  $messageHtml
                </div>

                <!-- Shared Files Section -->
                <div class="card">
                  <div class="card-title">
                    <span>Shared Files <span class="badge" id="fileCountBadge">${sharedFiles.size}</span></span>
                    <input type="text" class="search-box" id="fileSearch" placeholder="Filter files..." style="width:160px; margin-bottom:0;" oninput="filterFiles()" />
                  </div>
                  <div id="filesContainer">
                    ${buildFileListHtml(sharedFiles)}
                  </div>
                </div>

                <!-- Shared Text Snippets Section -->
                <div class="card">
                  <div class="card-title">
                    <span>Shared Text Notes <span class="badge" id="textCountBadge">${snippets.size}</span></span>
                  </div>
                  <div id="textsContainer">
                    ${buildTextListHtml(snippets)}
                  </div>
                </div>
              </div>

              <!-- Preview Modal -->
              <div id="previewModal" class="modal">
                <div class="modal-content">
                  <span class="modal-close" onclick="closePreview()">&times;</span>
                  <div id="modalBody"></div>
                </div>
              </div>

              <script>
                function switchTab(tabId) {
                  document.getElementById('fileTab').style.display = tabId === 'fileTab' ? 'block' : 'none';
                  document.getElementById('textTab').style.display = tabId === 'textTab' ? 'block' : 'none';
                  const btns = document.querySelectorAll('.tab-btn');
                  btns[0].classList.toggle('active', tabId === 'fileTab');
                  btns[1].classList.toggle('active', tabId === 'textTab');
                }

                const dropZone = document.getElementById('dropZone');
                const fileInput = document.getElementById('fileInput');
                const pickButton = document.getElementById('pickButton');
                const statusEl = document.getElementById('status');
                const progressWrap = document.getElementById('progressWrap');
                const progressBar = document.getElementById('progressBar');

                pickButton.addEventListener('click', () => fileInput.click());
                fileInput.addEventListener('change', () => {
                  if (fileInput.files.length > 0) uploadFile(fileInput.files[0]);
                });

                ['dragenter','dragover'].forEach(e =>
                  dropZone.addEventListener(e, ev => { ev.preventDefault(); dropZone.classList.add('dragover'); })
                );
                ['dragleave','drop'].forEach(e =>
                  dropZone.addEventListener(e, ev => { ev.preventDefault(); dropZone.classList.remove('dragover'); })
                );
                dropZone.addEventListener('drop', ev => {
                  const files = ev.dataTransfer.files;
                  if (files.length > 0) uploadFile(files[0]);
                });

                function uploadFile(file) {
                  statusEl.textContent = 'Uploading ' + file.name + '\u2026';
                  progressWrap.style.display = 'block';
                  progressBar.style.width = '0%';

                  const xhr = new XMLHttpRequest();
                  xhr.open('POST', '/upload');
                  xhr.setRequestHeader('Accept', 'application/json');

                  xhr.upload.addEventListener('progress', ev => {
                    if (ev.lengthComputable) {
                      progressBar.style.width = Math.round(ev.loaded / ev.total * 100) + '%';
                    }
                  });

                  xhr.addEventListener('load', () => {
                    progressWrap.style.display = 'none';
                    if (xhr.status === 200) {
                      statusEl.textContent = 'Uploaded successfully!';
                      fetchFiles();
                    } else {
                      statusEl.textContent = 'Upload failed.';
                    }
                  });
                  xhr.addEventListener('error', () => {
                    statusEl.textContent = 'Upload failed. Check connection.';
                    progressWrap.style.display = 'none';
                  });

                  const data = new FormData();
                  data.append('file', file, file.name);
                  xhr.send(data);
                }

                function sendText() {
                  const input = document.getElementById('textInput');
                  const val = input.value.trim();
                  if (!val) return;

                  fetch('/text', {
                    method: 'POST',
                    headers: {
                      'Content-Type': 'application/x-www-form-urlencoded',
                      'Accept': 'application/json'
                    },
                    body: 'text=' + encodeURIComponent(val)
                  }).then(r => r.json()).then(data => {
                    if (data.success) {
                      input.value = '';
                      fetchTexts();
                    }
                  }).catch(() => alert('Failed to share text snippet.'));
                }

                function copyText(str) {
                  navigator.clipboard.writeText(str).then(() => {
                    alert('Copied to clipboard!');
                  }).catch(() => {
                    const el = document.createElement('textarea');
                    el.value = str; document.body.appendChild(el); el.select();
                    document.execCommand('copy'); document.body.removeChild(el);
                    alert('Copied to clipboard!');
                  });
                }

                function deleteText(id) {
                  fetch('/text/delete?id=' + id, { method: 'POST' })
                    .then(() => fetchTexts());
                }

                function previewFile(url, mime, name) {
                  const modal = document.getElementById('previewModal');
                  const body = document.getElementById('modalBody');
                  body.innerHTML = '';

                  if (mime.startsWith('image/')) {
                    body.innerHTML = '<h3>' + name + '</h3><br><img src="' + url + '" />';
                  } else if (mime.startsWith('video/')) {
                    body.innerHTML = '<h3>' + name + '</h3><br><video src="' + url + '" controls style="max-width:100%; max-height:60vh;"></video>';
                  } else if (mime.startsWith('text/') || mime.includes('json') || mime.includes('javascript')) {
                    fetch(url).then(r => r.text()).then(t => {
                      body.innerHTML = '<h3>' + name + '</h3><br><pre style="text-align:left; background:#1e293b; color:#f8fafc; padding:12px; border-radius:8px; max-height:50vh; overflow:auto;">' + escapeHtml(t) + '</pre>';
                    });
                  } else {
                    window.open(url, '_blank');
                    return;
                  }
                  modal.style.display = 'flex';
                }

                function closePreview() {
                  document.getElementById('previewModal').style.display = 'none';
                }

                function escapeHtml(text) {
                  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
                }

                function formatSize(bytes) {
                  if (bytes < 1024) return bytes + ' B';
                  if (bytes < 1048576) return (bytes/1024).toFixed(1) + ' KB';
                  return (bytes/1048576).toFixed(1) + ' MB';
                }

                function mimeIcon(mime) {
                  if (mime.startsWith('image/')) return '🖼️';
                  if (mime.startsWith('video/')) return '🎬';
                  if (mime.startsWith('audio/')) return '🎵';
                  if (mime === 'application/pdf') return '📄';
                  if (mime.includes('zip') || mime.includes('compressed')) return '🗂️';
                  if (mime.startsWith('text/')) return '📝';
                  return '📁';
                }

                function fetchFiles() {
                  fetch('/files').then(r => r.json()).then(files => {
                    document.getElementById('fileCountBadge').textContent = files.length;
                    let html = '<ul class="item-list">';
                    if (files.length === 0) {
                      html = '<p style="color:var(--text-muted); font-size:.9rem;">No shared files yet.</p>';
                    } else {
                      files.forEach(f => {
                        html += '<li class="list-item" data-name="' + escapeHtml(f.name.toLowerCase()) + '">' +
                          '<span class="item-icon">' + mimeIcon(f.mime) + '</span>' +
                          '<div class="item-info">' +
                            '<div class="item-name" title="' + escapeHtml(f.name) + '">' + escapeHtml(f.name) + '</div>' +
                            '<div class="item-sub">' + formatSize(f.size) + '</div>' +
                          '</div>' +
                          '<div class="item-actions">' +
                            '<button class="btn btn-secondary btn-sm" onclick="previewFile(\'' + f.previewUrl + '\', \'' + f.mime + '\', \'' + escapeHtml(f.name).replace(/'/g, "\\'") + '\')">View</button>' +
                            '<a class="btn btn-sm" href="' + f.url + '" download="' + escapeHtml(f.name) + '">Download</a>' +
                          '</div>' +
                        '</li>';
                      });
                      html += '</ul>';
                    }
                    document.getElementById('filesContainer').innerHTML = html;
                    filterFiles();
                  }).catch(() => {});
                }

                function fetchTexts() {
                  fetch('/texts').then(r => r.json()).then(texts => {
                    document.getElementById('textCountBadge').textContent = texts.length;
                    let html = '';
                    if (texts.length === 0) {
                      html = '<p style="color:var(--text-muted); font-size:.9rem;">No text snippets shared yet.</p>';
                    } else {
                      texts.forEach(t => {
                        const time = new Date(t.timestamp).toLocaleTimeString();
                        html += '<div class="text-card">' +
                          '<div class="text-content">' + escapeHtml(t.text) + '</div>' +
                          '<div class="text-footer">' +
                            '<span>' + time + '</span>' +
                            '<div>' +
                              '<button class="btn btn-secondary btn-sm" onclick="copyText(' + escapeHtml(JSON.stringify(t.text)) + ')">📋 Copy</button>' +
                              '<button class="btn btn-secondary btn-sm" onclick="deleteText(' + t.id + ')" style="color:#ef4444;">🗑️</button>' +
                            '</div>' +
                          '</div>' +
                        '</div>';
                      });
                    }
                    document.getElementById('textsContainer').innerHTML = html;
                  }).catch(() => {});
                }

                function filterFiles() {
                  const q = document.getElementById('fileSearch').value.toLowerCase();
                  const items = document.querySelectorAll('#filesContainer .list-item');
                  items.forEach(el => {
                    const name = el.getAttribute('data-name') || '';
                    el.style.display = name.includes(q) ? 'flex' : 'none';
                  });
                }

                // Poll every 3 seconds
                setInterval(() => {
                  fetchFiles();
                  fetchTexts();
                }, 3000);
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildFileListHtml(sharedFiles: List<SharedFile>): String {
        if (sharedFiles.isEmpty()) {
            return "<p style=\"color:#64748b;font-size:.9rem;\">No shared files yet. Files uploaded or placed in Downloads/HotspotDrop appear here.</p>"
        }

        return buildString {
            append("<ul class=\"item-list\">")
            sharedFiles.forEach { f ->
                val encodedName = TextUtils.htmlEncode(f.displayName)
                val icon = mimeToEmoji(f.mimeType)
                append(
                    """
                    <li class="list-item" data-name="${encodedName.lowercase()}">
                      <span class="item-icon">$icon</span>
                      <div class="item-info">
                        <div class="item-name" title="$encodedName">$encodedName</div>
                        <div class="item-sub">${formatFileSize(f.size)}</div>
                      </div>
                      <div class="item-actions">
                        <button class="btn btn-secondary btn-sm" onclick="previewFile('/preview?id=${f.id}', '${f.mimeType}', '$encodedName')">View</button>
                        <a class="btn btn-sm" href="/download?id=${f.id}" download="$encodedName">Download</a>
                      </div>
                    </li>
                    """.trimIndent(),
                )
            }
            append("</ul>")
        }
    }

    private fun buildTextListHtml(snippets: List<TextSnippet>): String {
        if (snippets.isEmpty()) {
            return "<p style=\"color:#64748b;font-size:.9rem;\">No text snippets shared yet.</p>"
        }

        return buildString {
            snippets.forEach { s ->
                val encodedText = TextUtils.htmlEncode(s.text)
                val jsonEscaped = jsonString(s.text)
                append(
                    """
                    <div class="text-card">
                      <div class="text-content">$encodedText</div>
                      <div class="text-footer">
                        <span>Shared note</span>
                        <div>
                          <button class="btn btn-secondary btn-sm" onclick='copyText($jsonEscaped)'>📋 Copy</button>
                          <button class="btn btn-secondary btn-sm" onclick="deleteText(${s.id})" style="color:#ef4444;">🗑️</button>
                        </div>
                      </div>
                    </div>
                    """.trimIndent(),
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private fun guessMimeType(fileName: String): String =
        URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"

    private fun sanitizeFilename(filename: String): String =
        filename.replace(Regex("""[\\/:*?"<>|]"""), "_")

    private fun formatFileSize(size: Long): String = when {
        size < 1_024 -> "$size B"
        size < 1_048_576 -> "${size / 1_024} KB"
        else -> String.format("%.1f MB", size / 1_048_576.0)
    }

    private fun mimeToEmoji(mime: String): String = when {
        mime.startsWith("image/") -> "🖼️"
        mime.startsWith("video/") -> "🎬"
        mime.startsWith("audio/") -> "🎵"
        mime == "application/pdf" -> "📄"
        mime.contains("zip") || mime.contains("compressed") -> "🗂️"
        mime.startsWith("text/") -> "📝"
        else -> "📁"
    }

    private fun jsonString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    companion object {
        const val SHARED_FOLDER_PATH = "Download/HotspotDrop/"
    }
}

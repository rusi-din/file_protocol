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

class HotspotFileServer(
    private val context: Context,
    port: Int,
    private val onFileSaved: (String) -> Unit,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/" ->
                    htmlResponse(sharedPage())

                session.method == Method.GET && session.uri == "/download" ->
                    handleDownload(session)

                session.method == Method.POST && session.uri == "/upload" ->
                    handleUpload(session)

                // JSON API — returns the file list so clients can integrate programmatically
                session.method == Method.GET && session.uri == "/files" ->
                    jsonFileList()

                else -> newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (error: Exception) {
            htmlResponse(
                sharedPage("Upload failed: ${error.message ?: "Unknown error"}", isError = true),
                Status.INTERNAL_ERROR,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private fun handleUpload(session: IHTTPSession): Response {
        val bodyFiles = HashMap<String, String>()
        session.parseBody(bodyFiles)

        val tempFilePath = bodyFiles["file"]
            ?: return htmlResponse(
                sharedPage("No file was selected.", isError = true),
                Status.BAD_REQUEST,
            )

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
            htmlResponse(sharedPage("Uploaded \u201c$savedName\u201d successfully."))
        } finally {
            tempFile.delete()
        }
    }

    private fun handleDownload(session: IHTTPSession): Response {
        val fileId = session.parameters["id"]?.firstOrNull()?.toLongOrNull()
            ?: return htmlResponse(
                sharedPage("Missing file id.", isError = true),
                Status.BAD_REQUEST,
            )

        val sharedFile = findSharedFile(fileId)
            ?: return htmlResponse(
                sharedPage("File not found or access denied.", isError = true),
                Status.NOT_FOUND,
            )

        val fileUri = android.content.ContentUris.withAppendedId(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            fileId,
        )
        val inputStream = context.contentResolver.openInputStream(fileUri)
            ?: return htmlResponse(
                sharedPage("Could not open the selected file.", isError = true),
                Status.INTERNAL_ERROR,
            )

        return streamDownload(sharedFile.displayName, sharedFile.mimeType, inputStream)
    }

    /** Returns a minimal JSON array so clients/scripts can discover shared files. */
    private fun jsonFileList(): Response {
        val files = listSharedFiles()
        val json = buildString {
            append("[")
            files.forEachIndexed { index, f ->
                if (index > 0) append(",")
                append(
                    """{"id":${f.id},"name":${jsonString(f.displayName)},"size":${f.size},"mime":${jsonString(f.mimeType)},"url":"/download?id=${f.id}"}"""
                )
            }
            append("]")
        }
        return newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", json).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Access-Control-Allow-Origin", "*")
        }
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

    private fun streamDownload(fileName: String, mimeType: String, inputStream: InputStream): Response {
        val safeFileName = fileName.replace("\"", "_")
        return newChunkedResponse(Status.OK, mimeType, inputStream).apply {
            addHeader("Content-Disposition", "attachment; filename=\"$safeFileName\"")
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun htmlResponse(body: String, status: Response.IStatus = Status.OK): Response {
        return newFixedLengthResponse(status, "text/html; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    // -------------------------------------------------------------------------
    // HTML page builders
    // -------------------------------------------------------------------------

    private fun sharedPage(message: String? = null, isError: Boolean = false): String {
        val sharedFiles = listSharedFiles()
        val safeMessage = message?.let { TextUtils.htmlEncode(it) }
        val messageHtml = if (safeMessage == null) {
            ""
        } else {
            val color = if (isError) "#B3261E" else "#1B5E20"
            "<p class=\"banner\" style=\"color:$color;\">$safeMessage</p>"
        }
        val fileListHtml = buildFileListHtml(sharedFiles)
        val fileCountLabel = if (sharedFiles.isEmpty()) "No files yet" else "${sharedFiles.size} file${if (sharedFiles.size == 1) "" else "s"}"

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>Hotspot Drop</title>
              <style>
                *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                  font-family: Arial, sans-serif;
                  background: #f0f4f8;
                  color: #1b1f24;
                  padding: 16px;
                }
                .page { max-width: 560px; margin: 0 auto; }
                h1 { font-size: 1.6rem; margin-bottom: 4px; }
                h2 { font-size: 1.1rem; margin-bottom: 12px; color: #1b1f24; }
                .subtitle { color: #5f6368; font-size: 0.9rem; margin-bottom: 20px; }
                .card {
                  background: #fff;
                  border-radius: 16px;
                  padding: 20px;
                  box-shadow: 0 4px 16px rgba(0,0,0,.08);
                  margin-bottom: 16px;
                }
                .drop-zone {
                  border: 2px dashed #1565c0;
                  border-radius: 12px;
                  padding: 32px 16px;
                  text-align: center;
                  background: #eef5ff;
                  transition: background .15s;
                  cursor: pointer;
                }
                .drop-zone.dragover { background: #dbeaff; }
                .drop-zone strong { display: block; margin-bottom: 6px; }
                .hint { color: #5f6368; font-size: .85rem; margin-top: 6px; }
                .btn {
                  display: inline-block;
                  margin-top: 14px;
                  padding: 10px 20px;
                  border: 0;
                  border-radius: 10px;
                  background: #1565c0;
                  color: #fff;
                  font-size: 1rem;
                  cursor: pointer;
                }
                .progress-bar-wrap {
                  display: none;
                  height: 6px;
                  background: #e0e3e7;
                  border-radius: 3px;
                  margin-top: 12px;
                  overflow: hidden;
                }
                .progress-bar {
                  height: 100%;
                  background: #1565c0;
                  width: 0%;
                  transition: width .2s;
                }
                .status { margin-top: 10px; font-size: .9rem; color: #5f6368; }
                .banner {
                  margin-top: 14px;
                  font-weight: 600;
                  font-size: .95rem;
                }
                /* file list */
                .file-list { list-style: none; }
                .file-item {
                  display: flex;
                  align-items: center;
                  gap: 12px;
                  padding: 10px 0;
                  border-top: 1px solid #e8eaed;
                }
                .file-item:first-child { border-top: 0; }
                .file-icon { font-size: 1.6rem; flex-shrink: 0; }
                .file-info { flex: 1; min-width: 0; }
                .file-name {
                  font-weight: 600;
                  white-space: nowrap;
                  overflow: hidden;
                  text-overflow: ellipsis;
                }
                .file-meta { color: #5f6368; font-size: .82rem; margin-top: 2px; }
                .download-btn {
                  flex-shrink: 0;
                  padding: 6px 14px;
                  border-radius: 8px;
                  background: #1565c0;
                  color: #fff;
                  text-decoration: none;
                  font-size: .85rem;
                  font-weight: 600;
                }
                .count-badge {
                  display: inline-block;
                  background: #e8f0fe;
                  color: #1565c0;
                  font-size: .78rem;
                  font-weight: 600;
                  border-radius: 99px;
                  padding: 2px 10px;
                  margin-left: 8px;
                  vertical-align: middle;
                }
              </style>
            </head>
            <body>
              <div class="page">
                <div class="card">
                  <h1>Hotspot Drop</h1>
                  <p class="subtitle">Upload files to the phone or download shared files from it.</p>

                  <div id="dropZone" class="drop-zone">
                    <strong>Drop a file here</strong>
                    <div class="hint">Files are saved in Downloads/HotspotDrop on the phone.</div>
                    <input id="fileInput" type="file" style="display:none" />
                    <button id="pickButton" class="btn" type="button">Choose File</button>
                    <div class="progress-bar-wrap" id="progressWrap">
                      <div class="progress-bar" id="progressBar"></div>
                    </div>
                    <div id="status" class="status">Waiting for a file\u2026</div>
                  </div>
                  $messageHtml
                </div>

                <div class="card">
                  <h2>Shared Files <span class="count-badge">$fileCountLabel</span></h2>
                  $fileListHtml
                </div>
              </div>

              <script>
                const dropZone   = document.getElementById('dropZone');
                const fileInput  = document.getElementById('fileInput');
                const pickButton = document.getElementById('pickButton');
                const statusEl   = document.getElementById('status');
                const progressWrap = document.getElementById('progressWrap');
                const progressBar  = document.getElementById('progressBar');

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

                  xhr.upload.addEventListener('progress', ev => {
                    if (ev.lengthComputable) {
                      progressBar.style.width = Math.round(ev.loaded / ev.total * 100) + '%';
                    }
                  });

                  xhr.addEventListener('load', () => {
                    document.open(); document.write(xhr.responseText); document.close();
                  });
                  xhr.addEventListener('error', () => {
                    statusEl.textContent = 'Upload failed. Check your connection.';
                    progressWrap.style.display = 'none';
                  });

                  const data = new FormData();
                  data.append('file', file, file.name);
                  xhr.send(data);
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildFileListHtml(sharedFiles: List<SharedFile>): String {
        if (sharedFiles.isEmpty()) {
            return "<p style=\"color:#5f6368;font-size:.9rem;\">No shared files yet. Files you upload from a client, or files placed in Downloads/HotspotDrop, will appear here.</p>"
        }

        return buildString {
            append("<ul class=\"file-list\">")
            sharedFiles.forEach { f ->
                val encodedName = TextUtils.htmlEncode(f.displayName)
                val icon = mimeToEmoji(f.mimeType)
                append(
                    """
                    <li class="file-item">
                      <span class="file-icon">$icon</span>
                      <div class="file-info">
                        <div class="file-name" title="$encodedName">$encodedName</div>
                        <div class="file-meta">${formatFileSize(f.size)} &middot; ${TextUtils.htmlEncode(f.mimeType)}</div>
                      </div>
                      <a class="download-btn" href="/download?id=${f.id}" download="$encodedName">Download</a>
                    </li>
                    """.trimIndent(),
                )
            }
            append("</ul>")
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

    /** Maps broad MIME categories to an emoji for the file list. */
    private fun mimeToEmoji(mime: String): String = when {
        mime.startsWith("image/") -> "\uD83D\uDDBC\uFE0F"   // 🖼️
        mime.startsWith("video/") -> "\uD83C\uDFAC"          // 🎬
        mime.startsWith("audio/") -> "\uD83C\uDFB5"          // 🎵
        mime == "application/pdf" -> "\uD83D\uDCC4"           // 📄
        mime.contains("zip") || mime.contains("compressed") -> "\uD83D\uDDC2\uFE0F" // 🗂️
        mime.startsWith("text/") -> "\uD83D\uDCDD"            // 📝
        else -> "\uD83D\uDCC1"                                 // 📁
    }

    /** Minimal JSON string escaping (only what's needed for display names). */
    private fun jsonString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    // -------------------------------------------------------------------------
    // Data model
    // -------------------------------------------------------------------------

    data class SharedFile(
        val id: Long,
        val displayName: String,
        val size: Long,
        val mimeType: String,
    )

    companion object {
        const val SHARED_FOLDER_PATH = "Download/HotspotDrop/"
    }
}

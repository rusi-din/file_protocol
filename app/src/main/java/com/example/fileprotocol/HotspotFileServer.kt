package com.example.fileprotocol

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.text.TextUtils
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.net.URLConnection

class HotspotFileServer(
    private val context: Context,
    port: Int,
    private val onFileSaved: (String) -> Unit,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/" -> {
                    htmlResponse(uploadPage())
                }

                session.method == Method.POST && session.uri == "/upload" -> {
                    handleUpload(session)
                }

                else -> newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (error: Exception) {
            htmlResponse(
                uploadPage("Upload failed: ${error.message ?: "Unknown error"}", isError = true),
                Status.INTERNAL_ERROR,
            )
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val bodyFiles = HashMap<String, String>()
        session.parseBody(bodyFiles)

        val tempFilePath = bodyFiles["file"]
            ?: return htmlResponse(
                uploadPage("No file was selected.", isError = true),
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
            htmlResponse(uploadPage("Uploaded $savedName successfully."))
        } finally {
            tempFile.delete()
        }
    }

    private fun saveToDownloads(sourceFile: File, displayName: String): String {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, guessMimeType(displayName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/HotspotDrop")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: error("Could not create a file in Downloads.")

        resolver.openOutputStream(targetUri)?.use { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: error("Could not open output stream for upload.")

        resolver.update(
            targetUri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )

        return displayName
    }

    private fun guessMimeType(fileName: String): String {
        return URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
    }

    private fun sanitizeFilename(filename: String): String {
        return filename.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    private fun htmlResponse(body: String, status: Response.IStatus = Status.OK): Response {
        return newFixedLengthResponse(status, "text/html; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun uploadPage(message: String? = null, isError: Boolean = false): String {
        val safeMessage = message?.let { TextUtils.htmlEncode(it) }
        val messageHtml = if (safeMessage == null) {
            ""
        } else {
            val color = if (isError) "#B3261E" else "#1B5E20"
            "<p style=\"margin-top:16px;color:$color;font-weight:600;\">$safeMessage</p>"
        }

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>Hotspot Drop</title>
              <style>
                body {
                  margin: 0;
                  font-family: Arial, sans-serif;
                  background: #f5f7fa;
                  color: #1b1f24;
                }
                .page {
                  max-width: 520px;
                  margin: 0 auto;
                  padding: 24px;
                }
                .card {
                  background: #ffffff;
                  border-radius: 16px;
                  padding: 24px;
                  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.08);
                }
                .drop-zone {
                  border: 2px dashed #1565c0;
                  border-radius: 16px;
                  padding: 40px 20px;
                  text-align: center;
                  background: #eef5ff;
                  transition: background 0.2s ease;
                }
                .drop-zone.dragover {
                  background: #dbeaff;
                }
                .button {
                  margin-top: 16px;
                  padding: 12px 20px;
                  border: 0;
                  border-radius: 10px;
                  background: #1565c0;
                  color: #ffffff;
                  font-size: 16px;
                  cursor: pointer;
                }
                .hint {
                  margin-top: 12px;
                  color: #5f6368;
                  font-size: 14px;
                }
                .status {
                  margin-top: 16px;
                  font-size: 14px;
                  color: #5f6368;
                }
              </style>
            </head>
            <body>
              <div class="page">
                <div class="card">
                  <h1>Hotspot Drop</h1>
                  <p>Drag a file onto this page or tap the button to upload it to the phone.</p>
                  <div id="dropZone" class="drop-zone">
                    <strong>Drop file here</strong>
                    <div class="hint">Files are saved in Downloads/HotspotDrop on the phone.</div>
                    <input id="fileInput" type="file" hidden />
                    <button id="pickButton" class="button" type="button">Choose File</button>
                    <div id="status" class="status">Waiting for a file...</div>
                  </div>
                  $messageHtml
                </div>
              </div>
              <script>
                const dropZone = document.getElementById('dropZone');
                const fileInput = document.getElementById('fileInput');
                const pickButton = document.getElementById('pickButton');
                const status = document.getElementById('status');

                pickButton.addEventListener('click', () => fileInput.click());
                fileInput.addEventListener('change', () => {
                  if (fileInput.files.length > 0) {
                    uploadFile(fileInput.files[0]);
                  }
                });

                ['dragenter', 'dragover'].forEach((eventName) => {
                  dropZone.addEventListener(eventName, (event) => {
                    event.preventDefault();
                    dropZone.classList.add('dragover');
                  });
                });

                ['dragleave', 'drop'].forEach((eventName) => {
                  dropZone.addEventListener(eventName, (event) => {
                    event.preventDefault();
                    dropZone.classList.remove('dragover');
                  });
                });

                dropZone.addEventListener('drop', (event) => {
                  const files = event.dataTransfer.files;
                  if (files.length > 0) {
                    uploadFile(files[0]);
                  }
                });

                async function uploadFile(file) {
                  status.textContent = 'Uploading ' + file.name + '...';
                  const data = new FormData();
                  data.append('file', file, file.name);

                  const response = await fetch('/upload', {
                    method: 'POST',
                    body: data
                  });

                  const html = await response.text();
                  document.open();
                  document.write(html);
                  document.close();
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}

package com.example.fileprotocol

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class HotspotClient(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()

    fun fetchFiles(serverUrl: String, pin: String? = null, callback: (Result<List<HotspotFileServer.SharedFile>>) -> Unit) {
        executor.execute {
            try {
                val cleanUrl = cleanBaseUrl(serverUrl) + "/files"
                val responseStr = makeHttpGet(cleanUrl, pin)
                val jsonArray = JSONArray(responseStr)
                val list = mutableListOf<HotspotFileServer.SharedFile>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(
                        HotspotFileServer.SharedFile(
                            id = obj.getLong("id"),
                            displayName = obj.getString("name"),
                            size = obj.getLong("size"),
                            mimeType = obj.getString("mime"),
                        )
                    )
                }
                callback(Result.success(list))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    fun fetchTexts(serverUrl: String, pin: String? = null, callback: (Result<List<HotspotFileServer.TextSnippet>>) -> Unit) {
        executor.execute {
            try {
                val cleanUrl = cleanBaseUrl(serverUrl) + "/texts"
                val responseStr = makeHttpGet(cleanUrl, pin)
                val jsonArray = JSONArray(responseStr)
                val list = mutableListOf<HotspotFileServer.TextSnippet>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(
                        HotspotFileServer.TextSnippet(
                            id = obj.getLong("id"),
                            text = obj.getString("text"),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        )
                    )
                }
                callback(Result.success(list))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    fun sendText(serverUrl: String, text: String, pin: String? = null, callback: (Result<Boolean>) -> Unit) {
        executor.execute {
            try {
                val cleanUrl = cleanBaseUrl(serverUrl) + "/text"
                val postData = "text=" + java.net.URLEncoder.encode(text, "UTF-8")
                val response = makeHttpPost(cleanUrl, postData, "application/x-www-form-urlencoded", pin)
                val obj = JSONObject(response)
                val success = obj.optBoolean("success", false)
                callback(Result.success(success))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    fun uploadFile(serverUrl: String, fileUri: Uri, pin: String? = null, callback: (Result<Boolean>) -> Unit) {
        executor.execute {
            try {
                val cleanUrl = cleanBaseUrl(serverUrl) + "/upload"
                val fileName = getFileNameFromUri(fileUri) ?: "upload_${System.currentTimeMillis()}"
                val boundary = "*****" + System.currentTimeMillis() + "*****"

                val url = URL(cleanUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 15000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.doInput = true
                conn.useCaches = false
                conn.setRequestProperty("Connection", "Keep-Alive")
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.setRequestProperty("Accept", "application/json")
                if (!pin.isNullOrBlank()) {
                    conn.setRequestProperty("X-Pin", pin)
                    conn.setRequestProperty("Cookie", "auth_pin=$pin")
                }

                val dos = DataOutputStream(conn.outputStream)
                dos.writeBytes("--$boundary\r\n")
                dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
                dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n")

                context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        dos.write(buffer, 0, bytesRead)
                    }
                }

                dos.writeBytes("\r\n--$boundary--\r\n")
                dos.flush()
                dos.close()

                val code = conn.responseCode
                conn.disconnect()
                callback(Result.success(code == 200))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    private fun makeHttpGet(urlString: String, pin: String?): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.requestMethod = "GET"
        if (!pin.isNullOrBlank()) {
            conn.setRequestProperty("X-Pin", pin)
            conn.setRequestProperty("Cookie", "auth_pin=$pin")
        }
        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line)
        }
        reader.close()
        conn.disconnect()
        return sb.toString()
    }

    private fun makeHttpPost(urlString: String, body: String, contentType: String, pin: String?): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", contentType)
        conn.setRequestProperty("Accept", "application/json")
        if (!pin.isNullOrBlank()) {
            conn.setRequestProperty("X-Pin", pin)
            conn.setRequestProperty("Cookie", "auth_pin=$pin")
        }

        conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
        conn.outputStream.flush()
        conn.outputStream.close()

        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        val sb = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            sb.append(line)
        }
        reader.close()
        conn.disconnect()
        return sb.toString()
    }

    private fun cleanBaseUrl(url: String): String {
        var trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://$trimmed"
        }
        return trimmed.removeSuffix("/")
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) name = cursor.getString(idx)
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/') ?: -1
            if (cut != -1) name = name?.substring(cut + 1)
        }
        return name
    }
}

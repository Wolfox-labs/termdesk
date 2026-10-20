package dev.termdesk.app.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder

/**
 * The HTTP half of file download and preview.
 *
 * [AgentClient] keeps ownership of the transfer flow, the last-action banner and
 * the preview state; this class only streams bytes to a file and reports
 * progress, so the socket client stays about the socket.
 */
internal class AgentFileTransfer(
    private val client: OkHttpClient,
    private val context: Context?,
) {

    /** App-private external directory: no storage permission required. */
    fun downloadDir(): File {
        val ctx = context ?: return File("/sdcard/Download")
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun previewDir(): File {
        val ctx = context ?: return File("/data/local/tmp")
        val dir = File(ctx.cacheDir, "preview")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "preview.bin" }

    /**
     * Download [remotePath] into the app's download directory.
     *
     * [onProgress] is only called when the server sent a content length, so an
     * unknown-length body does not report a made-up fraction.
     */
    fun download(
        base: String,
        tok: String,
        remotePath: String,
        fileName: String,
        onProgress: (Float) -> Unit,
    ): File {
        val encoded = URLEncoder.encode(remotePath, "UTF-8")
        val request = Request.Builder()
            .url("$base/download?path=$encoded")
            .header("Authorization", "Bearer $tok")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("empty response body")
            val total = body.contentLength()
            val outFile = File(downloadDir(), fileName)
            body.byteStream().use { input ->
                outFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = input.read(buffer)
                    var done = 0L
                    while (read >= 0) {
                        output.write(buffer, 0, read)
                        done += read
                        // Report progress only when the length is known.
                        if (total > 0) onProgress(done.toFloat() / total)
                        read = input.read(buffer)
                    }
                }
            }
            outFile
        }
    }

    /**
     * Fetch a previewed file into the app cache.
     *
     * The cache, not the download folder: this copy exists only so the platform
     * can decode it, and a viewer must not litter the user's Downloads with
     * every file they glance at.
     */
    fun fetchPreview(base: String, tok: String, entry: FileEntry): File {
        val encoded = URLEncoder.encode(entry.path, "UTF-8")
        val request = Request.Builder()
            .url("$base/download?path=$encoded")
            .header("Authorization", "Bearer $tok")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("响应为空")
            val out = File(previewDir(), safeFileName(entry.name))
            body.byteStream().use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out
        }
    }
}

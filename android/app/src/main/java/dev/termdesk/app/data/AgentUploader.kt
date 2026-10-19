package dev.termdesk.app.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

/** Above this size the upload switches to a chunked session (P5-4). */
private const val SINGLE_SHOT_LIMIT = 32L * 1024 * 1024

/**
 * Per-request body size for chunked uploads. Must stay under the Cloudflare
 * free-tier 100 MB single-request cap; 4 MB also keeps memory use flat on a phone.
 */
private const val CHUNK_BYTES = 4L * 1024 * 1024

/**
 * The HTTP half of a file upload.
 *
 * [AgentClient] keeps ownership of the transfer state, the last-action banner
 * and the listing refresh; this class only moves bytes and reports progress, so
 * the protocol can be tested or replaced without touching the socket client.
 */
internal class AgentUploader(private val client: OkHttpClient) {

    fun displayName(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /** File size when the content resolver exposes one; null means "unknown". */
    fun size(resolver: ContentResolver, uri: Uri): Long? {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
            }
        }.getOrNull()
    }

    /**
     * Upload one content URI, picking one-shot or chunked by [size].
     *
     * Progress is reported through [onProgress] rather than a state holder so the
     * caller decides which flow a phone is watching.
     */
    fun upload(
        base: String,
        tok: String,
        resolver: ContentResolver,
        uri: Uri,
        remotePath: String,
        displayName: String,
        size: Long?,
        onProgress: (TransferState) -> Unit,
    ) {
        if (size != null && size > SINGLE_SHOT_LIMIT) {
            uploadChunked(base, tok, resolver, uri, remotePath, displayName, size, onProgress)
        } else {
            uploadSingleShot(base, tok, resolver, uri, remotePath, displayName, onProgress)
        }
    }

    /** One-shot POST. Fine up to a few tens of MB; larger files use [uploadChunked]. */
    private fun uploadSingleShot(
        base: String,
        tok: String,
        resolver: ContentResolver,
        uri: Uri,
        remotePath: String,
        displayName: String,
        onProgress: (TransferState) -> Unit,
    ) {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("cannot open selected file")
        val encoded = URLEncoder.encode(remotePath, "UTF-8")
        val body = bytes.toRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder()
            .url("$base/upload?path=$encoded&overwrite=1")
            .header("Authorization", "Bearer $tok")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
        }
        onProgress(TransferState(displayName, 1f, "上传完成"))
    }

    /**
     * Chunked upload (P5-4): open a session, PUT each chunk at its offset, commit.
     * Every request body is at most [CHUNK_BYTES], which stays under the
     * Cloudflare free-tier 100 MB single-request limit.
     */
    private fun uploadChunked(
        base: String,
        tok: String,
        resolver: ContentResolver,
        uri: Uri,
        remotePath: String,
        displayName: String,
        size: Long,
        onProgress: (TransferState) -> Unit,
    ) {
        val encodedPath = URLEncoder.encode(remotePath, "UTF-8")
        val openReq = Request.Builder()
            .url("$base/upload/session?path=$encodedPath&size=$size&chunkSize=$CHUNK_BYTES&overwrite=1")
            .header("Authorization", "Bearer $tok")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val opened = client.newCall(openReq).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} 开启分片会话失败")
            JSONObject(response.body?.string() ?: "{}")
        }
        val uploadId = opened.optString("uploadId")
        if (uploadId.isEmpty()) error("会话响应缺少 uploadId")
        val chunkSize = opened.optLong("chunkSize", CHUNK_BYTES)
        val totalChunks = opened.optInt("totalChunks", 0)

        var index = 0
        var sent = 0L
        resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(chunkSize.toInt().coerceAtMost(CHUNK_BYTES.toInt()))
            while (sent < size) {
                val want = minOf(buf.size.toLong(), size - sent).toInt()
                var filled = 0
                while (filled < want) {
                    val n = input.read(buf, filled, want - filled)
                    if (n < 0) break
                    filled += n
                }
                if (filled <= 0) error("输入流提前结束")
                val slice = buf.copyOf(filled)
                val putReq = Request.Builder()
                    .url("$base/upload/session?uploadId=$uploadId&index=$index")
                    .header("Authorization", "Bearer $tok")
                    .put(slice.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                client.newCall(putReq).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code} 分片 $index 失败")
                }
                sent += filled
                index += 1
                if (totalChunks > 0) {
                    onProgress(
                        TransferState(
                            displayName,
                            (sent.toFloat() / size).coerceIn(0f, 1f),
                            "上传中 $index/$totalChunks",
                        ),
                    )
                }
            }
        } ?: error("cannot open selected file")

        val commitReq = Request.Builder()
            .url("$base/upload/session/commit?uploadId=$uploadId")
            .header("Authorization", "Bearer $tok")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(commitReq).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} 提交失败")
        }
    }
}

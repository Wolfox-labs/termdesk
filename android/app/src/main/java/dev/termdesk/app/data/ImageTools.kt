package dev.termdesk.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * 图片处理：在**手机本地**用 Bitmap + Canvas 做完，不经过内核。
 *
 * 为什么不走内核：这件事没有"生成"的成分——压缩、转格式、打水印都是确定性的像素操作。
 * 交给模型只会绕一大圈（还要把图传到电脑再传回来），而手机自己几百毫秒就能做完。
 */
internal object ImageTools {

    /**
     * 真机上出了问题时唯一能自查的地方。
     *
     * 这个功能的失败全在 IO 里，界面上只留一行字；把每一步的异常打出来，
     * 现场就能用 `adb logcat -s TermDeskImage` 一眼看出卡在"读"还是"写"。
     */
    private const val TAG = "TermDeskImage"

    /** 这批图最多保留多少像素：手机上超过这个尺寸既没意义，又容易 OOM。 */
    private const val MAX_EDGE = 2048

    /** 保存到的相簿子目录（相册里显示为 Pictures/TermDesk）。 */
    const val ALBUM = "TermDesk"

    data class Options(
        /** 0..100，默认 80。PNG 用不上它（无损），但接口保持一致。 */
        val quality: Int,
        /** JPG / PNG / WEBP。 */
        val format: String,
        /** 右下角水印文字，空串表示不打。 */
        val watermark: String,
    )

    data class Result(
        /** 成功写进相册的张数。 */
        val saved: Int,
        /** 处理失败（读不出来、写不进去）的张数。 */
        val failed: Int,
        /** 写到哪里：相册的 Pictures/TermDesk，或旧系统上的应用目录。 */
        val where: String,
        /**
         * 失败时的一句话原因，直接写进对话那一行。
         *
         * 以前只有「另有 N 张没能处理」这半句，用户看不出是自己选错了图、还是这个功能坏了；
         * 现在把"读不出来"和"写不进去"分开说，并把细节打到 logcat（TAG=TermDeskImage）。
         */
        val reason: String = "",
        /**
         * 这一批的输入/输出字节数合计（0 表示没量到）。
         *
         * 为什么要它：压缩这件事**肉眼本来就看不出来**——一张 515 KB 的截图压到 203 KB，
         * 画面上几乎一模一样。上一版的结果行只说「成功处理 1 张」，用户看完的结论就是
         * "这功能没干活"。把「515 KB → 203 KB」写出来，做了什么事才看得见。
         */
        val inBytes: Long = 0,
        val outBytes: Long = 0,
    )

    /**
     * 批量处理。
     *
     * 每一张都走 "读 → 转 → 压缩 → 打水印 → 存" 这条链，逐张处理而不是全部读进内存，
     * 所以选 20 张也不会把手机撑爆。[onProgress] 在 IO 线程上被调用，调用方自己切回主线程。
     */
    suspend fun process(
        context: Context,
        uris: List<Uri>,
        options: Options,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        var saved = 0
        var failed = 0
        // 记下**实际**写到的那一处，而不是按系统版本猜。
        var where: String? = null
        // 第一张失败的短原因。写进对话那一行，用户不用连电脑就知道是哪一类问题。
        var reason = ""
        // 体积账：压了多少是这次唯一能"量化"的结果。
        var inBytes = 0L
        var outBytes = 0L
        uris.forEachIndexed { index, uri ->
            val srcSize = sizeOf(uri, context)
            val bitmap = runCatching { decode(uri, context) }
                .onFailure { Log.w(TAG, "解码抛异常 uri=$uri", it) }
                .getOrNull()
            if (bitmap == null) {
                failed += 1
                if (reason.isBlank()) reason = "读不出这张图（URI 打不开或不是图片）"
            } else {
                val name = fileName(options.format, index)
                val written = runCatching { save(context, bitmap, options, name) }
                    .onFailure { Log.w(TAG, "写文件抛异常 name=$name", it) }
                    .getOrNull()
                if (written != null) {
                    saved += 1
                    where = written.where
                    if (srcSize > 0) inBytes += srcSize
                    outBytes += written.bytes
                } else {
                    failed += 1
                    if (reason.isBlank()) reason = "写不进相册，也没写进应用目录"
                }
                bitmap.recycle()
            }
            onProgress(index + 1, uris.size)
        }
        Log.i(
            TAG,
            "处理完成 共 ${uris.size} 张：成功 $saved、失败 $failed、位置 ${where ?: "(无)"}" +
                "、体积 $inBytes → $outBytes" +
                if (reason.isBlank()) "" else "、原因 $reason",
        )
        Result(
            saved = saved,
            failed = failed,
            where = where ?: albumLabel(Build.VERSION.SDK_INT),
            reason = reason,
            inBytes = inBytes,
            outBytes = outBytes,
        )
    }

    /** 读一张图**原来**多少字节；量不到返回 0（不显示体积那一段就是了）。 */
    private fun sizeOf(uri: Uri, context: Context): Long = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
            ?: 0L
    }.getOrDefault(0L)

    /** 旧系统上写不进系统相册（要 WRITE_EXTERNAL_STORAGE），所以退回应用自己的目录。 */
    private fun albumLabel(sdk: Int): String =
        if (sdk >= Build.VERSION_CODES.Q) "相册 Pictures/" + ALBUM
        else "应用文件 Pictures/" + ALBUM

    /**
     * 解码时该用多大的 `inSampleSize`：让长边不超过 [maxEdge]。
     *
     * 抽成纯函数是为了能被单测钉住。上一版的判据是 `while (longEdge / 2 >= MAX_EDGE)`，
     * 读作"折半之后仍然 ≥2048 才缩"——于是 2800px 不缩、4000px 也不缩，
     * 实际放行到 4095px，与注释里写的 2048 不是一回事。这里按"缩完是否还超"判断。
     */
    internal fun sampleFor(longEdge: Int, maxEdge: Int = MAX_EDGE): Int {
        var sample = 1
        while (longEdge / sample > maxEdge) sample *= 2
        return sample
    }

    /**
     * 处理完写进对话里的那一行。
     *
     * 放在这里而不是界面里：它是这个功能的"台词"，措辞该和计数逻辑待在一起，
     * 也才能在纯 JVM 单测里钉住（用户点名要的就是这一句）。
     */
    fun noteText(result: Result): String = buildString {
        // 一张都没成功时不许再报「✅ …已保存至相册」：那是这个功能上一版最误导人的地方
        // （真机上 0 成功 / 1 失败，界面却是一行绿色成功文案）。
        if (result.saved == 0) {
            append("⚠️ 没能处理任何图片（").append(result.failed).append(" 张全部失败）。")
            if (result.reason.isNotBlank()) append("原因：").append(result.reason).append('。')
            append("细节见 logcat 过滤 TermDeskImage。")
            return@buildString
        }
        append("✅ 成功处理 ").append(result.saved).append(" 张图片")
        // 压缩效果肉眼看不出来，所以把体积账写出来：这才是"做了什么"的可见证据。
        if (result.inBytes > 0 && result.outBytes > 0) {
            val pct = (100 - result.outBytes * 100 / result.inBytes).coerceIn(0, 99)
            append("（").append(kb(result.inBytes)).append(" → ").append(kb(result.outBytes))
            if (pct > 0) append("，小了 ").append(pct).append("%")
            append("）")
        }
        append("，已保存至相册。")
        if (result.failed > 0) {
            append("（另有 ").append(result.failed).append(" 张没能处理")
            if (result.reason.isNotBlank()) append("：").append(result.reason)
            append("）")
        }
        append("位置：").append(result.where).append("。")
    }

    /** 字节数说成人话：≥1 MB 用 MB，否则用 KB。 */
    private fun kb(bytes: Long): String =
        if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else "%d KB".format((bytes + 512) / 1024)

    /** 批量处理时的文件名。 */
    fun fileName(format: String, index: Int): String =
        "termdesk-" + System.currentTimeMillis() + "-" + (index + 1) + "." + extensionOf(format)

    /**
     * 把一张**已经画好的图**写进相册。
     *
     * 海报走的就是这里：写相册的兜底链只该有一份（相册 → 公开目录 → 应用目录），
     * 两处各写一遍迟早会有一处忘了处理失败。
     */
    fun export(context: Context, bitmap: Bitmap, name: String): String? =
        runCatching { save(context, bitmap, Options(quality = 96, format = "PNG", watermark = ""), name) }
            .getOrNull()?.where

    /**
     * 读一张图。
     *
     * 两趟读：第一趟只量尺寸（`inJustDecodeBounds`，不分配像素），算出降采样比例；
     * 第二趟才真正解码。一步不落地把原图读进来的话，一张 4000×3000 的照片就是 48MB，
     * 选十张必闪退——这是这个功能最容易出事的地方。
     */
    private fun decode(uri: Uri, context: Context): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // ⚠️ 这一趟只量尺寸：`BitmapFactory.decodeStream` 在 inJustDecodeBounds 模式下
        // **按契约返回 null**（它只填 outWidth/outHeight）。上一版把判空绑在 `use{}` 的
        // 返回值上，于是 elvis 恒触发、`decode()` 对任何图都返回 null，
        // 整个「图片处理」从来没成功过一次。判空必须判**流**，不能判它的返回值。
        val probe = runCatching { context.contentResolver.openInputStream(uri) }
            .onFailure { Log.w(TAG, "打开输入流失败（读权限 / URI 失效）uri=$uri", it) }
            .getOrNull()
        if (probe == null) {
            Log.w(TAG, "输入流为 null uri=$uri")
            return null
        }
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "量不出尺寸 out=${bounds.outWidth}x${bounds.outHeight} uri=$uri")
            return null
        }

        val sample = sampleFor(maxOf(bounds.outWidth, bounds.outHeight))
        Log.d(TAG, "解码 uri=$uri 原图=${bounds.outWidth}x${bounds.outHeight} sample=$sample")

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }.onFailure { Log.w(TAG, "第二趟解码抛异常 uri=$uri", it) }.getOrNull()
        if (decoded == null) {
            Log.w(TAG, "第二趟解码返回 null uri=$uri")
            return null
        }
        return runCatching { applyExifRotation(uri, context, decoded) }
            .onFailure { Log.w(TAG, "EXIF 旋转失败，按原图用 uri=$uri", it) }
            .getOrDefault(decoded)
    }

    /**
     * 按 EXIF 把方向摆正。
     *
     * 不处理的话，竖着拍的照片进相册会躺着——用户只会觉得"这软件把我照片弄歪了"。
     */
    private fun applyExifRotation(uri: Uri, context: Context, src: Bitmap): Bitmap {
        val degrees = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (degrees == 0f) return src
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    private fun compressFormat(format: String): Bitmap.CompressFormat = when (format.uppercase()) {
        "PNG" -> Bitmap.CompressFormat.PNG
        "WEBP" ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
            else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.JPEG
    }

    private fun mimeOf(format: String): String = when (format.uppercase()) {
        "PNG" -> "image/png"
        "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun extensionOf(format: String): String = when (format.uppercase()) {
        "PNG" -> "png"
        "WEBP" -> "webp"
        else -> "jpg"
    }

    /** 打水印：右下角，带描边，深浅底上都能看清。 */
    private fun watermark(bitmap: Bitmap, text: String): Bitmap {
        if (text.isBlank()) return bitmap
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
        val canvas = Canvas(mutable)
        val size = (minOf(mutable.width, mutable.height) / 22f).coerceAtLeast(14f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(3f, 0f, 1f, Color.argb(160, 0, 0, 0))
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 0, 0, 0)
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.STROKE
            strokeWidth = size / 14f
        }
        val pad = size * 0.8f
        val baseline = mutable.height - pad
        val width = fill.measureText(text)
        val x = mutable.width - width - pad
        canvas.drawText(text, x, baseline, stroke)
        canvas.drawText(text, x, baseline, fill)
        if (mutable !== bitmap) bitmap.recycle()
        return mutable
    }

    /** 一次成功的落盘：写到哪了 + 用了多少字节。 */
    private data class Written(val where: String, val bytes: Long)

    /**
     * 写一张图，返回它**实际**写到了哪里、占了多少字节；写不进去返回 null。
     *
     * 失败为什么还要有兜底：本项目的 targetSdk 是 28（为了 Termux 那套本地环境），
     * 在 Android 10 以上会跑在"旧存储模式"里，MediaStore 那条路有可能被拒。
     * 所以顺序是：系统相册 → 相册的公开目录 → 应用自己的图片目录。
     * 三条里总有一条能成，而返回值如实说清是哪一条，用户不会以为图丢了。
     */
    private fun save(
        context: Context,
        bitmap: Bitmap,
        options: Options,
        name: String,
    ): Written? {
        val stamped = watermark(bitmap, options.watermark)
        try {
            val quality = options.quality.coerceIn(1, 100)
            val format = compressFormat(options.format)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val written = runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, mimeOf(options.format))
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + ALBUM)
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: return@runCatching null
                    val streamed = resolver.openOutputStream(uri)?.use { out: OutputStream ->
                        stamped.compress(format, quality, out)
                        true
                    } ?: false
                    if (!streamed) {
                        runCatching { resolver.delete(uri, null, null) }
                        return@runCatching null
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    // 写完回读一次实际大小：这才是"压了多少"的真实数字。
                    val size = runCatching {
                        resolver.query(uri, arrayOf(MediaStore.Images.Media.SIZE), null, null, null)
                            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
                            ?: 0L
                    }.getOrDefault(0L)
                    Written("相册 Pictures/" + ALBUM, size)
                }.onFailure {
                    // targetSdk 28（旧存储模式）+ 未声明 WRITE_EXTERNAL_STORAGE 时，
                    // Android 10+ 会在这一步抛 SecurityException，然后静默退到下面两条兜底。
                    Log.w(TAG, "MediaStore 写入被拒，改用兜底目录", it)
                }.getOrNull()
                if (written != null) return written
            }

            // 旧系统（或上面被拒）：先试相册的公开目录，再退到应用自己的图片目录。
            @Suppress("DEPRECATION")
            val public = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                ALBUM,
            )
            if (public.exists() || public.mkdirs()) {
                val file = File(public, name)
                val ok = runCatching {
                    file.outputStream().use { out -> stamped.compress(format, quality, out) }
                    true
                }.onFailure { Log.w(TAG, "公开相册目录写不进去 path=${file.absolutePath}", it) }
                    .getOrDefault(false)
                if (ok) {
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                    return Written("相册 Pictures/" + ALBUM, file.length())
                }
            }

            val private = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                ALBUM,
            )
            if (!private.exists() && !private.mkdirs()) return null
            val file = File(private, name)
            file.outputStream().use { out -> stamped.compress(format, quality, out) }
            return Written("应用文件 Pictures/" + ALBUM, file.length())
        } catch (e: Exception) {
            Log.w(TAG, "三条落盘路径全部失败 name=$name", e)
            return null
        } finally {
            if (stamped !== bitmap) stamped.recycle()
        }
    }
}

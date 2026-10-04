package dev.termdesk.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.termdesk.app.data.FilePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * The phone's file viewer.
 *
 * Everything here happens on the phone: the PC keeps the file and sends either
 * its text or its bytes. Images are sampled down before decoding and PDF pages
 * are rasterised on demand with the platform renderer, because a phone cannot
 * hold a whole document in memory and must not pretend it can.
 */
@Composable
fun FilePreviewScreen(
    preview: FilePreview,
    onClose: () -> Unit,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    val file = preview.localFile

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    preview.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(kindLabel(preview.kind))
                        if (preview.sizeBytes > 0) append(" · ${formatSize(preview.sizeBytes)}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (file != null) {
                IconButton(onClick = { openWith(context, file) }) {
                    Icon(
                        Icons.Outlined.OpenInNew,
                        contentDescription = "用其他应用打开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onSave) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = "保存到手机",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Box(Modifier.weight(1f)) {
            when {
                preview.message != null -> Message(preview.message)
                preview.loading -> Loading()
                preview.kind == "docx" -> DocxView(preview.text.orEmpty())
                preview.kind == "image" && file != null -> ImageView(file)
                preview.kind == "pdf" && file != null -> PdfView(file)
                else -> Message("这个格式在手机上没有内建查看器，可以保存到手机后用其他应用打开。")
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun Message(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Word text as extracted by the PC: paragraphs, no invented styling. */
@Composable
private fun DocxView(text: String) {
    if (text.isBlank()) {
        Message("这个文档没有可提取的文字。")
        return
    }
    SelectionContainer {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.5f),
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ImageView(file: File) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, 2600) }
    }
    val bmp = bitmap
    if (bmp == null) {
        Message("无法解码这张图片。")
        return
    }
    var scale by remember(file.path) { mutableFloatStateOf(1f) }
    var offset by remember(file.path) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(file.path) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale <= 1.05f) Offset.Zero else offset + pan
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}

@Composable
private fun PdfView(file: File) {
    val context = LocalContext.current
    val renderer by produceState<PdfRenderer?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                PdfRenderer(
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                )
            }.getOrNull()
        }
    }
    val open = renderer
    if (open == null) {
        Message("无法打开这个 PDF。")
        return
    }
    DisposableEffect(open) {
        onDispose { runCatching { open.close() } }
    }

    val pageCount = open.pageCount
    val mutex = remember(open) { Mutex() }
    // Small cache: pages are large and only the visible ones matter.
    val cache = remember(open) { LruCache<Int, Bitmap>(6) }
    val targetWidth = (context.resources.displayMetrics.widthPixels).coerceAtLeast(480)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items((0 until pageCount).toList(), key = { it }) { index ->
            PdfPage(open, index, cache, mutex, targetWidth)
        }
    }
}

@Composable
private fun PdfPage(
    renderer: PdfRenderer,
    index: Int,
    cache: LruCache<Int, Bitmap>,
    mutex: Mutex,
    targetWidth: Int,
) {
    val bitmap by produceState<Bitmap?>(cache.get(index), renderer, index) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                mutex.withLock {
                    cache.get(index) ?: renderPdfPage(renderer, index, targetWidth).also {
                        cache.put(index, it)
                    }
                }
            }
        }
    }
    val bmp = bitmap
    if (bmp == null) {
        Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    } else {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "第 ${index + 1} 页",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        )
    }
}

private fun renderPdfPage(renderer: PdfRenderer, index: Int, targetWidth: Int): Bitmap {
    val page = renderer.openPage(index)
    try {
        val scale = targetWidth.toFloat() / page.width.toFloat()
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
        // Pages are transparent by default; a white sheet reads as paper.
        bitmap.eraseColor(AndroidColor.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    } finally {
        page.close()
    }
}

/**
 * Decode with subsampling.
 *
 * A phone camera photo is 4000px wide; decoding it at full size to show it on a
 * 1080px screen is how a viewer runs out of memory on the second image.
 */
private fun decodeSampled(file: File, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

/** Hand the cached copy to whatever app can show it. */
private fun openWith(context: Context, file: File) {
    runCatching {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeTypeOf(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "打开方式"))
    }
}

private fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "heic", "heif" -> "image/heic"
    "pdf" -> "application/pdf"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "txt", "md", "log", "json", "xml", "csv", "yml", "yaml" -> "text/plain"
    else -> "*/*"
}

private fun kindLabel(kind: String): String = when (kind) {
    "image" -> "图片"
    "pdf" -> "PDF"
    "docx" -> "Word"
    else -> "文件"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

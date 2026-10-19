package dev.termdesk.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dev.termdesk.app.ui.theme.Semantic
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The console's shared furniture.
 *
 * One panel shape, one table header, one state pill. The previous window drew
 * each page with its own spacing, which is why it read as four unrelated
 * screens rather than one console.
 */
@Composable
internal fun ConsoleTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    fun enlarged(style: androidx.compose.ui.text.TextStyle) = style.copy(
        fontSize = style.fontSize * 1.12f,
        lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * 1.12f else style.lineHeight,
    )
    MaterialTheme(
        typography = androidx.compose.material3.Typography(
            displayLarge = enlarged(base.displayLarge),
            displayMedium = enlarged(base.displayMedium),
            displaySmall = enlarged(base.displaySmall),
            headlineLarge = enlarged(base.headlineLarge),
            headlineMedium = enlarged(base.headlineMedium),
            headlineSmall = enlarged(base.headlineSmall),
            titleLarge = enlarged(base.titleLarge),
            titleMedium = enlarged(base.titleMedium),
            titleSmall = enlarged(base.titleSmall),
            bodyLarge = enlarged(base.bodyLarge),
            bodyMedium = enlarged(base.bodyMedium),
            bodySmall = enlarged(base.bodySmall),
            labelLarge = enlarged(base.labelLarge),
            labelMedium = enlarged(base.labelMedium),
            labelSmall = enlarged(base.labelSmall),
        ),
        content = content,
    )
}

@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (title != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    trailing?.invoke(this)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            }
            Column(Modifier.fillMaxSize().padding(contentPadding), content = content)
        }
    }
}

/** One column of a table header, weighted the same way its cells are. */
internal data class Column(val label: String, val weight: Float, val align: TextAlign = TextAlign.Start)

@Composable
internal fun ConsoleTableHeader(columns: List<Column>) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (column in columns) {
            Text(
                column.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(column.weight),
                textAlign = column.align,
                maxLines = 1,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
}

/** A compact state tag. Tint carries the meaning; the text repeats it. */
@Composable
internal fun Pill(text: String, tint: Color, mono: Boolean = false) {
    Surface(color = tint.copy(alpha = 0.13f), shape = RoundedCornerShape(999.dp)) {
        Text(
            text,
            style = if (mono) MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
            else MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun StatusDot(ok: Boolean, size: Dp = 8.dp) {
    Box(
        Modifier.size(size).background(
            if (ok) Semantic.current.success else MaterialTheme.colorScheme.error,
            CircleShape,
        ),
    )
}

/** "codex" is not a product name; this is the label a person recognises. */
internal fun engineLabel(engine: String): String = when (engine) {
    "codex" -> "Codex"
    "dsh" -> "DeepSeek Harness"
    "opencode" -> "OpenCode"
    "mimo" -> "MiMo Code"
    "qoder" -> "QoderWork CN"
    "command-code" -> "Command Code"
    else -> engine.ifBlank { "未知内核" }
}

@Composable
internal fun EngineTag(engine: String) {
    Pill(engineLabel(engine), Semantic.current.info)
}

@Composable
internal fun EmptyState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action?.let {
                Spacer(Modifier.height(2.dp))
                it()
            }
        }
    }
}

@Composable
internal fun KeyValueRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(
            value,
            style = if (mono) MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
            else MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A choose-one chip; used for kernel scopes and lanes. */
@Composable
internal fun ChipButton(selected: Boolean, text: String, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
        ),
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onClick),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            maxLines = 1,
        )
    }
}

@Composable
internal fun NoticeBanner(text: String, isError: Boolean, onClose: () -> Unit) {
    val tint = if (isError) MaterialTheme.colorScheme.error else Semantic.current.success
    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.4f)),
        modifier = Modifier.widthIn(max = 680.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭提示", modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ---- time, paths, clipboard -------------------------------------------------

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateClockFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val isoFormats = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    "yyyy-MM-dd'T'HH:mm:ssXXX",
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    "yyyy-MM-dd'T'HH:mm:ss'Z'",
)

internal fun parseIsoMs(iso: String): Long {
    if (iso.isBlank()) return 0L
    for (pattern in isoFormats) {
        val parsed = runCatching {
            java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                .apply { isLenient = true }
                .parse(iso)
        }.getOrNull()
        if (parsed != null) return parsed.time
    }
    return 0L
}

/** "刚刚 / 12 分钟前 / 今天 21:04 / 10-16 09:12". */
internal fun relativeTime(iso: String): String {
    val ms = parseIsoMs(iso)
    if (ms == 0L) return "时间未知"
    val at = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val now = Instant.now().atZone(ZoneId.systemDefault())
    val minutes = (now.toEpochSecond() - at.toEpochSecond()) / 60
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        at.toLocalDate() == now.toLocalDate() -> "今天 ${clockFormat.format(at)}"
        at.toLocalDate() == now.toLocalDate().minusDays(1) -> "昨天 ${clockFormat.format(at)}"
        at.year == now.year -> dateClockFormat.format(at)
        else -> {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            formatter.format(at)
        }
    }
}

internal fun formatClock(ms: Long): String =
    if (ms <= 0) "—" else clockFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** The last folder of a path, or the whole path when it has none. */
internal fun workspaceName(path: String): String {
    if (path.isBlank()) return "未指定目录"
    val trimmed = path.trimEnd('\\', '/')
    val name = trimmed.substringAfterLast('\\').substringAfterLast('/')
    return name.ifBlank { trimmed }
}

internal fun fileName(path: String): String {
    if (path.isBlank()) return ""
    val name = path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/')
    return name
}

internal fun copyToClipboard(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}

/** A local file picker. Used only where a path is the thing being chosen. */
internal fun pickFile(title: String, mode: Int = java.awt.FileDialog.LOAD): String? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, mode)
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory ?: ".", name).absolutePath
}

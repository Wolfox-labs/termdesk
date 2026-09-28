package dev.termdesk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.termdesk.app.data.HostStatus
import dev.termdesk.app.ui.theme.MeterColor
import dev.termdesk.app.ui.theme.Semantic
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MetricCard(
    label: String,
    value: String,
    caption: String,
    percent: Double,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MeterColor.forPercent(percent),
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        Meter(percent)
        Spacer(Modifier.height(6.dp))
        // Allow up to two lines and shrink the step: at a 145% font scale a long
        // "13.6 GB / 31.7 GB" caption cannot fit on one line in a half-width card,
        // and forcing one line clipped it instead of wrapping readably.
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

@Composable
fun Meter(percent: Double) {
    val fraction = (percent.coerceIn(0.0, 100.0) / 100.0).toFloat()
    Box(
        Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.outline),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(MeterColor.forPercent(percent)),
        )
    }
}

@Composable
fun DiskRow(root: String, percent: Double, caption: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            root,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.width(46.dp),
        )
        Box(Modifier.weight(1f)) { Meter(percent) }
        Spacer(Modifier.width(10.dp))
        // No fixed width here: at a 145% font scale a hard 42dp clipped "80%"
        // onto two lines. The percentage is short, so letting it size itself and
        // keeping it on one line is both correct and simpler.
        Text(
            "${percent.roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MeterColor.forPercent(percent),
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * Right-hand panel: persistent host context, visible from every section.
 * Later phases add process list, service controls and recent file changes here.
 */
@Composable
fun ContextPanel(
    status: HostStatus?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "主机状态",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        if (status == null) {
            Text(
                "等待数据…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        PanelRow("主机名", status.hostname)
        PanelRow("系统", status.platform)
        PanelRow("架构", status.arch)
        PanelRow("运行时长", formatUptime(status.uptimeSeconds))
        PanelRow("CPU", "${formatPercent(status.cpuUsagePercent)} · ${status.cpuCores} 核")
        PanelRow("内存", formatPercent(status.memoryUsedPercent))
        status.disks.forEach { PanelRow("磁盘 ${it.root}", formatPercent(it.usedPercent)) }
    }
}

@Composable
private fun PanelRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---- formatting helpers ----

fun formatPercent(value: Double?): String =
    if (value == null) "—" else String.format(Locale.US, "%.1f%%", value)

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i += 1
    }
    return String.format(Locale.US, if (v >= 100 || i == 0) "%.0f %s" else "%.1f %s", v, units[i])
}

fun formatUptime(seconds: Long): String {
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}天 ${h}小时"
        h > 0 -> "${h}小时 ${m}分"
        else -> "${m}分"
    }
}

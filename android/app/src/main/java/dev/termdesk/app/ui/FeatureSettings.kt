package dev.termdesk.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.FeatureStage
import dev.termdesk.app.data.FeatureState
import dev.termdesk.app.data.Storage

/**
 * 设置里的「功能包」页：五个功能块各自装没装、能不能更新、占多少空间。
 *
 * 和「运行设备与助手」里那块本地内核是一套心思：先显示磁盘上真实的现状，再显示电脑上
 * 有什么可下载的，中间只有一条明确的动作（下载 / 更新 / 移除）。没装不是错误，是正常状态。
 */
@Composable
internal fun FeaturePacksPage(
    packs: Map<String, FeatureState>,
    connected: Boolean,
    onRefresh: () -> Unit,
    onInstall: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    // 固定顺序：和 @ 菜单一致，用户两次看到的是同一个排列。
    val order = listOf("web", "ppt", "poster", "table", "image")
    val rows = packs.values.sortedBy { pack ->
        order.indexOf(pack.id).let { if (it < 0) order.size else it }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "功能包",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (connected) {
                        "模板和预设随包更新，补一套版式不用等新版 App。"
                    } else {
                        "未连接电脑：只能看本机已装的包，下载需要回到主线。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRefresh, enabled = connected) { Text("刷新") }
        }

        if (rows.isEmpty()) {
            Text(
                if (connected) "正在读取电脑上的功能包…" else "没有已安装的功能包。连接电脑后点「刷新」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        rows.forEach { pack ->
            FeaturePackCard(
                pack = pack,
                connected = connected,
                onInstall = { onInstall(pack.id) },
                onRemove = { onRemove(pack.id) },
            )
        }
    }
}

@Composable
private fun FeaturePackCard(
    pack: FeatureState,
    connected: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pack.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val available = pack.available
                Text(
                    text = pack.note.ifBlank {
                        when {
                            pack.installed -> "已安装"
                            available == null -> "电脑上没有这个包"
                            else -> "可以下载"
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (pack.stage) {
                        FeatureStage.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (available != null && available.summary.isNotBlank()) {
                    Text(
                        available.summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (pack.busy) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).height(18.dp), strokeWidth = 2.dp)
            } else if (pack.installed) {
                TextButton(onClick = onRemove) { Text("移除") }
            } else {
                OutlinedButton(onClick = onInstall, enabled = connected && pack.available?.present == true) {
                    Text("下载")
                }
            }
        }

        if (pack.busy) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { pack.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val size = pack.installedBytes.takeIf { it > 0 }
            ?: pack.available?.sizeBytes?.takeIf { it > 0 }
        if (size != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = (if (pack.installed) "已占 " else "包大小 ") + Storage.format(size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    }
}

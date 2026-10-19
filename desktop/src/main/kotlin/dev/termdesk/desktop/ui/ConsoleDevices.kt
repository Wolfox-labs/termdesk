package dev.termdesk.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.desktop.workbench.DeviceRow
import dev.termdesk.desktop.workbench.WorkbenchStore

/**
 * The phones this computer knows.
 *
 * Rows are registry entries, not "connected clients": a phone that is offline
 * still has a name, a credential and a last-seen time, and removing it here is
 * what actually cuts it off.
 */
@Composable
internal fun DevicesPage(
    store: WorkbenchStore,
    onPair: () -> Unit,
    onRename: (DeviceRow) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(top = 14.dp)) {
        if (store.phones.isEmpty()) {
            EmptyState(
                title = if (store.connected) "还没有配对手机" else "连接代理后显示已配对的手机",
                hint = if (store.connected) {
                    "配对码只在这台电脑上生成。用手机扫码后，它会作为独立设备出现在这里，可以单独改名或吊销。"
                } else {
                    "左侧显示当前没有连接上代理；先启动本机代理，或检查端口。"
                },
                action = if (store.connected) {
                    { Button(onClick = onPair) { Text("配对手机") } }
                } else {
                    null
                },
            )
            return@Column
        }

        ConsoleTableHeader(
            listOf(
                Column("手机", 2.6f),
                Column("来源", 0.7f),
                Column("最后在线", 1.3f),
                Column("状态", 1.0f),
                Column("操作", 1.1f),
            ),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(store.phones, key = { it.id }) { phone ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(2.6f)) {
                        Text(
                            phone.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            phone.id,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(Modifier.weight(0.7f)) {
                        Pill(
                            if (phone.source == "relay") "中转" else "本机",
                            if (phone.source == "relay") MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (phone.lastSeenAt.isBlank()) "从未上线" else relativeTime(phone.lastSeenAt),
                        modifier = Modifier.weight(1.3f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Box(Modifier.weight(1.0f)) {
                        when {
                            phone.revoked -> Pill("已吊销", MaterialTheme.colorScheme.error)
                            phone.legacy -> Pill("旧版令牌", MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> Pill("有效", dev.termdesk.app.ui.theme.Semantic.current.success)
                        }
                    }
                    Box(Modifier.weight(1.1f)) {
                        var menu by remember(phone.id) { mutableStateOf(false) }
                        TextButton(onClick = { menu = true }) {
                            Text("操作", style = MaterialTheme.typography.labelSmall)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (phone.legacy) {
                                DropdownMenuItem(
                                    text = { Text("旧版令牌：换主令牌才会失效") },
                                    onClick = { menu = false },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    onClick = { menu = false; onRename(phone) },
                                )
                                DropdownMenuItem(
                                    text = { Text("吊销（保留记录）") },
                                    onClick = { menu = false; store.revokePhone(phone.id) },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("删除记录", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; store.removePhone(phone.id) },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "吊销会立即拒绝这台手机；删除会连记录一起移除。中转设备的名称由中转维护。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { store.loadPhones() }) { Text("刷新", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

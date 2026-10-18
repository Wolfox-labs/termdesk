package dev.termdesk.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.app.ui.theme.Semantic
import dev.termdesk.desktop.agent.AgentStatus
import dev.termdesk.desktop.workbench.WorkbenchStore

/**
 * "Is this machine doing anything, and what does it need from me?"
 *
 * The summary answers that in one screen: what is live, what was recorded
 * recently, whether the agent and its connection are healthy, and which kernels are
 * usable. Everything here is a shortcut into a page that owns the detail.
 */
@Composable
internal fun OverviewPage(
    store: WorkbenchStore,
    status: AgentStatus?,
    onPair: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenKernels: () -> Unit,
    onOpenSessions: () -> Unit,
    onStart: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 1280dp window (2560px at 200%) leaves ~1016dp of content: two columns
        // still fit there, and a single stacked column at that width wastes the
        // screen. Below ~900dp only the stack is honest.
        val wide = maxWidth >= 900.dp
        if (wide) {
            Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1.9f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LivePanel(store, Modifier.weight(1f), onOpenSessions)
                    RecentPanel(store, Modifier.weight(1f), onOpenSessions)
                }
                Column(Modifier.width(400.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (store.approvals.isNotEmpty()) {
                        ApprovalsPanel(store, Modifier.weight(1f, fill = false))
                    }
                    MachinePanel(
                        store = store,
                        status = status,
                        modifier = Modifier.weight(1f),
                        onPair = onPair,
                        onStart = onStart,
                        onOpenDevices = onOpenDevices,
                    )
                    KernelsPanel(store, Modifier.weight(0.9f), onOpenKernels)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { MachinePanel(store, status, Modifier.fillMaxWidth(), onPair, onStart, onOpenDevices, compact = true) }
                if (store.approvals.isNotEmpty()) item { ApprovalsPanel(store, Modifier.fillMaxWidth()) }
                item { LivePanel(store, Modifier.fillMaxWidth().height(260.dp), onOpenSessions) }
                item { RecentPanel(store, Modifier.fillMaxWidth().height(320.dp), onOpenSessions) }
                item { KernelsPanel(store, Modifier.fillMaxWidth().height(300.dp), onOpenKernels) }
            }
        }
    }
}

@Composable
private fun MachinePanel(
    store: WorkbenchStore,
    status: AgentStatus?,
    modifier: Modifier,
    onPair: () -> Unit,
    onStart: () -> Unit,
    onOpenDevices: () -> Unit,
    compact: Boolean = false,
) {
    Panel(
        modifier = modifier,
        title = "这台电脑",
        subtitle = status?.let { it.hostname ?: "本机" } ?: "代理没有响应",
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            KeyValueRow("代理", if (status != null) "运行中 · 已运行 ${uptime(status.uptimeMs)}" else "未连接")
            KeyValueRow(
                "地址",
                buildString {
                    append("127.0.0.1:")
                    append(status?.port ?: 7420)
                    val lan = status?.lanUrls?.firstOrNull()
                    if (!lan.isNullOrBlank()) {
                        append("  ·  ")
                        append(lan)
                    }
                },
                mono = true,
            )
            KeyValueRow("版本", status?.version?.let { "v$it" } ?: "—")
            KeyValueRow(
                "服务器中转",
                when {
                    status == null -> "—"
                    status.connections.relay.connected -> "已连接 ${status.connections.relay.url.orEmpty()}"
                    status.connections.relay.configured -> "连接中或暂不可达（局域网可用）"
                    else -> "未配置（局域网可用）"
                },
            )
            KeyValueRow(
                "手机",
                "${store.phones.count { !it.revoked }} 台有效 · 共 ${store.phones.size} 台",
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (status == null) {
                Button(onClick = onStart) { Text("启动本机代理") }
            } else {
                Button(onClick = onPair) { Text("配对手机") }
                TextButton(onClick = onOpenDevices) { Text("管理设备") }
            }
        }
    }
}

@Composable
private fun LivePanel(store: WorkbenchStore, modifier: Modifier, onOpenSessions: () -> Unit) {
    Panel(
        modifier = modifier,
        title = "正在运行",
        subtitle = if (store.chats.isEmpty()) "这台电脑现在没有正在进行的会话" else "${store.chats.size} 个会话由代理持有",
        trailing = {
            TextButton(onClick = onOpenSessions) { Text("全部", style = MaterialTheme.typography.labelSmall) }
        },
    ) {
        if (store.chats.isEmpty()) {
            EmptyState(
                title = "没有正在进行的会话",
                hint = "在手机上发起对话，或在“会话”页从历史记录继续一条。",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                items(store.chats, key = { it.id }) { chat ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                store.open(chat.id)
                                onOpenSessions()
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatusDot(chat.status == "running")
                        Column(Modifier.weight(1f)) {
                            Text(chat.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                buildString {
                                    append(engineLabel(chat.engine))
                                    if (chat.owner.isNotBlank()) append(" · ${chat.owner} 持有")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Pill(
                            if (chat.status == "running") "进行中" else "空闲",
                            if (chat.status == "running") Semantic.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                }
            }
        }
    }
}

@Composable
private fun RecentPanel(store: WorkbenchStore, modifier: Modifier, onOpenSessions: () -> Unit) {
    val recent = store.sessions.take(8)
    Panel(
        modifier = modifier,
        title = "最近记录",
        subtitle = if (store.sessions.isEmpty()) "没有找到内核写下的历史" else "共 ${store.sessions.size} 条，按时间排序",
        trailing = {
            TextButton(onClick = onOpenSessions) { Text("全部", style = MaterialTheme.typography.labelSmall) }
        },
    ) {
        if (recent.isEmpty()) {
            EmptyState(
                title = "没有历史记录",
                hint = "常用内核会在本机留下会话记录；这里显示它们最近写下的那些。",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(vertical = 2.dp)) {
                items(recent, key = { it.engine + ":" + it.id }) { row ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                store.openRecord(row)
                                onOpenSessions()
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.title.ifBlank { "未命名记录" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${engineLabel(row.engine)} · ${workspaceName(row.cwd)} · ${relativeTime(row.updatedAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!row.canResume) {
                            Pill("只读", MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                }
            }
        }
    }
}

@Composable
private fun KernelsPanel(store: WorkbenchStore, modifier: Modifier, onOpenKernels: () -> Unit) {
    val installed = store.kernels.filter { it.available }
    Panel(
        modifier = modifier,
        title = "内核",
        subtitle = "${installed.count { it.enabled }} 个已启用 · 共 ${installed.size} 个已安装",
        trailing = {
            TextButton(onClick = onOpenKernels) { Text("管理", style = MaterialTheme.typography.labelSmall) }
        },
    ) {
        if (installed.isEmpty()) {
            EmptyState(
                title = "没有检测到可用的内核",
                hint = "到“内核”页重新扫描，或手动指定 Codex / OpenCode 等可执行文件的位置。",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                for (kernel in installed) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusDot(kernel.enabled, size = 7.dp)
                        Text(
                            kernel.label,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (kernel.enabled) kernel.tier else "已禁用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalsPanel(store: WorkbenchStore, modifier: Modifier) {
    Panel(
        modifier = modifier,
        title = "等待确认",
        subtitle = "${store.approvals.size} 个请求需要一个人回答",
    ) {
        LazyColumn(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
            items(store.approvals, key = { it.requestId }) { approval ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(approval.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Text(
                        approval.chatTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        approval.options.take(3).forEach { (id, label) ->
                            TextButton(onClick = { store.approve(approval, id) }) {
                                Text(label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
            }
        }
    }
}

private fun uptime(ms: Long): String {
    val seconds = ms / 1000
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days} 天 ${hours} 小时"
        hours > 0 -> "${hours} 小时 ${minutes} 分"
        else -> "${minutes} 分"
    }
}

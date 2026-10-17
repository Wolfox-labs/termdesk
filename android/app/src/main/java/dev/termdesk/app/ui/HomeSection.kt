package dev.termdesk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.*
import dev.termdesk.app.ui.theme.Semantic

/** The working context and one clear next action, followed by recent work. */
@Composable
fun HomeSection(
    hostname: String,
    connected: Boolean,
    connectionLabel: String,
    status: HostStatus?,
    kernelTarget: String,
    engines: List<KernelInfo>,
    currentEngine: String?,
    onSetKernelTarget: (String) -> Unit,
    onSetEngine: (String?) -> Unit,
    onRefreshEngines: () -> Unit,
    workingDirectory: String,
    onSetWorkingDirectory: (String) -> Unit,
    roots: List<String>,
    workspaces: List<WorkspaceInfo>,
    chats: List<ChatInfo>,
    sessions: List<SessionInfo>,
    onNewChat: () -> Unit,
    canCreateChat: Boolean,
    onOpenChat: (String) -> Unit,
    onOpenSession: (SessionInfo) -> Unit,
    onBrowseFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenSystem: () -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    var assistantPickerOpen by remember { mutableStateOf(false) }
    val local = kernelTarget == "local"
    val detected = if (local) engines.filter { it.available } else engines
    val selectable = detected.filter { it.selectable }
    val chosen = selectable.firstOrNull { it.id == currentEngine } ?: selectable.firstOrNull()
    LaunchedEffect(assistantPickerOpen, connected, kernelTarget) {
        if (assistantPickerOpen && connected) onRefreshEngines()
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("继续你的工作", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("继续会话，也能处理文件和终端。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BlockTitle("运行位置")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TargetChip("远程电脑", Icons.Outlined.Computer, !local, Modifier.weight(1f)) { onSetKernelTarget("remote") }
                TargetChip("本机手机", Icons.Outlined.Smartphone, local, Modifier.weight(1f)) { onSetKernelTarget("local") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (connected) Icons.Outlined.CheckCircle else Icons.Outlined.Info, null,
                    tint = if (connected) Semantic.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        connected && local -> "手机沙盒已就绪"
                        connected -> "已连接 · $hostname"
                        local -> "手机沙盒未就绪 · 可在设置中检查"
                        else -> "连接电脑后，即可在这里继续工作"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ContextRow(Icons.Outlined.AutoAwesome, "助手", chosen?.displayName ?: if (connected) "未检测到可用助手" else "连接后检测", "选择助手", connected) { assistantPickerOpen = true }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .5f))
            ContextRow(Icons.Outlined.FolderOpen, "工作目录", shortPath(workingDirectory), "选择工作目录", connected) { pickerOpen = true }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onNewChat, enabled = canCreateChat, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("新建会话", style = MaterialTheme.typography.titleSmall)
            }
            if (!canCreateChat) {
                Text(
                    when {
                        !connected -> "请先连接运行设备，再开始新会话。"
                        chosen == null -> "尚无可用助手。可重新检测，或在设置中查看接入说明。"
                        else -> "当前暂时无法新建会话，请检查设备状态。"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction("文件", Icons.Outlined.FolderOpen, Modifier.weight(1f), onBrowseFiles)
            QuickAction("终端", Icons.Outlined.Terminal, Modifier.weight(1f), onOpenTerminal)
            QuickAction("系统", Icons.Outlined.Memory, Modifier.weight(1f), onOpenSystem)
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BlockTitle("最近会话", Modifier.weight(1f))
                if (!connected) Text("已缓存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (chats.isEmpty() && sessions.isEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp)) {
                    Text("从第一个会话开始", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text("这里会保留最近的工作，方便下次继续。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                chats.sortedByDescending { it.lastUsedAt }.take(4).forEach { chat ->
                    RecentRow(chat.title.ifBlank { "未命名会话" }, listOf(chat.engine, shortPath(chat.cwd), if (chat.isRunning) "进行中" else "可继续").joinToString(" · ")) { onOpenChat(chat.id) }
                }
                sessions.take(if (chats.isEmpty()) 5 else 3).forEach { session ->
                    RecentRow(session.title?.takeIf { it.isNotBlank() } ?: "未命名会话", listOfNotNull(session.engine, session.cwd?.let { shortPath(it) }, session.updatedAt?.let { shortStamp(it) }).joinToString(" · ")) { onOpenSession(session) }
                }
            }
        }
        if (connected && status != null) {
            Text(
                if (local) "手机沙盒 · 代理内存 ${status.sandboxRssBytes.takeIf { it > 0 }?.let { Storage.format(it) } ?: "—"}"
                else "主机状态 · CPU ${status.cpuUsagePercent?.let { "%.0f%%".format(it) } ?: "—"} · 内存 ${"%.0f%%".format(status.memoryUsedPercent)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (assistantPickerOpen) {
        AlertDialog(
            onDismissRequest = { assistantPickerOpen = false },
            title = { Text(if (local) "选择本机助手" else "选择电脑助手") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("根据当前设备检测；可用不代表已登录或已配置模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selectable.isEmpty()) Text("未检测到可用助手", modifier = Modifier.padding(vertical = 12.dp))
                    selectable.forEach { kernel ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .selectable(kernel.id == chosen?.id, role = Role.RadioButton) { onSetEngine(kernel.id); assistantPickerOpen = false }
                            .padding(vertical = 12.dp, horizontal = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = kernel.id == chosen?.id, onClick = null)
                                Spacer(Modifier.width(10.dp))
                                Text(kernel.displayName, style = MaterialTheme.typography.titleSmall)
                            }
                            kernel.manifest?.let { manifest ->
                                Text(if (manifest.session.resume) "支持历史会话延续" else "支持当前会话内连续对话", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 34.dp))
                            }
                        }
                    }
                    val unavailable = detected.filterNot { it.selectable }
                    if (unavailable.isNotEmpty()) {
                        Text("暂不可用", style = MaterialTheme.typography.labelMedium)
                        unavailable.forEach { kernel ->
                            Text("${kernel.displayName} · ${kernel.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { assistantPickerOpen = false }) { Text("完成") } },
            dismissButton = { TextButton(onClick = onRefreshEngines, enabled = connected) { Text("重新检测") } },
        )
    }
    if (pickerOpen) WorkingDirectoryDialog(workingDirectory,
        (workspaces.map { it.cwd } + roots + workingDirectory).filter { it.isNotBlank() }.distinct(),
        onPick = { onSetWorkingDirectory(it); pickerOpen = false }, onDismiss = { pickerOpen = false })
}

@Composable
private fun BlockTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TargetChip(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(14.dp))
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
        .selectable(selected, role = Role.RadioButton, onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ContextRow(icon: ImageVector, label: String, value: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClickLabel = action, onClick = onClick).heightIn(min = 60.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, action, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(onClick = onClick).padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RecentRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).heightIn(min = 72.dp).padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, "打开会话", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Choosing where the next conversation works.
 *
 * The candidates are the directories this app has actually seen (workspaces
 * derived from recorded sessions, plus the roots the agent allows) — not an
 * invented list — and a path can still be typed by hand.
 */
@Composable
private fun WorkingDirectoryDialog(
    current: String,
    candidates: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("工作目录", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                candidates.take(12).forEach { path ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(path) }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            shortPath(path),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (path == current) {
                            Text(
                                "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("或直接输入路径", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (typed.isNotBlank()) onPick(typed.trim()) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (typed.isNotBlank()) onPick(typed.trim()) },
                enabled = typed.isNotBlank(),
            ) { Text("用这个目录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * "2026-10-05T17:19:04Z" -> "10-05 17:19" — in the phone's own timezone.
 *
 * This used to be a substring of the ISO text (`raw.substring(5, 16)`), which prints whatever
 * clock the sender wrote, and the sender writes UTC. A conversation from a minute ago was
 * therefore dated this afternoon — eight hours early here — which is part of why the owner
 * could not find the conversation they had just been using on the computer. Parsing it is also
 * what makes the two shapes the engines write (with and without a zone) mean one instant.
 */
private fun shortStamp(raw: String): String = formatChatTime(raw)

/** The tail of a path is what tells them apart on a 400dp screen. */
private fun shortPath(path: String): String {    if (path.isBlank()) return "（还没定）"
    val parts = path.trimEnd('\\', '/').split('\\', '/').filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> path
        parts.size == 1 -> parts[0]
        else -> "${parts[parts.size - 2]}/${parts.last()}"
    }
}

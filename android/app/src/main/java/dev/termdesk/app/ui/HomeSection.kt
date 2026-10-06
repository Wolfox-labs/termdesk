package dev.termdesk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.ChatInfo
import dev.termdesk.app.data.HostStatus
import dev.termdesk.app.data.KernelInfo
import dev.termdesk.app.data.SessionInfo
import dev.termdesk.app.data.Storage
import dev.termdesk.app.data.WorkspaceInfo
import dev.termdesk.app.ui.theme.MeterChars

/**
 * The landing page.
 *
 * What it answers, in order: is the computer reachable, what will a new
 * conversation run on, where will it work, and how do I get back into what I was
 * doing. Everything that used to be asked per conversation (which kernel) is a
 * single standing choice here instead.
 */
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
    onOpenChat: (String) -> Unit,
    onOpenSession: (SessionInfo) -> Unit,
    onBrowseFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenSystem: () -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val selectable = engines.filter { it.selectable }
    val unavailable = engines.filterNot { it.selectable }
    val chosen = engines.firstOrNull { it.id == currentEngine && it.selectable }
        ?: selectable.firstOrNull()
    val local = kernelTarget == "local"
    // The off-line banner text belongs to a screen that has nothing else to say;
    // here the state is already legible from the dot and the numbers, so the
    // subtitle only has to name what is on the other end.
    val subtitle = when {
        local && !connected -> "手机沙盒代理未运行"
        local -> "沙盒里的代理已就绪"
        connected -> "已连接"
        else -> connectionLabel
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // ---- where this phone is pointed -------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (connected) MeterChars.ok else MaterialTheme.colorScheme.outline),
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (local) "本机（手机沙盒）" else hostname,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            // Shown for BOTH hosts, and the second line says which one you are
            // looking at. On the PC these are the machine's numbers; in the
            // sandbox they are the sandbox's own, because Android denies an app
            // the system-wide view (measured: /proc/stat -> Permission denied,
            // os.cpus() empty, while /proc/<own pid>/stat and /proc/meminfo are
            // readable). So "内存 61%" and "内存 52.0 MB" mean different things
            // on purpose, and neither is a guess.
            if (status != null && connected) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "CPU ${status.cpuUsagePercent?.let { "%.0f%%".format(it) } ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (local && status.sandboxRssBytes > 0) "内存 ${Storage.format(status.sandboxRssBytes)}"
                        else "内存 ${"%.0f%%".format(status.memoryUsedPercent)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- the standing kernel choice --------------------------------------
        BlockTitle("内核")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TargetChip(
                label = "这台电脑",
                selected = !local,
                enabled = true,
                modifier = Modifier.weight(1f),
                onClick = { onSetKernelTarget("remote") },
            )
            TargetChip(
                label = "本机（沙盒）",
                selected = local,
                enabled = true,
                modifier = Modifier.weight(1f),
                onClick = { onSetKernelTarget("local") },
            )
        }
        Spacer(Modifier.height(8.dp))
        if (!local) {
            when {
                selectable.isEmpty() -> Text(
                    if (connected) "这台电脑上没有可用的 agent 内核" else "还没连上电脑，读不到内核表",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    selectable.forEach { kernel ->
                        EngineRow(
                            kernel = kernel,
                            selected = kernel.id == chosen?.id,
                            onClick = { onSetEngine(kernel.id) },
                        )
                    }
                    if (unavailable.isNotEmpty()) {
                        UnavailableEngines(unavailable)
                    }
                }
            }
        } else {
            // The sandbox is a kernel host of its own: the agent running inside it
            // answers the same `kernels.list` as the PC, so whatever it reports
            // selectable belongs here. This branch used to draw a fixed sentence
            // instead, which hid a working DSH behind "there is no kernel here".
            when {
                selectable.isEmpty() -> Text(
                    "沙盒里还没有可用的 agent 内核：把 opencode / dsh 之类装进文件系统，这里就会出现。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    selectable.forEach { kernel ->
                        EngineRow(
                            kernel = kernel,
                            selected = kernel.id == chosen?.id,
                            onClick = { onSetEngine(kernel.id) },
                        )
                    }
                    if (unavailable.isNotEmpty()) {
                        UnavailableEngines(unavailable)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Text(
                if (chosen == null && !local) "刷新内核表" else "刷新",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onRefreshEngines() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---- where work happens ----------------------------------------------
        BlockTitle("工作目录")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { pickerOpen = true }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    shortPath(workingDirectory),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (workspaces.isNotEmpty()) {
                    Text(
                        "最近用过 ${workspaces.size} 个目录 · 点击更换",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "点击更换",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("更换", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickAction(
                "新建会话",
                Icons.Outlined.ChatBubbleOutline,
                primary = true,
                modifier = Modifier.weight(1.4f),
                onClick = onNewChat,
            )
            QuickAction("文件", Icons.Outlined.FolderOpen, modifier = Modifier.weight(1f), onClick = onBrowseFiles)
            QuickAction("终端", Icons.Outlined.Terminal, modifier = Modifier.weight(1f), onClick = onOpenTerminal)
            QuickAction("系统", Icons.Outlined.Memory, modifier = Modifier.weight(1f), onClick = onOpenSystem)
        }

        Spacer(Modifier.height(16.dp))

        // ---- back into what was already running -------------------------------
        BlockTitle("最近会话")
        if (chats.isEmpty() && sessions.isEmpty()) {
            Text(
                "还没有会话。新建一个，就能在手机上接着电脑里的助手干活。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Live conversations first: they are the ones with a runtime behind
            // them right now. Then what the kernels recorded on disk, which is
            // what "what was I doing on this machine" actually means.
            chats.forEach { chat ->
                RecentRow(
                    title = chat.title.ifBlank { "未命名会话" },
                    subtitle = listOfNotNull(
                        chat.engine.ifBlank { null },
                        shortPath(chat.cwd).ifBlank { null },
                        chat.status.takeIf { it.isNotBlank() && it != "idle" },
                    ).joinToString(" · "),
                    action = "继续",
                    onClick = { onOpenChat(chat.id) },
                )
            }
            sessions.take(if (chats.isEmpty()) 6 else 4).forEach { session ->
                RecentRow(
                    title = session.title?.takeIf { it.isNotBlank() } ?: session.id.take(10),
                    subtitle = listOfNotNull(
                        session.engine,
                        session.cwd?.let { shortPath(it) }?.takeIf { it.isNotBlank() },
                        session.updatedAt?.let { shortStamp(it) },
                    ).joinToString(" · "),
                    action = if (session.canResume) "接上" else "看",
                    onClick = { onOpenSession(session) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (pickerOpen) {
        WorkingDirectoryDialog(
            current = workingDirectory,
            candidates = (workspaces.map { it.cwd } + roots + workingDirectory)
                .filter { it.isNotBlank() }
                .distinct(),
            onPick = {
                onSetWorkingDirectory(it)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/** One line of "this is what was running", live or recorded. */
@Composable
private fun RecentRow(
    title: String,
    subtitle: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            action,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun BlockTitle(text: String) {    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun TargetChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun EngineRow(kernel: KernelInfo, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            kernel.displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (kernel.tier.isNotBlank() && kernel.tier != "native") {
            Text(
                kernel.tier,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The kernels this machine found but cannot drive yet — with the PC's own reason. */
@Composable
private fun UnavailableEngines(engines: List<KernelInfo>) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            if (open) "收起不可用的 ${engines.size} 个" else "另有 ${engines.size} 个不可用 ▾",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        if (open) {
            engines.forEach { kernel ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(
                        kernel.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (kernel.detail.isNotBlank()) {
                        Text(
                            kernel.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (primary) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
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

/** "2026-10-05T17:19:04Z" -> "10-05 17:19": the year is never the question. */
private fun shortStamp(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.length < 16) return trimmed
    return trimmed.substring(5, 16).replace('T', ' ')
}

/** The tail of a path is what tells them apart on a 400dp screen. */
private fun shortPath(path: String): String {    if (path.isBlank()) return "（还没定）"
    val parts = path.trimEnd('\\', '/').split('\\', '/').filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> path
        parts.size == 1 -> parts[0]
        else -> "${parts[parts.size - 2]}/${parts.last()}"
    }
}

package dev.termdesk.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.app.ui.theme.Semantic
import dev.termdesk.desktop.workbench.ChatRow
import dev.termdesk.desktop.workbench.SessionRow
import dev.termdesk.desktop.workbench.WorkbenchStore
import java.io.File

/**
 * Sessions: live runtimes and recorded transcripts, in one master-detail view.
 *
 * The left pane is for finding one (lane → kernel → workspace → row); the right
 * pane is the transcript, with the actions that are actually possible on that
 * kind of session. "继续对话" resumes a past session on the PC — the same call
 * the phone makes — instead of pretending a desktop console holds the chat.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionsPage(store: WorkbenchStore, onDelete: (SessionRow) -> Unit) {
    var lane by remember { mutableStateOf("live") }
    var query by remember { mutableStateOf("") }
    var engine by remember { mutableStateOf<String?>(null) }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }

    val needle = query.trim()
    val chats = store.chats
        .filter { engine == null || it.engine == engine }
        .filter {
            needle.isBlank() ||
                it.title.contains(needle, ignoreCase = true) ||
                it.cwd.contains(needle, ignoreCase = true) ||
                it.engine.contains(needle, ignoreCase = true)
        }
    val records = store.sessions
        .filter { engine == null || it.engine == engine }
        .filter {
            needle.isBlank() ||
                it.title.contains(needle, ignoreCase = true) ||
                it.cwd.contains(needle, ignoreCase = true) ||
                it.engine.contains(needle, ignoreCase = true)
        }
    val engines = remember(store.chats.size, store.sessions.size, engine) {
        (store.chats.map { it.engine } + store.sessions.map { it.engine })
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { engineLabel(it).lowercase() }
    }
    val groups = remember(chats, records, needle, engine) { groupSessions(chats, records) }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(348.dp).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LaneButton("实时", store.chats.size, lane == "live") { lane = "live" }
                LaneButton("历史", store.sessions.size, lane == "records") { lane = "records" }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("搜索标题、目录或内核", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            )
            if (engines.size > 1) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ChipButton(engine == null, "全部", { engine = null })
                    for (id in engines) {
                        ChipButton(engine == id, engineLabel(id), { engine = id })
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            val visibleGroups = groups.filter { if (lane == "live") it.live.isNotEmpty() else it.records.isNotEmpty() }
            if (visibleGroups.isEmpty()) {
                EmptyState(
                    title = when {
                        store.chats.isEmpty() && store.sessions.isEmpty() -> "还没有会话"
                        needle.isNotBlank() || engine != null -> "没有匹配的会话"
                        lane == "live" -> "没有正在进行的会话"
                        else -> "没有历史记录"
                    },
                    hint = when {
                        needle.isNotBlank() || engine != null -> "换一个关键词，或点“全部”取消内核筛选。"
                        lane == "live" -> "手机上开始的对话会出现在这里；也可以从“历史”里继续一条。"
                        else -> "内核会在本机留下会话记录，这里显示它最近写下的那些。"
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    for (group in visibleGroups) {
                        item(key = "h:${group.cwd}") {
                            GroupHeader(
                                group = group,
                                lane = lane,
                                collapsed = group.cwd in collapsed,
                                onToggle = {
                                    collapsed = if (group.cwd in collapsed) collapsed - group.cwd
                                    else collapsed + group.cwd
                                },
                            )
                        }
                        if (group.cwd !in collapsed) {
                            if (lane == "live") {
                                items(group.live, key = { "l:${it.id}" }) { chat ->
                                    LiveRow(
                                        chat = chat,
                                        selected = store.activeId == chat.id && store.detailSource == "chat",
                                        onOpen = { store.open(chat.id) },
                                        onTakeover = { store.takeoverChat(chat.id) },
                                        onClose = { store.closeChat(chat.id) },
                                    )
                                }
                            } else {
                                items(group.records, key = { "r:${it.engine}:${it.id}" }) { record ->
                                    RecordRow(
                                        record = record,
                                        selected = store.activeId == record.id && store.detailSource == "record",
                                        onOpen = { store.openRecord(record) },
                                        onResume = { store.resume(record) },
                                        onDelete = { onDelete(record) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        SessionDetailPane(store, onDelete)
    }
}

@Composable
private fun LaneButton(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupHeader(group: SessionGroup, lane: String, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            if (collapsed) Icons.AutoMirrored.Outlined.KeyboardArrowRight else Icons.Outlined.KeyboardArrowDown,
            contentDescription = if (collapsed) "展开" else "收起",
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(
                workspaceName(group.cwd),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                group.cwd,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            (if (lane == "live") group.live.size else group.records.size).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveRow(chat: ChatRow, selected: Boolean, onOpen: () -> Unit, onTakeover: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
            )
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(chat.status == "running", size = 7.dp)
        Column(Modifier.weight(1f)) {
            Text(chat.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(engineLabel(chat.engine))
                    if (chat.owner.isNotBlank()) append(" · ${chat.owner}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RowMenu {
            DropdownMenuItem(text = { Text("查看正文") }, onClick = { it(); onOpen() })
            DropdownMenuItem(text = { Text("接管") }, onClick = { it(); onTakeover() })
            DropdownMenuItem(
                text = { Text("结束会话", color = MaterialTheme.colorScheme.error) },
                onClick = { it(); onClose() },
            )
        }
    }
}

@Composable
private fun RecordRow(
    record: SessionRow,
    selected: Boolean,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
            )
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                record.title.ifBlank { "未命名记录" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${engineLabel(record.engine)} · ${relativeTime(record.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!record.canResume) {
            Pill("只读", MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RowMenu {
            DropdownMenuItem(text = { Text("查看正文") }, onClick = { it(); onOpen() })
            if (record.canResume) {
                DropdownMenuItem(text = { Text("继续对话") }, onClick = { it(); onResume() })
            }
            DropdownMenuItem(
                text = { Text("删除记录", color = MaterialTheme.colorScheme.error) },
                onClick = { it(); onDelete() },
            )
        }
    }
}

/** A "more" button whose menu items close it before acting. */
@Composable
private fun RowMenu(content: @Composable (dismiss: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "更多", modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            content { open = false }
        }
    }
}

@Composable
private fun SessionDetailPane(store: WorkbenchStore, onDelete: (SessionRow) -> Unit) {
    val activeId = store.activeId
    if (activeId == null) {
        EmptyState(
            title = "选择一条会话",
            hint = "左边是实时会话和历史记录。实时会话可以查看、接管或结束；历史记录可以继续对话、导出或删除。",
            modifier = Modifier.fillMaxSize().padding(32.dp),
        )
        return
    }
    val isLive = store.detailSource == "chat"
    val record = store.sessions.firstOrNull { it.id == activeId && it.engine == store.activeEngine }
    val listState = rememberLazyListState()

    LaunchedEffect(store.activeId) {
        if (isLive) {
            listState.scrollToItem((store.lines.size - 1).coerceAtLeast(0))
        }
    }
    LaunchedEffect(store.lines.size) {
        if (isLive && store.lines.isNotEmpty()) {
            listState.animateScrollToItem(store.lines.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    store.activeTitle.ifBlank { "会话" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Pill(
                    if (isLive) "实时" else "历史记录",
                    if (isLive) Semantic.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { store.closeDetail() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EngineTag(store.activeEngine)
                if (store.activeCwd.isNotBlank()) {
                    Text(
                        store.activeCwd,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable {
                            copyToClipboard(store.activeCwd)
                            store.info = "已复制目录"
                        },
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (isLive) {
                    Text(
                        "${store.lines.size} 条事件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (record != null) {
                    Text(
                        relativeTime(record.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isLive) {
                    if (store.sending) {
                        OutlinedButton(onClick = { store.cancel() }) { Text("停止本回合") }
                    }
                    if (!store.canWrite) {
                        Button(onClick = { store.takeover() }) { Text("接管") }
                    }
                    OutlinedButton(onClick = { store.closeChat(activeId) }, enabled = store.canWrite && store.connected) {
                        Text("结束会话", color = MaterialTheme.colorScheme.error)
                    }
                } else if (record != null) {
                    if (record.canResume) {
                        Button(onClick = { store.resume(record) }) { Text("继续对话") }
                    } else {
                        Text(
                            record.note.ifBlank { "这个内核没有声明可以恢复这条记录" },
                            style = MaterialTheme.typography.labelSmall,
                            color = Semantic.current.warning,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (record.path.isNotBlank() && store.activeDeviceId == "local") {
                        OutlinedButton(onClick = { store.revealPath(record.path) }) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("在文件夹中显示")
                        }
                    }
                    OutlinedButton(onClick = { exportTranscript(store) }) { Text("导出…") }
                }
                Spacer(Modifier.weight(1f))
                if (record != null) {
                    IconButton(onClick = { onDelete(record) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "删除记录",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
        }
        if (isLive && !store.canWrite) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    "${store.ownership.ifBlank { "另一台设备" }} 正在使用这条会话，这里是只读视图。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Semantic.current.warning,
                )
            }
        }
        if (store.detailLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        } else {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                store.detailLoading -> EmptyState(
                    title = "正在读取",
                    hint = if (isLive) "正在向代理请求这个会话的正文…" else "正在让内核重放这条记录…",
                    modifier = Modifier.fillMaxSize(),
                )
                store.lines.isEmpty() -> EmptyState(
                    title = if (store.sessionNote.isNotBlank()) "没有可显示的正文" else "这条记录是空的",
                    hint = store.sessionNote.ifBlank {
                        "内核没有为这条会话留下正文；如果它可以继续，上面的按钮会打开一条新会话。"
                    },
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(store.lines, key = { it.seq }) { line -> TranscriptLine(line) }
                    item {
                        if (store.sessionTruncated) {
                            Text(
                                "只显示了最近 ${store.lines.size} 条，共 ${store.sessionTotal} 条事件。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                        } else if (store.sessionTotal > store.lines.size) {
                            Text(
                                "共 ${store.sessionTotal} 条事件，已显示全部 ${store.lines.size} 条。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
        if (store.sessionNote.isNotBlank() && store.lines.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Text(
                store.sessionNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

private fun exportTranscript(store: WorkbenchStore) {
    val path = pickFile("导出会话", java.awt.FileDialog.SAVE) ?: return
    val text = buildString {
        appendLine("# ${store.activeTitle.ifBlank { "会话" }}")
        appendLine()
        appendLine("- 内核：${engineLabel(store.activeEngine)}")
        if (store.activeCwd.isNotBlank()) appendLine("- 目录：${store.activeCwd}")
        appendLine("- 事件：${store.lines.size}")
        appendLine()
        for (line in store.lines) {
            when {
                line.kind == "message" && line.role == "user" -> appendLine("## 你\n\n${line.text}\n")
                line.kind == "message" -> appendLine("## 助手\n\n${line.text}\n")
                line.kind == "tool" || line.kind == "tool_result" || line.kind == "command" ->
                    appendLine("```\n${line.name.ifBlank { line.kind }}: ${line.text}\n```\n")
                line.kind == "error" -> appendLine("> 错误：${line.text}\n")
                line.text.isNotBlank() -> appendLine(line.text + "\n")
            }
        }
    }
    runCatching { File(path).writeText(text) }
        .onSuccess { store.info = "已导出 ${File(path).name}" }
        .onFailure { store.error = "导出失败：${it.message}" }
}

// ---- grouping ---------------------------------------------------------------

private data class SessionGroup(
    val cwd: String,
    val live: List<ChatRow>,
    val records: List<SessionRow>,
)

private fun groupSessions(chats: List<ChatRow>, records: List<SessionRow>): List<SessionGroup> {
    val keys = (chats.map { it.cwd } + records.map { it.cwd }).distinct()
    return keys.map { key ->
        SessionGroup(
            cwd = key,
            live = chats.filter { it.cwd == key }.sortedByDescending { it.updatedAt },
            records = records.filter { it.cwd == key }.sortedByDescending { parseIsoMs(it.updatedAt) },
        )
    }.sortedWith(
        // Most recent activity first: a console is opened to find what was just
        // happening, and a machine with 338 records has dozens of old workspaces
        // that would otherwise push today's project off the first screen.
        compareByDescending<SessionGroup> { group ->
            maxOf(
                group.live.maxOfOrNull { it.updatedAt } ?: 0L,
                group.records.maxOfOrNull { parseIsoMs(it.updatedAt) } ?: 0L,
            )
        }.thenBy { workspaceName(it.cwd).lowercase() },
    )
}

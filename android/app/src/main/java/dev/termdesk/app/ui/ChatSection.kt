package dev.termdesk.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.termdesk.app.data.ChatEvent
import dev.termdesk.app.data.CodexConfig
import dev.termdesk.app.data.EngineInfo
import dev.termdesk.app.data.ChatInfo
import dev.termdesk.app.data.SessionDetail
import dev.termdesk.app.data.SessionInfo
import dev.termdesk.app.data.WorkspaceInfo
import dev.termdesk.app.ui.theme.Semantic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The conversation view: one column, always.
 *
 * A phone is ~400dp wide, so nothing here is ever shown side by side. The chat
 * index lives in a slide-over drawer that starts closed, and the transcript
 * itself fills the width. Reading is a normal conversation: the user's own
 * words, then the model's answer.
 *
 * What is rendered is the runtime's own event stream, passed through verbatim.
 * Nothing is summarised, re-ranked, or turned into a card: reasoning, tool
 * calls, injected context and the engine's own turn boundaries are all present
 * and labelled, because a conversation the user cannot fully inspect is worse
 * than a dense one.
 */
@Composable
fun ChatSection(
    chats: List<ChatInfo>,
    activeChat: ChatInfo?,
    events: List<ChatEvent>,
    sending: Boolean,
    workspaces: List<WorkspaceInfo>,
    sessions: List<SessionInfo>,
    defaultCwd: String,
    recordedSession: SessionDetail?,
    engines: List<EngineInfo>,
    codexConfig: CodexConfig?,
    onOpenSections: () -> Unit,
    onLoadChats: () -> Unit,
    onCreateChat: (String?) -> Unit,
    onOpenChat: (String) -> Unit,
    onSend: (String, String) -> Unit,
    onCancel: (String) -> Unit,
    onCloseChat: (String) -> Unit,
    onLeaveChat: () -> Unit,
    onConfigureChat: (String, String?, String?) -> Unit,
    onCloseRecorded: () -> Unit,
    onLoadSessions: () -> Unit,
    onOpenSession: (SessionInfo) -> Unit,
    onResumeSession: (SessionDetail) -> Unit,
    connected: Boolean,
) {
    // 0 = live conversations, 1 = recorded history. One list, two states: there
    // is no separate drawer for history any more, because a phone-width screen
    // must not spend a third of its width on a second navigation surface.
    var tab by remember { mutableStateOf(0) }
    // Details are folded away by default: the bar stays one row, and the model /
    // directory / effort line only appears when the user asks for it.
    var detailOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onLoadChats()
        onLoadSessions()
    }

    // Opening a recorded session attaches the kernel to it (metadata-only on the
    // PC), so history and a live conversation become the same view rather than
    // two separate objects. DSH has no resume, so its history stays read-only.
    LaunchedEffect(recordedSession?.engine, recordedSession?.id, connected) {
        val session = recordedSession
        if (session != null && connected && session.engine == "codex") onResumeSession(session)
    }

    // A new subject always starts folded.
    LaunchedEffect(activeChat?.id, recordedSession?.id) { detailOpen = false }

    val open = activeChat != null || recordedSession != null

    // Back walks out of the conversation, not out of the app: fold the detail
    // panel first, then return to the list (or close the recorded session).
    BackHandler(enabled = open) {
        when {
            detailOpen -> detailOpen = false
            recordedSession != null -> onCloseRecorded()
            else -> onLeaveChat()
        }
    }

    Column(Modifier.fillMaxSize()) {
        SessionBar(
            title = when {
                recordedSession != null ->
                    recordedSession.title?.takeIf { it.isNotBlank() } ?: "会话记录"
                activeChat != null -> activeChat.title
                else -> "会话"
            },
            open = open,
            expanded = detailOpen,
            onLeading = {
                when {
                    recordedSession != null -> onCloseRecorded()
                    activeChat != null -> onLeaveChat()
                    else -> onOpenSections()
                }
            },
            onToggleDetail = { detailOpen = !detailOpen },
            onNew = { onCreateChat(defaultCwd) },
            canResume = connected && recordedSession?.engine == "codex" && !sending,
            onResume = { recordedSession?.let(onResumeSession) },
        )

        AnimatedVisibility(
            visible = detailOpen && open,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val chat = activeChat
            val recorded = recordedSession
            if (chat != null) {
                ChatDetailPanel(
                    chat = chat,
                    codexConfig = codexConfig,
                    onConfigure = onConfigureChat,
                )
            } else if (recorded != null) {
                RecordedDetailPanel(recorded)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Box(Modifier.weight(1f)) {
            when {
                recordedSession != null -> {
                    if (recordedSession.engine == "dsh") {
                        Text(
                            "DSH 内核暂未开放可靠的原生恢复；此处保留只读，不伪造上下文。",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RecordedTranscript(recordedSession)
                }
                activeChat != null -> Conversation(
                    chat = activeChat,
                    events = events,
                    sending = sending,
                    onSend = onSend,
                    onCancel = onCancel,
                    connected = connected,
                )
                else -> Column(Modifier.fillMaxSize()) {
                    SessionTabs(
                        tab = tab,
                        chatCount = chats.size,
                        sessionCount = sessions.size,
                        onSelect = { tab = it },
                    )
                    Box(Modifier.weight(1f)) {
                        if (tab == 0) {
                            ChatIndex(
                                chats = chats,
                                defaultCwd = defaultCwd,
                                onOpenChat = onOpenChat,
                                onCloseChat = onCloseChat,
                                onCreateChat = onCreateChat,
                            )
                        } else {
                            SessionIndex(
                                sessions = sessions,
                                onOpenSession = onOpenSession,
                                onLoadSessions = onLoadSessions,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The one bar. At the list level it shows the section and a new-chat action; in
 * a conversation the same row becomes back + title + details, so there is never
 * a second stacked header.
 */
@Composable
private fun SessionBar(
    title: String,
    open: Boolean,
    expanded: Boolean,
    onLeading: () -> Unit,
    onToggleDetail: () -> Unit,
    onNew: () -> Unit,
    canResume: Boolean,
    onResume: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onLeading) {
            Icon(
                imageVector = if (open) Icons.Outlined.ArrowBack else Icons.Outlined.Menu,
                contentDescription = if (open) "返回" else "分区",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        if (canResume) {
            TextButton(onClick = onResume) { Text("继续对话") }
        }
        if (open) {
            IconButton(onClick = onToggleDetail) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp
                    else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起详情" else "展开详情",
                    tint = if (expanded) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            IconButton(onClick = onNew) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "新建对话",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Folded-away facts about the open conversation, plus what the next turn runs on. */
@Composable
private fun ChatDetailPanel(
    chat: ChatInfo,
    codexConfig: CodexConfig?,
    onConfigure: (String, String?, String?) -> Unit,
) {
    val catalog = if (chat.engine == "codex") codexConfig?.models.orEmpty() else emptyList()
    // The thread's own model may not be in the catalog (it can be set outside
    // TermDesk), so it is always offered first — otherwise nothing looks chosen.
    val models = remember(catalog, chat.model) {
        val slugs = catalog.map { it.slug }
        if (chat.model.isNotBlank() && chat.model !in slugs) listOf(chat.model) + slugs else slugs
    }
    val levels = remember(catalog, chat.model) {
        catalog.firstOrNull { it.slug == chat.model }?.reasoningLevels
            ?: catalog.flatMap { it.reasoningLevels }.distinct()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KernelBadge(chat.engine)
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildString {
                    append(chat.model.ifBlank { "内核默认模型" })
                    append(" · 思考 ")
                    append(chat.effort.ifBlank { "默认" })
                    when {
                        chat.isRunning -> append(" · 回复中")
                        chat.ready -> append(" · 就绪")
                        chat.isFailed -> append(" · 失败")
                        else -> append(" · 未启动")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            chat.cwd,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (models.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            InlineChips(
                label = "模型",
                items = models,
                selected = chat.model,
                onSelect = { onConfigure(chat.id, it, chat.effort.ifBlank { null }) },
            )
        }
        if (levels.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            InlineChips(
                label = "思考",
                items = levels,
                selected = chat.effort,
                onSelect = { onConfigure(chat.id, chat.model.ifBlank { null }, it) },
            )
        }
        if (models.isEmpty() && levels.isEmpty() && chat.engine != "codex") {
            Spacer(Modifier.height(4.dp))
            Text(
                "该内核由自己决定模型与强度。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordedDetailPanel(session: SessionDetail) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KernelBadge(session.engine)
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildString {
                    append("${session.totalEvents} 条")
                    if (session.truncated) append("（已截断）")
                    if (session.engine == "dsh") append(" · 只读")
                    else append(" · 可继续对话")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        session.cwd?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Label + horizontally scrollable choices, one line each. */
@Composable
private fun InlineChips(
    label: String,
    items: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(44.dp),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEach { item ->
                val isSelected = item == selected
                Text(
                    text = item,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                        )
                        .clickable { onSelect(item) }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/** Two states of one list: live conversations and recorded history. */
@Composable
private fun SessionTabs(tab: Int, chatCount: Int, sessionCount: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("进行中" to chatCount, "历史" to sessionCount).forEachIndexed { index, (label, count) ->
            val selected = index == tab
            Text(
                text = if (count > 0) "$label $count" else label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Recorded history, listed like conversations because that is what it is. */
@Composable
private fun SessionIndex(
    sessions: List<SessionInfo>,
    onOpenSession: (SessionInfo) -> Unit,
    onLoadSessions: () -> Unit,
) {
    if (sessions.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("没有找到历史会话", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "电脑上记录过的对话会出现在这里，点开即可阅读或继续。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onLoadSessions) { Text("重新扫描") }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sessions, key = { "${it.engine}-${it.id}" }) { session ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onOpenSession(session) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        sessionTitle(session),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KernelBadge(session.engine)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = buildString {
                                session.cwd?.takeIf { it.isNotBlank() }?.let { append(workspaceShortName(it)) }
                                if (isNotEmpty()) append(" · ")
                                append(formatRelativeTime(session.updatedAt))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    Icons.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun RecordedTranscript(session: SessionDetail) {
    if (session.events.isEmpty()) {
        Text(
            "这条会话没有可显示的记录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    // Fresh state per session so switching records always opens at the end.
    val listState = key(session.engine, session.id) { rememberLazyListState() }
    LaunchedEffect(session.engine, session.id) {
        listState.scrollToBottomNow()
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 10.dp, end = 10.dp, top = 10.dp, bottom = 14.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        itemsIndexed(
            session.events,
            key = { index, _ -> "ev-$index" },
        ) { _, event ->
            ChatEventRow(
                ChatEvent(
                    seq = 0,
                    at = 0L,
                    kind = event.kind,
                    role = event.role,
                    text = event.text,
                    name = event.name,
                    state = event.state,
                    exitCode = event.exitCode,
                    sourceKind = null,
                    streaming = false,
                ),
            )
        }
    }
}

@Composable
private fun KernelBadge(engine: String?) {
    val text = when (engine?.lowercase()) {
        null, "" -> return
        "codex" -> "CODEX"
        "dsh" -> "DSH"
        else -> engine.uppercase()
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Index of live chats, shown when no conversation is open.
 *
 * Live chats are not the same thing as recorded sessions: a session is history
 * on disk, a chat is a runtime that can still be talked to. They are listed
 * separately so the distinction stays visible.
 */
@Composable
private fun ChatIndex(
    chats: List<ChatInfo>,
    defaultCwd: String,
    onOpenChat: (String) -> Unit,
    onCloseChat: (String) -> Unit,
    onCreateChat: (String?) -> Unit,
) {
    if (chats.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("还没有进行中的对话", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "在 $defaultCwd 中新建一个，就能在手机上和电脑里的助手连续对话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { onCreateChat(defaultCwd) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("新建对话", style = MaterialTheme.typography.labelLarge)
            }
        }
        return
    }

    // Newest first: the conversation the user just left is the one they want.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chats, key = { it.id }) { chat ->
            ChatRow(chat = chat, onOpen = { onOpenChat(chat.id) }, onClose = { onCloseChat(chat.id) })
        }
    }
}

@Composable
private fun ChatRow(chat: ChatInfo, onOpen: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(chat)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chat.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${chat.cwd} · ${chat.eventCount} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatChatTime(chat.lastUsedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "关闭会话",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun StatusDot(chat: ChatInfo) {
    val color = when {
        chat.isRunning -> MaterialTheme.colorScheme.primary
        chat.isFailed -> MaterialTheme.colorScheme.error
        chat.ready -> Semantic.current.success
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** The conversation itself: transcript above, composer pinned below. */
@Composable
private fun Conversation(
    chat: ChatInfo,
    events: List<ChatEvent>,
    sending: Boolean,
    onSend: (String, String) -> Unit,
    onCancel: (String) -> Unit,
    connected: Boolean,
) {
    // Fresh state per chat: opening or switching a conversation always lands at
    // its end, never at a leftover scroll offset from the previous one.
    val listState = key(chat.id) { rememberLazyListState() }
    var draft by remember(chat.id) { mutableStateOf("") }
    // Follow the stream only while the user is already at (or has returned to)
    // the bottom. Scrolling up hands control back to the reader immediately.
    var stickToBottom by remember(chat.id) { mutableStateOf(true) }
    var programmaticScroll by remember(chat.id) { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && !programmaticScroll) {
                    stickToBottom = listState.isNearBottom()
                }
            }
    }

    val lastSeq = events.lastOrNull()?.seq ?: 0
    val lastLen = events.lastOrNull()?.text?.length ?: 0

    // Open / switch: snap to the last item's bottom (not merely its top).
    LaunchedEffect(chat.id) {
        programmaticScroll = true
        listState.scrollToBottomNow()
        programmaticScroll = false
        stickToBottom = true
    }

    // Stream follow: only when the reader is still parked at the end.
    LaunchedEffect(chat.id, lastSeq, lastLen, events.size) {
        if (events.isEmpty() || !stickToBottom) return@LaunchedEffect
        programmaticScroll = true
        listState.animateScrollToBottom()
        programmaticScroll = false
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (events.isEmpty()) {
                Text(
                    "还没有内容。发一条消息开始对话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(events, key = { it.seq }) { event -> ChatEventRow(event) }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            sending = sending,
            running = chat.isRunning,
            canSend = connected && (chat.ready || chat.isRunning || chat.status != "stopped"),
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    onSend(chat.id, text)
                    draft = ""
                }
            },
            onCancel = { onCancel(chat.id) },
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    running: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("说点什么…", style = MaterialTheme.typography.bodyMedium) },
            textStyle = MaterialTheme.typography.bodyMedium,
            maxLines = 5,
            shape = RoundedCornerShape(11.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            keyboardActions = KeyboardActions(),
        )
        Spacer(Modifier.width(6.dp))
        // While a reply is streaming the same button becomes "stop", because
        // sending a second prompt mid-turn would be rejected by the agent.
        if (running) {
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Outlined.Stop,
                    contentDescription = "停止",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            IconButton(onClick = onSend, enabled = canSend && draft.isNotBlank() && !sending) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(19.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Outlined.Send,
                        contentDescription = "发送",
                        tint = if (canSend && draft.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                }
            }
        }
    }
}

/**
 * One line of the conversation.
 *
 * Alignment and colour carry the speaker, so no avatars and no bubbles are
 * needed: the user's own words sit right, the model's answer sits left, and
 * everything the engine did around it is indented as engine output. The user
 * explicitly did not want a chat-bubble look, so the transcript reads as
 * annotated text rather than a messaging app.
 */
@Composable
private fun ChatEventRow(event: ChatEvent) {
    when {
        event.kind == "turn" -> TurnMarker(event)
        event.kind == "step" -> Spacer(Modifier.height(0.dp))
        event.isUser -> UserLine(event)
        event.isAssistant -> AssistantLine(event)
        event.isReasoning -> ReasoningLine(event)
        event.isInjectedContext -> ContextLine(event)
        event.isTool -> ToolLine(event)
        event.isCommand -> CommandLine(event)
        event.isError -> ErrorLine(event)
        event.isLocal || event.isEngineLog -> EngineNoteLine(event)
        event.kind == "engine_plan" -> EngineOutputLine(event, label = "计划")
        event.kind == "engine_summary" -> EngineOutputLine(event, label = "摘要")
        event.kind == "usage" -> EngineOutputLine(event, label = "用量")
        event.hasText -> PlainLine(event)
        else -> Spacer(Modifier.height(0.dp))
    }
}

@Composable
private fun UserLine(event: ChatEvent) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            MarkdownText(
                text = event.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AssistantLine(event: ChatEvent) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(0.98f)) {
            MarkdownText(
                text = event.text,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (event.streaming) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "▍",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ReasoningLine(event: ChatEvent) {
    EngineBlock(
        label = "思考",
        text = event.text,
        accent = MaterialTheme.colorScheme.onSurfaceVariant,
        streaming = event.streaming,
    )
}

/**
 * Context the runtime injected into its own conversation.
 *
 * Labelled explicitly, because it is neither the user's message nor the model's
 * answer and must not be mistaken for either.
 */
@Composable
private fun ContextLine(event: ChatEvent) {
    val source = event.sourceKind ?: "context"
    EngineBlock(
        label = "上下文 · $source",
        text = event.text,
        accent = Semantic.current.info,
        streaming = event.streaming,
    )
}

@Composable
private fun ToolLine(event: ChatEvent) {
    EngineBlock(
        label = if (event.kind == "tool") (event.name ?: "工具") else "工具结果",
        text = event.text,
        accent = Semantic.current.syntaxKeyword,
        streaming = event.streaming,
    )
}

@Composable
private fun CommandLine(event: ChatEvent) {
    val label = buildString {
        append("命令")
        event.exitCode?.let { append(" · 退出码 $it") }
    }
    EngineBlock(
        label = label,
        text = event.text,
        accent = Semantic.current.syntaxString,
        streaming = event.streaming,
    )
}

@Composable
private fun ErrorLine(event: ChatEvent) {
    EngineBlock(
        label = "错误",
        text = event.text,
        accent = MaterialTheme.colorScheme.error,
        streaming = event.streaming,
    )
}

/** Engine plan / summary / usage, shown verbatim and collapsed like other engine output. */
@Composable
private fun EngineOutputLine(event: ChatEvent, label: String) {
    EngineBlock(
        label = label,
        text = event.text,
        accent = Semantic.current.syntaxType,
        streaming = event.streaming,
    )
}

@Composable
private fun EngineNoteLine(event: ChatEvent) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            event.text.ifBlank { event.kind },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlainLine(event: ChatEvent) {
    MarkdownText(
        text = event.text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Engine output: monospaced, indented, with a coloured rail and its own label.
 *
 * Collapsed by default so a long thinking trace or tool dump never buries the
 * answer. The header is one summary line (label + line/character count) with an
 * expand arrow; tapping it toggles the body. A streaming block stays open while
 * it is still growing, and collapses again once the stream ends.
 */
@Composable
private fun EngineBlock(
    label: String,
    text: String,
    accent: Color,
    streaming: Boolean = false,
) {
    var expanded by remember { mutableStateOf(streaming) }
    // The block that was watched live folds itself up when the stream ends.
    LaunchedEffect(streaming) {
        if (!streaming) expanded = false
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = if (expanded) {
                    Icons.Outlined.KeyboardArrowDown
                } else {
                    Icons.Outlined.KeyboardArrowRight
                },
                contentDescription = if (expanded) "收起" else "展开",
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                engineSummaryLabel(label, text),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (streaming) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "生成中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        if (expanded && text.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(20.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One-line fold summary: label plus a size hint (lines when multi-line, else characters). */
private fun engineSummaryLabel(label: String, text: String): String {
    if (text.isBlank()) return label
    val lines = text.count { it == '\n' } + 1
    return if (lines > 1) "$label · $lines 行" else "$label · ${text.length} 字"
}

/**
 * The engine's own turn boundary, drawn as a thin rule.
 *
 * Kept visible rather than hidden: seeing where a turn ended is how the user
 * tells "still thinking" from "finished".
 */
@Composable
private fun TurnMarker(event: ChatEvent) {
    val reason = event.state
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = if (reason == null) " 完成 " else " 完成 · $reason ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** A recorded session's own title, falling back to its id when it has none. */
private fun sessionTitle(session: SessionInfo): String =
    session.title?.takeIf { it.isNotBlank() } ?: session.id.take(18)

/**
 * Scroll so the last item's bottom edge sits at the viewport bottom.
 *
 * `animateScrollToItem` only pins the item's top, which leaves a tall final
 * message cut off — the opposite of what opening a conversation should do.
 */
private suspend fun LazyListState.animateScrollToBottom() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    animateScrollToItem(lastIndex)
    alignLastItemBottom(animated = true)
}

/** Same as [animateScrollToBottom], but without the animation — used when a chat opens. */
private suspend fun LazyListState.scrollToBottomNow() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    scrollToItem(lastIndex)
    alignLastItemBottom(animated = false)
}

private suspend fun LazyListState.alignLastItemBottom(animated: Boolean) {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull { it.index == info.totalItemsCount - 1 }
        ?: info.visibleItemsInfo.lastOrNull()
        ?: return
    val remaining = (last.offset + last.size) - info.viewportEndOffset
    if (remaining <= 0f) return
    if (animated) animateScrollBy(remaining.toFloat()) else scrollBy(remaining.toFloat())
}

/** True when the list end is within [thresholdPx] of the viewport bottom. */
private fun LazyListState.isNearBottom(thresholdPx: Int = 160): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    val remaining = (last.offset + last.size) - info.viewportEndOffset
    return remaining <= thresholdPx
}

/** Short display name for a working directory: its last meaningful path segment. */
private fun workspaceShortName(cwd: String): String {
    val normalized = cwd.replace('\\', '/').trimEnd('/')
    if (normalized.isBlank()) return cwd.ifBlank { "未命名" }
    return normalized.substringAfterLast('/').ifBlank { normalized }
}

/**
 * Format an ISO-8601 timestamp for the drawer.
 *
 * `javax.xml.bind` is not on Android, so the portable parsers are tried in
 * order of how much of the timestamp they need.
 */
private fun parseIsoDate(iso: String?): Date? {
    if (iso.isNullOrBlank()) return null
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
    )
    for (pattern in patterns) {
        val parsed = runCatching {
            SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }.parse(iso)
        }.getOrNull()
        if (parsed != null) return parsed
    }
    return null
}

private fun formatChatTime(iso: String?): String {
    val date = parseIsoDate(iso)
    if (date != null) return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(date)
    // Last resort: show the date part rather than nothing.
    if (iso.isNullOrBlank()) return "—"
    return iso.take(16).replace('T', ' ')
}

/** "刚刚 / 12 分钟前 / 3 小时前 / 2 天前 / MM-dd" — the drawer's session timestamps. */
private fun formatRelativeTime(iso: String?): String {
    val date = parseIsoDate(iso) ?: return formatChatTime(iso)
    val diff = System.currentTimeMillis() - date.time
    if (diff < 0) return formatChatTime(iso)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 7 * day -> "${diff / day} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.US).format(date)
    }
}

private fun formatChatTime(epochMs: Long): String {
    if (epochMs <= 0) return "—"
    return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(epochMs))
}

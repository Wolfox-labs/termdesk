package dev.termdesk.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.termdesk.app.data.ChatEvent
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
    onLoadChats: () -> Unit,
    onCreateChat: (String?) -> Unit,
    onOpenChat: (String) -> Unit,
    onSend: (String, String) -> Unit,
    onCancel: (String) -> Unit,
    onCloseChat: (String) -> Unit,
    onLeaveChat: () -> Unit,
    onCloseRecorded: () -> Unit,
    onLoadSessions: () -> Unit,
    onOpenSession: (SessionInfo) -> Unit,
) {
    var drawerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onLoadChats()
        onLoadSessions()
    }

    Box(Modifier.fillMaxSize()) {
        // One column: the conversation, or the index when nothing is open.
        Column(Modifier.fillMaxSize()) {
            // A recorded session takes over the view when one is picked from the
            // index: it is history, so it is shown read-only.
            if (recordedSession != null) {
                RecordedSessionHeader(session = recordedSession, onClose = onCloseRecorded)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                RecordedTranscript(recordedSession)
            } else {
                ChatHeader(
                    activeChat = activeChat,
                    onMenu = { drawerOpen = true },
                    onNew = { onCreateChat(defaultCwd) },
                    onLeave = onLeaveChat,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                if (activeChat == null) {
                    ChatIndex(
                        chats = chats,
                        defaultCwd = defaultCwd,
                        onOpenChat = onOpenChat,
                        onCloseChat = onCloseChat,
                        onCreateChat = onCreateChat,
                    )
                } else {
                    Conversation(
                        chat = activeChat,
                        events = events,
                        sending = sending,
                        onSend = onSend,
                        onCancel = onCancel,
                    )
                }
            }
        }

        // Scrim over the conversation; tapping it dismisses the drawer.
        AnimatedVisibility(visible = drawerOpen, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { drawerOpen = false },
            )
        }

        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            ChatDrawer(
                workspaces = workspaces,
                sessions = sessions,
                chats = chats,
                activeChatId = activeChat?.id,
                onOpenChat = { onOpenChat(it); drawerOpen = false },
                onOpenSession = { onOpenSession(it); drawerOpen = false },
                onCreateChat = { onCreateChat(it); drawerOpen = false },
                onLoadSessions = onLoadSessions,
                onClose = { drawerOpen = false },
            )
        }
    }
}

@Composable
private fun RecordedSessionHeader(session: SessionDetail, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                Icons.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                session.title?.takeIf { it.isNotBlank() } ?: "会话记录",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(session.engine)
                    session.cwd?.let { append(" · $it") }
                    append(" · ${session.totalEvents} 条")
                    if (session.truncated) append("（已截断）")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A session recorded on disk, shown read-only.
 *
 * Recorded events use the stored vocabulary rather than the live chat kinds, so
 * they are mapped onto the same rows. Nothing is rewritten: this is the engine's
 * own record, which is exactly what the user asked to see.
 */
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 10.dp, end = 10.dp, top = 10.dp, bottom = 14.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        items(session.events, key = { it.hashCode() }) { event ->
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
private fun ChatHeader(
    activeChat: ChatInfo?,
    onMenu: () -> Unit,
    onNew: () -> Unit,
    onLeave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) {
            Icon(
                Icons.Outlined.Menu,
                contentDescription = "会话列表",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (activeChat != null) {
            IconButton(onClick = onLeave) {
                Icon(
                    Icons.Outlined.ArrowBack,
                    contentDescription = "返回列表",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = activeChat?.title ?: "会话",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = activeChat?.let { chat ->
                buildString {
                    append(chat.model.ifBlank { chat.provider })
                    when {
                        chat.isRunning -> append(" · 回复中")
                        chat.ready -> append(" · 就绪")
                        chat.isFailed -> append(" · 失败")
                        else -> append(" · 未启动")
                    }
                }
            } ?: "选择或新建一个对话"
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onNew) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = "新建会话",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
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
) {
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

    // Follow the newest line as the answer streams in. Keyed on the last event
    // so a growing answer keeps the view pinned, which is what a conversation
    // should do; the user can still scroll away between updates.
    val lastSeq = events.lastOrNull()?.seq ?: 0
    val lastLen = events.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(lastSeq, lastLen, events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex)
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
            canSend = chat.ready || chat.isRunning || chat.status != "stopped",
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
            Text(
                event.text,
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
            Text(
                event.text,
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
    )
}

@Composable
private fun ToolLine(event: ChatEvent) {
    EngineBlock(
        label = if (event.kind == "tool") (event.name ?: "工具") else "工具结果",
        text = event.text,
        accent = Semantic.current.syntaxKeyword,
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
    )
}

@Composable
private fun ErrorLine(event: ChatEvent) {
    EngineBlock(
        label = "错误",
        text = event.text,
        accent = MaterialTheme.colorScheme.error,
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
    Text(
        event.text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Engine output: monospaced, indented, with a coloured rail and its own label. */
@Composable
private fun EngineBlock(label: String, text: String, accent: Color) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(2.dp)
                .height(if (text.isBlank()) 14.dp else 18.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(accent),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (text.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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

/**
 * Collapsible sidebar: a directory index of workspaces plus the recorded
 * sessions inside them, and the live chats.
 *
 * It starts closed and slides over the conversation, because a permanently
 * visible sidebar would cut a 400dp phone screen to roughly half.
 */
@Composable
private fun ChatDrawer(
    workspaces: List<WorkspaceInfo>,
    sessions: List<SessionInfo>,
    chats: List<ChatInfo>,
    activeChatId: String?,
    onOpenChat: (String) -> Unit,
    onOpenSession: (SessionInfo) -> Unit,
    onCreateChat: (String) -> Unit,
    onLoadSessions: () -> Unit,
    onClose: () -> Unit,
) {
    var expandedCwd by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "会话",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onLoadSessions, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp, end = 8.dp, bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (chats.isNotEmpty()) {
                item { DrawerHeading("进行中") }
                items(chats, key = { "chat-${it.id}" }) { chat ->
                    DrawerRow(
                        title = chat.title,
                        subtitle = chat.model.ifBlank { chat.cwd },
                        selected = chat.id == activeChatId,
                        dot = when {
                            chat.isRunning -> MaterialTheme.colorScheme.primary
                            chat.isFailed -> MaterialTheme.colorScheme.error
                            chat.ready -> Semantic.current.success
                            else -> MaterialTheme.colorScheme.outline
                        },
                        onClick = { onOpenChat(chat.id) },
                    )
                }
            }

            item { DrawerHeading("工作目录") }
            items(workspaces, key = { "ws-${it.cwd}" }) { ws ->
                val expanded = expandedCwd == ws.cwd
                Column {
                    DrawerRow(
                        title = ws.cwd,
                        subtitle = "${ws.count} 个会话 · ${ws.engines.joinToString("+")}",
                        selected = false,
                        icon = true,
                        onClick = {
                            expandedCwd = if (expanded) null else ws.cwd
                            if (!expanded) onLoadSessions()
                        },
                    )
                    if (expanded) {
                        sessions
                            .filter { it.cwd == ws.cwd }
                            .take(60)
                            .forEach { session ->
                                DrawerRow(
                                    title = sessionTitle(session),
                                    subtitle = "${session.engine} · ${formatChatTime(session.updatedAt)}",
                                    selected = false,
                                    indented = true,
                                    onClick = { onOpenSession(session) },
                                )
                            }
                    }
                }
            }

            item {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onCreateChat(workspaces.firstOrNull()?.cwd ?: "") }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("在此电脑新建对话", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DrawerHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun DrawerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    dot: Color? = null,
    icon: Boolean = false,
    indented: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(
                start = if (indented) 20.dp else 10.dp,
                end = 8.dp,
                top = 7.dp,
                bottom = 7.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            dot != null -> Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
            icon -> Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            else -> Spacer(Modifier.width(7.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A recorded session's own title, falling back to its id when it has none. */
private fun sessionTitle(session: SessionInfo): String =
    session.title?.takeIf { it.isNotBlank() } ?: session.id.take(18)

/**
 * Format an ISO-8601 timestamp for the drawer.
 *
 * `javax.xml.bind` is not on Android, so the portable parsers are tried in
 * order of how much of the timestamp they need.
 */
private fun formatChatTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
    )
    for (pattern in patterns) {
        var formatted: String? = null
        runCatching {
            val parser = SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }
            val date = parser.parse(iso)
            if (date != null) formatted = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(date)
        }
        if (formatted != null) return formatted!!
    }
    // Last resort: show the date part rather than nothing.
    return iso.take(16).replace('T', ' ')
}

private fun formatChatTime(epochMs: Long): String {
    if (epochMs <= 0) return "—"
    return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(epochMs))
}

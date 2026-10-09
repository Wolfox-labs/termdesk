package dev.termdesk.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import dev.termdesk.app.data.ChatApproval
import dev.termdesk.app.data.ChatEvent
import dev.termdesk.app.data.ChatModels
import dev.termdesk.app.data.SessionGroups
import dev.termdesk.app.data.UploadedFile
import dev.termdesk.app.data.CodexConfig
import dev.termdesk.app.data.KernelInfo
import dev.termdesk.app.data.KernelRun
import dev.termdesk.app.data.ChatInfo
import dev.termdesk.app.data.ChatTerminal
import dev.termdesk.app.data.TerminalView
import dev.termdesk.app.data.SessionDetail
import dev.termdesk.app.data.SessionInfo
import dev.termdesk.app.data.SessionSort
import dev.termdesk.app.data.WorkspaceInfo
import dev.termdesk.app.ui.theme.Semantic
import kotlinx.coroutines.delay
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
    approvals: List<ChatApproval>,
    onRespondApproval: (String, String) -> Unit,
    workspaces: List<WorkspaceInfo>,
    sessions: List<SessionInfo>,
    defaultCwd: String,
    recordedSession: SessionDetail?,
    engines: List<KernelInfo>,
    codexConfig: CodexConfig?,
    chatModels: Map<String, ChatModels>,
    lastUpload: UploadedFile?,
    onUploadFile: (android.net.Uri, String) -> Unit,
    onClearUpload: () -> Unit,
    onRequestChatModels: (String) -> Unit,
    onOpenSections: () -> Unit,
    onLoadChats: () -> Unit,
    onCreateChat: (String?) -> Unit,
    onOpenChat: (String) -> Unit,
    onSend: (String, String) -> Unit,
    onCancel: (String) -> Unit,
    onCloseChat: (String) -> Unit,
    onLeaveChat: () -> Unit,
    onConfigureChat: (String, String?, String?) -> Unit,
    onSetChatMode: (String, String) -> Unit,
    onCloseRecorded: () -> Unit,
    onLoadSessions: () -> Unit,
    /** Agent processes running on the PC that its own agent did not start. */
    kernelRuns: List<KernelRun> = emptyList(),
    kernelRunsNote: String? = null,
    /**
     * Open the file section at a directory.
     *
     * The conversation knows where it works; the file browser is where that
     * directory can actually be looked at. Without this the path was a label and
     * the person had to find the same folder again by hand.
     */
    onOpenFiles: ((String) -> Unit)? = null,
    /** Messages waiting for a link, and how many expired unsent. */
    pendingSends: Int = 0,
    pendingDropped: Int = 0,
    onLoadKernelRuns: () -> Unit = {},
    /**
     * The order conversations inside a workspace are listed in, and how to change it.
     *
     * It comes from preferences rather than from local state: the order is about how
     * a person reads their own history, so a choice has to survive a restart instead
     * of being asked for again on every launch.
     */
    sessionSort: SessionSort = SessionSort.Default,
    onSetSessionSort: (SessionSort) -> Unit = {},
    onOpenSession: (SessionInfo) -> Unit,
    onResumeSession: (SessionDetail) -> Unit,
    connected: Boolean,
    /** The command lines the open conversation ran, and the one being viewed. */
    terminals: List<ChatTerminal>,
    terminalView: TerminalView?,
    onLoadTerminals: (String) -> Unit,
    onOpenTerminal: (ChatTerminal) -> Unit,
    onTerminalInput: (String) -> Unit,
    onStopTerminal: (String) -> Unit,
    onCloseTerminalView: () -> Unit,
) {
    // 0 = live conversations, 1 = recorded history. One list, two states: there
    // is no separate drawer for history any more, because a phone-width screen
    // must not spend a third of its width on a second navigation surface.
    var tab by remember { mutableStateOf(0) }
    // Which kernel's conversations are shown. The product asked for the hierarchy
    // kernel -> workspace -> conversation: with every kernel's sessions in one flat
    // list, finding one meant reading all of them. Null means "no narrowing".
    var engineFilter by rememberSaveable { mutableStateOf<String?>(null) }
    // Details are folded away by default: the bar stays one row, and the model /
    // directory / effort line only appears when the user asks for it.
    var detailOpen by remember { mutableStateOf(false) }
    // The command lines of this conversation, shown over the conversation rather
    // than beside it: output wants the whole 400dp.
    var terminalsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onLoadChats()
        onLoadSessions()
    }

    // "What is running over there" is a SNAPSHOT: the PC answers it by listing its own
    // processes. Asked once per connection, that snapshot is the one from connect time
    // — which is how the list showed nothing while a desktop application was running a
    // conversation. So it is refreshed while the list is being looked at: when the
    // "进行中" tab is shown, and every 20 s after that. Not while the app is in the
    // background, so a phone left on this screen with the display off does not keep
    // waking the PC's process listing up.
    val view = LocalView.current
    val lifecycleOwner = remember(view) { view.findViewTreeLifecycleOwner() }
    // `!= false` on purpose: when there is no owner to ask (a preview, or a host that
    // sets none) the answer is "assume foreground". Refreshing too often costs one
    // process listing; not refreshing at all is the bug this effect exists to fix, so
    // the unknown case must not fall on the broken side.
    var inForeground by remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false,
        )
    }
    DisposableEffect(lifecycleOwner) {
        val owner = lifecycleOwner
        if (owner == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, _ ->
                inForeground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }
    LaunchedEffect(tab, connected, inForeground) {
        if (tab != 0 || !connected || !inForeground) return@LaunchedEffect
        while (true) {
            onLoadKernelRuns()
            delay(20_000)
        }
    }

    // Opening a recorded session attaches the kernel to it (metadata-only on the
    // PC), so history and a live conversation become the same view rather than
    // two separate objects. The PC says per session whether it can be continued.
    LaunchedEffect(recordedSession?.engine, recordedSession?.id, connected) {
        val session = recordedSession
        // Any kernel the PC marks resumable, not just codex: attaching to a
        // recorded session is metadata-only on the PC (the kernel replays its own
        // transcript), so this costs nothing and history and a live conversation
        // become the same view.
        if (session != null && connected && session.canResume) onResumeSession(session)
    }

    // A new subject always starts folded.
    LaunchedEffect(activeChat?.id, recordedSession?.id) {
        detailOpen = false
        terminalsOpen = false
        onCloseTerminalView()
    }

    // A conversation's model list is the kernel's own declaration, and a kernel
    // only declares it once a session exists - so it is asked for as soon as a
    // conversation is open, not only when the folded panel is expanded. Codex is
    // the exception: its catalog is configuration, not a session declaration.
    LaunchedEffect(activeChat?.id, activeChat?.engine, connected) {
        val chat = activeChat ?: return@LaunchedEffect
        if (connected && chat.engine != "codex" && chatModels[chat.id] == null) {
            onRequestChatModels(chat.id)
        }
    }

    val open = activeChat != null || recordedSession != null

    // Back walks out of the conversation, not out of the app: the command lines
    // first, then the detail panel, then the list.
    BackHandler(enabled = open || terminalsOpen) {
        when {
            terminalsOpen -> {
                terminalsOpen = false
                onCloseTerminalView()
            }
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
            canResume = connected && recordedSession?.canResume == true && !sending,
            onResume = { recordedSession?.let(onResumeSession) },
            onOpenTerminals = {
                activeChat?.let { onLoadTerminals(it.id) }
                terminalsOpen = true
            },
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
                    models = chatModels[chat.id],
                    onConfigure = onConfigureChat,
                    onSetMode = onSetChatMode,
                    onRequestModels = { onRequestChatModels(chat.id) },
                    onOpenFiles = onOpenFiles,
                )
            } else if (recorded != null) {
                RecordedDetailPanel(recorded)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Box(Modifier.weight(1f)) {
            when {
                // The command lines cover the conversation: they are the same
                // subject seen from another angle, not a second screen beside it.
                terminalsOpen && activeChat != null -> ChatTerminalsPanel(
                    terminals = terminals,
                    view = terminalView,
                    onOpen = onOpenTerminal,
                    onInput = onTerminalInput,
                    onStop = onStopTerminal,
                    onRefresh = { onLoadTerminals(activeChat.id) },
                    onClose = {
                        terminalsOpen = false
                        onCloseTerminalView()
                    },
                )
                recordedSession != null -> {
                    recordedSession.resumeNote?.let { note ->
                        Text(
                            note,
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
                    models = chatModels[activeChat.id],
                    lastUpload = lastUpload,
                    onUploadFile = onUploadFile,
                    onClearUpload = onClearUpload,
                    onSend = onSend,
                    onCancel = onCancel,
                    onConfigure = onConfigureChat,
                    onSetMode = onSetChatMode,
                    onClose = onCloseChat,
                    connected = connected,
                    pendingSends = pendingSends,
                    pendingDropped = pendingDropped,
                    // The same door the workspace row uses: a produced file is opened the
                    // way its folder is, so there is one behaviour to learn, not two.
                    onOpenFile = onOpenFiles,
                )
                else -> Column(Modifier.fillMaxSize()) {
                    // Kernel scope first: the list is kernel -> workspace ->
                    // conversation, and this is the outermost level.
                    val scope = SessionGroups.scopeOf(chats, sessions, engineFilter)
                    // A stored choice can outlive what it pointed at, and `scopeOf`
                    // resolves that to "everything" - so the state is corrected here
                    // rather than leaving a filter on that the switcher cannot show.
                    if (scope.selected != engineFilter) engineFilter = scope.selected
                    val (visibleChats, visibleSessions) = SessionGroups.only(chats, sessions, scope.selected)
                    SessionTabs(
                        tab = tab,
                        chatCount = visibleChats.size,
                        sessionCount = visibleSessions.size,
                        onSelect = { tab = it },
                    )
                    // The order switch shares this row instead of the tab row above it.
                    // The tab row had nothing that could give when the text grew: measured
                    // on a real phone at a 1.35 font scale, the two tabs plus the switch
                    // already filled 96% of the width, and 1.45 is a setting the owner
                    // actually uses. The kernel chips here scroll, so THEY take the
                    // squeeze and the switch keeps its width at any scale.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (scope.isMeaningful) {
                            EngineScopeRow(
                                engines = scope.engines,
                                selected = scope.selected,
                                labelOf = { id -> engines.firstOrNull { it.id == id }?.displayName ?: id },
                                onSelect = { engineFilter = it },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        SortSwitch(sort = sessionSort, onSort = onSetSessionSort)
                    }
                    Box(Modifier.weight(1f)) {
                        // One grouped list for both tabs: they differ in WHICH
                        // conversations they hold, not in how a person finds one.
                        if (tab == 0) {
                            // "进行中" is what the agent is holding right now, so no
                            // recorded sessions are passed: the history lives on the
                            // other tab, and mixing them would quietly turn this one
                            // into a second history list.
                            GroupedSessionList(
                                chats = visibleChats,
                                sessions = emptyList(),
                                emptyTitle = "还没有进行中的对话",
                                emptyHint = "在 $defaultCwd 中新建一个，就能在手机上和电脑里的助手连续对话。",
                                onOpenChat = onOpenChat,
                                onCloseChat = onCloseChat,
                                onOpenSession = onOpenSession,
                                onCreateChat = { onCreateChat(defaultCwd) },
                                externalRuns = kernelRuns,
                                externalRunsNote = kernelRunsNote,
                                sort = sessionSort,
                            )
                        } else {
                            GroupedSessionList(
                                chats = visibleChats,
                                sessions = visibleSessions,
                                emptyTitle = "没有找到历史会话",
                                emptyHint = "电脑上记录过的对话会出现在这里，点开即可阅读或继续。",
                                onOpenChat = onOpenChat,
                                onCloseChat = onCloseChat,
                                onOpenSession = onOpenSession,
                                onCreateChat = { onCreateChat(defaultCwd) },
                                onScanSessions = onLoadSessions,
                                allowClose = false,
                                sort = sessionSort,
                            )
                        }
                    }
                }
            }
        }
    }

    // A pending question is the most important thing on this screen — the kernel
    // is blocked until it is answered — so it is drawn over everything else
    // rather than buried somewhere in the transcript.
    val pendingApproval = approvals.firstOrNull { it.chatId == null || it.chatId == activeChat?.id }
    if (pendingApproval != null && open) {
        ApprovalDialog(approval = pendingApproval, onAnswer = onRespondApproval)
    }
}

/**
 * The engine is blocked until this is answered.
 *
 * The countdown is real, not decorative: the agent gives the question a deadline
 * and settles it on its own when it passes, which is what makes a remote
 * approval safe — a phone in a pocket must not freeze a conversation forever.
 * Dismissing the dialog leaves the question pending, so it comes back instead of
 * silently deciding anything.
 */
@Composable
private fun ApprovalDialog(approval: ChatApproval, onAnswer: (String, String) -> Unit) {
    var now by remember(approval.requestId) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(approval.requestId) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val seconds = (approval.remainingMs(now) / 1000).toInt()

    AlertDialog(
        onDismissRequest = { /* stays pending; it will be asked again */ },
        title = { Text(approval.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (approval.detail.isNotBlank()) {
                    Text(
                        approval.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    )
                }
                // Fixed-shape lines on purpose. A countdown whose text re-wraps
                // every second is a dialog whose buttons move while the user is
                // reaching for them, and a mis-tap here decides whether the
                // kernel may run something. mm:ss never grows past the wrap.
                if (seconds > 0) {
                    Text(
                        "${approvalEngineLabel(approval.engine)} · 剩余 ${countdownText(seconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "超时按「${approval.fallbackText}」处理；关掉这个框只是稍后再说",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                approval.options.forEach { option ->
                    Button(
                        onClick = { onAnswer(approval.requestId, option.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when (option.style) {
                                "danger" -> MaterialTheme.colorScheme.error
                                "primary" -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surface
                            },
                            contentColor = when (option.style) {
                                "danger" -> Color.White
                                "primary" -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        ),
                    ) {
                        Text(option.label)
                    }
                }
            }
        },
        confirmButton = {},
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

/** mm:ss, so the line never changes width enough to move the buttons. */
private fun countdownText(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}

/** The kernel's name as the settings list spells it. */
private fun approvalEngineLabel(engine: String?): String = when (engine) {
    "codex" -> "Codex"
    "dsh" -> "DeepSeek Harness"
    "opencode" -> "OpenCode"
    "mimo" -> "MiMo Code"
    else -> engine?.takeIf { it.isNotBlank() } ?: "内核"
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
    onOpenTerminals: () -> Unit,
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
                imageVector = if (open) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Menu,
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
            IconButton(onClick = onOpenTerminals) {
                Icon(
                    Icons.Outlined.Terminal,
                    contentDescription = "命令行",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    models: ChatModels?,
    onConfigure: (String, String?, String?) -> Unit,
    onSetMode: (String, String) -> Unit,
    onRequestModels: () -> Unit,
    /** Null when there is nowhere to open it (a preview, a test). */
    onOpenFiles: ((String) -> Unit)? = null,
) {
    val catalog = if (chat.engine == "codex") codexConfig?.models.orEmpty() else emptyList()
    // Two sources of truth, one picker: Codex declares its models in the config
    // catalog, every other kernel answers through chat.models (its own session
    // declaration). The thread's own model may be in neither (it can be set
    // outside TermDesk), so it is always offered - otherwise nothing looks chosen.
    val picker = remember(catalog, models, chat.model, chat.engine) {
        val declared = models?.models.orEmpty().map { it.id to it.label }
        val fromCatalog = catalog.map { it.slug to (it.displayName.ifBlank { it.slug }) }
        val pairs = if (chat.engine == "codex") fromCatalog else declared
        val all = if (chat.model.isNotBlank() && pairs.none { it.first == chat.model }) {
            listOf(chat.model to chat.model) + pairs
        } else pairs
        // 1556 models are real, so the picker is searchable and never a chip row.
        all.distinctBy { it.first }
    }
    val modelIds = picker.map { it.first }
    val modeIds = models?.modes.orEmpty().map { it.id }
    val labelOf = { id: String -> picker.firstOrNull { it.first == id }?.second ?: id }
    var pickerOpen by remember { mutableStateOf(false) }
    val declaredCurrent = models?.current?.takeIf { it.isNotBlank() }
    val currentLabel = when {
        chat.model.isNotBlank() -> labelOf(chat.model)
        declaredCurrent != null -> labelOf(declaredCurrent)
        else -> "内核默认"
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
        // The working directory is the way into the files: everything the turn
        // produced is in here, and making the person find the same folder again by
        // hand is the whole reason the two screens felt unrelated.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .then(
                    if (onOpenFiles != null) {
                        Modifier.clickable { onOpenFiles(chat.cwd) }
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 3.dp),
        ) {
            if (onOpenFiles != null) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    // The action, not the icon: a screen reader should not stop at
                    // "folder" and leave the person to guess what a tap does.
                    contentDescription = "在文件里打开 ${chat.cwd}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                chat.cwd,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // A handful of models stay chips. Anything longer is a searchable picker:
        // one ACP kernel declares 1556 of them, and a chip row is not a way to
        // choose among those.
        if (modelIds.isNotEmpty() && modelIds.size <= CHIP_LIMIT) {
            Spacer(Modifier.height(8.dp))
            InlineChips(
                label = "模型",
                items = modelIds,
                selected = chat.model,
                labelOf = labelOf,
                onSelect = { onConfigure(chat.id, it, chat.effort.ifBlank { null }) },
            )
        } else if (modelIds.size > CHIP_LIMIT) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(44.dp),
                )
                TextButton(
                    onClick = { pickerOpen = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        currentLabel,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    modelIds.size.toString() + " 个可选 · 可搜索",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (modeIds.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            InlineChips(
                label = "权限",
                items = modeIds,
                selected = models?.currentMode.orEmpty(),
                labelOf = { id -> models?.modeLabelOf(id) ?: id },
                onSelect = { onSetMode(chat.id, it) },
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
        if (modelIds.isEmpty() && levels.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            val note = models?.note
            Text(
                when {
                    models == null && chat.engine != "codex" -> "正在读取这个内核的模型清单…"
                    !note.isNullOrBlank() -> note
                    chat.engine == "codex" -> "Codex 的模型清单见设置（models.json）"
                    else -> "该内核没有声明模型清单，由它自己决定。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (pickerOpen) {
            ChoicePickerDialog(
                title = "选择模型 · " + chat.engine,
                options = picker,
                selected = chat.model.ifBlank { declaredCurrent.orEmpty() },
                onDismiss = { pickerOpen = false },
                onPick = { id ->
                    pickerOpen = false
                    onConfigure(chat.id, id, chat.effort.ifBlank { null })
                },
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
                    // The PC's verdict, not a guess from the engine name.
                    append(if (session.canResume) " · 可继续对话" else " · 只读")
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

/** Above this many choices a chip row stops being a choice and becomes a scroll. */
private const val CHIP_LIMIT = 12

/** Searchable model picker: an ACP kernel can declare more models than a screen holds. */
@Composable
private fun ChoicePickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(options, query) {
        if (query.isBlank()) options
        else options.filter { (id, name) -> id.contains(query, true) || name.contains(query, true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("搜索") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    filtered.size.toString() + " / " + options.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(filtered, key = { it.first }) { (id, name) ->
                        val isSelected = id == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(id) }
                                .padding(vertical = 8.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (name != id) {
                                    Text(
                                        id,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (isSelected) {
                                Text(
                                    "当前",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
/** Label + horizontally scrollable choices, one line each. */
private fun InlineChips(
    label: String,
    items: List<String>,
    selected: String,
    labelOf: (String) -> String = { it },
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
                    text = labelOf(item),
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
private fun SessionTabs(
    tab: Int,
    chatCount: Int,
    sessionCount: Int,
    onSelect: (Int) -> Unit,
) {
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

/**
 * The order switch for the list below: newest first, or A→Z by title.
 *
 * It is drawn at the end of the kernel row, and that placement is the point. On the tab
 * row above it had nothing that could give when the text grew: measured on the test phone
 * (339dp wide, a 1.35 font scale) the three chips filled 96% of the width. The owner's
 * phone is wider (400dp) but set to 1.45, which lands in the same marginal place, and any
 * narrower screen would have clipped it. The kernel chips beside it scroll, so a squeeze
 * is absorbed there and this stays whole at any font scale.
 *
 * The label names the order IN FORCE rather than the one a tap would switch to: a chip
 * reading "名称" while the list is sorted by time reads as a button already pressed.
 */
@Composable
private fun SortSwitch(sort: SessionSort, onSort: (SessionSort) -> Unit) {
    Text(
        text = "排序：${sort.label}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable {
                onSort(if (sort == SessionSort.Recent) SessionSort.Name else SessionSort.Recent)
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * Which kernel's conversations are listed.
 *
 * The hierarchy is kernel -> workspace -> conversation, and this is the outermost level.
 * The reason it exists: one machine had 313 recorded sessions across every kernel at
 * once, so "find my OpenCode conversation" meant reading Codex's and MiMo's history too.
 *
 * "全部" is offered first and is the default, because narrowing is a way to find one
 * thing faster, not a mode the person should have to leave. Only kernels that have
 * something to show appear at all — a tab that lands on an empty screen is a button that
 * cannot work, which this project has had to remove more than once.
 *
 * Horizontally scrollable rather than wrapped: five kernels at a 1.45 font scale exceed
 * 400dp, and a wrapped second row would push the list down for a control used rarely.
 * It also shares its row with the order switch, which is what lets the switch stay whole
 * when the text grows: this row can scroll, the switch cannot shrink.
 */
@Composable
private fun EngineScopeRow(
    engines: List<String>,
    selected: String?,
    labelOf: (String) -> String,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val choices = listOf<String?>(null) + engines
        for (choice in choices) {
            val isSelected = choice == selected
            Text(
                text = if (choice == null) "全部" else labelOf(choice),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface,
                    )
                    .clickable { onSelect(choice) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}
/** Recorded history, listed like conversations because that is what it is. */
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
internal fun KernelBadge(engine: String?) {
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
internal fun ChatRow(
    chat: ChatInfo,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    /**
     * Whether closing belongs on this row.
     *
     * It does on "进行中" — those are runtimes the agent is holding, and ending one
     * is a real action. It does not on "历史": a recorded session on disk is not
     * something the phone can stop, and an x that refuses to do anything teaches
     * people to distrust the other x.
     */
    allowClose: Boolean = true,
) {
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
        if (allowClose) {
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
    models: ChatModels?,
    lastUpload: UploadedFile?,
    onUploadFile: (android.net.Uri, String) -> Unit,
    onClearUpload: () -> Unit,
    onSend: (String, String) -> Unit,
    onCancel: (String) -> Unit,
    onConfigure: (String, String?, String?) -> Unit,
    onSetMode: (String, String) -> Unit,
    onClose: (String) -> Unit,
    connected: Boolean,
    pendingSends: Int = 0,
    pendingDropped: Int = 0,
    /** Open a produced file's folder in the file section (see `DeliverableLine`). */
    onOpenFile: ((String) -> Unit)? = null,
) {
    // What this conversation can run as, both of them the kernel's own words:
    // the model list and the permission / agent modes it declared.
    val modelOptions = models?.models.orEmpty().map { it.id to it.label }
    val modeOptions = models?.modes.orEmpty().map { it.id to it.label }
    // The chat summary carries the mode the PC has actually applied, so it wins
    // over the mode the list was fetched with (a change since then is real).
    val chosenMode = chat.mode.ifBlank { models?.currentMode.orEmpty() }.ifBlank { null }
    val chosenModelLabel = when {
        chat.model.isNotBlank() -> models?.labelOf(chat.model) ?: chat.model
        models?.current != null -> models.labelOf(models.current!!)
        else -> "内核默认"
    }
    val chosenModeLabel = chosenMode?.let { id -> modeOptions.firstOrNull { it.first == id }?.second ?: id } ?: "内核默认"
    var modelPickerOpen by remember(chat.id) { mutableStateOf(false) }
    var modePickerOpen by remember(chat.id) { mutableStateOf(false) }
    // The file picked with `+`, once the PC has actually got it. The prompt then
    // carries its path, which every kernel can read - no kernel-specific image
    // block is invented here.
    var attachment by remember(chat.id) { mutableStateOf<UploadedFile?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onUploadFile(uri, chat.cwd)
    }
    LaunchedEffect(lastUpload?.path) {
        val done = lastUpload ?: return@LaunchedEffect
        if (done.path.startsWith(chat.cwd.trimEnd('\\', '/'))) {
            attachment = done
            onClearUpload()
        }
    }
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
                    items(events, key = { it.seq }) { event -> ChatEventRow(event, onOpenFile) }
                }
            }
        }

        run {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Attachment first: a long model name used to push it off the
                // right edge, where a scrolled row hid it entirely.
                ComposerChip("＋", "附件", withChevron = false) { filePicker.launch(arrayOf("*/*")) }
                if (modelOptions.isNotEmpty()) {
                    // The model name is the long one, so it is the chip that gives
                    // way: the attachment and permission entries stay on screen.
                    ComposerChip("模型", chosenModelLabel, modifier = Modifier.weight(1f)) { modelPickerOpen = true }
                }
                if (modeOptions.isNotEmpty()) {
                    ComposerChip("权限", chosenModeLabel) { modePickerOpen = true }
                }
            }
        }

        // The file that is about to be sent, and how to take it back off.
        attachment?.let { file ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "附件 " + file.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { attachment = null }, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "移除附件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        // `/` opens the conversation's own command panel. It lists what TermDesk
        // can actually do to THIS conversation - not a pretend copy of a kernel's
        // menu - and everything in it does something real when tapped.
        if (draft.startsWith("/") && !draft.contains(' ') && !draft.contains('\n')) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                SlashRow("模型", if (modelOptions.isEmpty()) "该内核没有模型清单" else chosenModelLabel, modelOptions.isNotEmpty()) {
                    draft = ""
                    modelPickerOpen = true
                }
                SlashRow("权限模式", if (modeOptions.isEmpty()) "该内核没有权限开关" else chosenModeLabel, modeOptions.isNotEmpty()) {
                    draft = ""
                    modePickerOpen = true
                }
                SlashRow("附件", "传到电脑，随下一条消息发出", true) {
                    draft = ""
                    filePicker.launch(arrayOf("*/*"))
                }
                SlashRow("关闭会话", "结束这个对话", true) {
                    draft = ""
                    onClose(chat.id)
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
            pendingCount = pendingSends,
            droppedCount = pendingDropped,
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    // The attachment travels as a path in the prompt: every kernel
                    // can read a file, and the transcript then shows what was sent.
                    val full = attachment?.let { "[附件] ${it.name} → ${it.path}\n$text" } ?: text
                    onSend(chat.id, full)
                    draft = ""
                    attachment = null
                }
            },
            onCancel = { onCancel(chat.id) },
        )

        if (modelPickerOpen) {
            ChoicePickerDialog(
                title = "选择模型 · " + chat.engine,
                options = modelOptions,
                selected = chat.model,
                onDismiss = { modelPickerOpen = false },
                onPick = { id ->
                    modelPickerOpen = false
                    onConfigure(chat.id, id, chat.effort.ifBlank { null })
                },
            )
        }
        if (modePickerOpen) {
            ChoicePickerDialog(
                title = "权限模式 · " + chat.engine,
                options = modeOptions,
                selected = chosenMode.orEmpty(),
                onDismiss = { modePickerOpen = false },
                onPick = { id ->
                    modePickerOpen = false
                    onSetMode(chat.id, id)
                },
            )
        }
    }
}

/** One line in the `/` panel: what it is, what it currently is, and what it does. */
@Composable
private fun SlashRow(label: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "/" + label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(96.dp),
        )
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One small pill in the conversation's bottom bar: label above value. */
@Composable
private fun ComposerChip(
    label: String,
    value: String,
    withChevron: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (withChevron) {
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
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
    /**
     * How many messages are waiting for the link to come back.
     *
     * Shown rather than silently held: someone who typed a message on a train
     * needs to know it is not lost, and needs to know when it finally goes.
     */
    pendingCount: Int = 0,
    /** How many were dropped for being too old to be worth sending. */
    droppedCount: Int = 0,
) {
    Column(Modifier.fillMaxWidth()) {
        if (pendingCount > 0) {
            Text(
                text = "待发 $pendingCount 条 · 连上就发",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
        if (droppedCount > 0) {
            Text(
                // Said out loud: a message that expired is a message the person
                // expected to be sent, and finding out by silence is worse.
                text = "$droppedCount 条待发消息已过期未发出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
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
        // Two buttons while a reply is streaming, where there used to be one that turned into
        // "stop" — on the reasoning that a second prompt mid-turn would be rejected. The PC
        // queues a message typed mid-turn now (ChatManager.drainQueue), so that reasoning is
        // false and the button outlived it: waiting became something the person can ask for
        // instead of something they are refused.
        if (running) {
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Outlined.Stop,
                    contentDescription = "停止",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        // Shown while running only once there is something to send, so the row stays calm
        // when there is not, and appears the moment somebody types.
        if (!running || draft.isNotBlank()) {
            // Disabled only by what the person can control themselves. It used to also be
            // disabled while a send was in flight, which quietly removed the queue on ACP
            // kernels: there the acknowledgement arrives when the turn *ends*, so the button
            // stayed dead for the whole turn and nothing could be typed into the queue at all.
            // The PC accepts and orders the second message either way (ChatManager.drainQueue).
            if (sending && !running) {
                // "In flight" means the gap between tapping and the turn starting — not the
                // whole turn, which would claim the machine is still receiving what it has
                // already begun answering.
                CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            IconButton(onClick = onSend, enabled = canSend && draft.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = if (running) "发送（排队）" else "发送",
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

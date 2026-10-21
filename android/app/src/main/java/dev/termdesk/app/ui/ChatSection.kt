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
import androidx.compose.foundation.verticalScroll
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
import dev.termdesk.app.data.ChatDraft
import dev.termdesk.app.data.ChatDraftStore
import android.content.Context
import androidx.compose.ui.platform.LocalContext
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
import dev.termdesk.app.data.ChatCommands
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
    /** The kernel the home page has selected; the new-chat dialog starts here. */
    defaultEngine: String? = null,
    /** Directories the agent allows, offered beside the known workspaces. */
    roots: List<String> = emptyList(),
    recordedSession: SessionDetail?,
    engines: List<KernelInfo>,
    codexConfig: CodexConfig?,
    chatModels: Map<String, ChatModels>,
    chatCommands: Map<String, ChatCommands>,
    lastUpload: UploadedFile?,
    draftScope: String,
    onClaimChat: (String, Boolean) -> Unit,
    onUploadFile: (android.net.Uri, String) -> Unit,
    onClearUpload: () -> Unit,
    onRequestChatModels: (String) -> Unit,
    onRequestChatCommands: (String) -> Unit,
    onOpenSections: () -> Unit,
    onLoadChats: () -> Unit,
    /** (working directory, kernel): both chosen in the new-conversation dialog. */
    onCreateChat: (String?, String?) -> Unit,
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
    // "+" asks where and on which kernel before it creates anything. The home page
    // keeps a standing choice, but pressing "+" here is starting a new subject and
    // may want a different directory or assistant for it.
    var newChatOpen by rememberSaveable { mutableStateOf(false) }

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

    // Slash commands are declared by the kernel that owns the session (the desktop
    // app's host), so they are asked for when a conversation opens. Without this
    // the `/` panel could only show the four rows compiled into the app.
    LaunchedEffect(activeChat?.id, connected) {
        val chat = activeChat ?: return@LaunchedEffect
        if (connected && chatCommands[chat.id] == null) onRequestChatCommands(chat.id)
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
            onNew = { newChatOpen = true },
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
                    // A Column of our own, on purpose: the branch's parent is a Box, so the note
                    // and the transcript's `fillMaxSize()` list were drawn ON TOP of each other —
                    // two blocks of text overlapping on a real screen. Weight only means
                    // something inside a column, which is why this one is explicit.
                    Column(Modifier.fillMaxSize()) {
                        recordedSession.resumeNote?.let { note ->
                            Text(
                                note,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            RecordedTranscript(recordedSession, onOpenFiles)
                        }
                    }
                }
                activeChat != null -> Conversation(
                    chat = activeChat,
                    events = events,
                    sending = sending,
                    models = chatModels[activeChat.id],
                    commands = chatCommands[activeChat.id],
                    lastUpload = lastUpload,
                    draftScope = draftScope,
                    onClaimChat = onClaimChat,
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
                                emptyTitle = "暂无进行中的会话",
                                emptyHint = "工作目录：$defaultCwd",
                                onOpenChat = onOpenChat,
                                onCloseChat = onCloseChat,
                                onOpenSession = onOpenSession,
                                onCreateChat = { newChatOpen = true },
                                externalRuns = kernelRuns,
                                externalRunsNote = kernelRunsNote,
                                sort = sessionSort,
                            )
                        } else {
                            GroupedSessionList(
                                chats = visibleChats,
                                sessions = visibleSessions,
                                emptyTitle = "没有找到历史会话",
                                emptyHint = "显示当前设备上的历史记录。",
                                onOpenChat = onOpenChat,
                                onCloseChat = onCloseChat,
                                onOpenSession = onOpenSession,
                                onCreateChat = { newChatOpen = true },
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
    //
    // "稍后再说" has to lead somewhere, though. The question stays pending on the PC — that is
    // what makes a remote approval safe — so it is the DIALOG that is deferred, and the bar below
    // is the way back to it. Before this, the dialog's own copy promised a way out while every
    // exit (back, scrim, outside tap) was a no-op: the only ways past it were answering or
    // waiting out the countdown, with the transcript that explains the question unreachable.
    var deferredApproval by remember(activeChat?.id) { mutableStateOf<String?>(null) }
    val pendingApproval = approvals.firstOrNull { it.chatId == null || it.chatId == activeChat?.id }
    if (pendingApproval != null && open) {
        if (deferredApproval == pendingApproval.requestId) {
            // A way back to a question that was deferred: the dialog is gone, the question is not.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                TextButton(
                    onClick = { deferredApproval = null },
                    modifier = Modifier.padding(bottom = 18.dp),
                ) {
                    Text("助手正在等待确认 · 查看请求")
                }
            }
        } else {
            ApprovalDialog(
                approval = pendingApproval,
                onAnswer = onRespondApproval,
                onDefer = { deferredApproval = pendingApproval.requestId },
            )
        }
    }

    if (newChatOpen) {
        NewChatDialog(
            engines = engines,
            selectedEngine = defaultEngine,
            workspaces = workspaces,
            roots = roots,
            currentCwd = defaultCwd,
            onDismiss = { newChatOpen = false },
            onCreate = { cwd, engine ->
                newChatOpen = false
                onCreateChat(cwd, engine)
            },
        )
    }
}


/** One small pill in the conversation's bottom bar: label above value. */
@Composable
internal fun ComposerChip(
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
internal fun Composer(
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
                text = "待发 $pendingCount 条 · 重连后自动发送",
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
            placeholder = { Text("输入消息", style = MaterialTheme.typography.bodyMedium) },
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


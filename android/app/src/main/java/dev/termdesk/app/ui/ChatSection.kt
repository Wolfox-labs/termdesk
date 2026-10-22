package dev.termdesk.app.ui

import androidx.activity.compose.BackHandler
import android.net.Uri
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.delay

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
    /** The assistant a new conversation starts on when the person does not pick one. */
    defaultEngine: String? = null,
    /** Directories the PC offers as places to start in. */
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
    onCreateChat: (String?, String?) -> Unit,
    onOpenChat: (String) -> Unit,
    onSend: (String, String) -> Unit,
    /** 手机本地做完一件事（如图片处理）之后，往当前对话里写一行系统提示。 */
    onNote: (String) -> Unit = {},
    /** 海报画好之后把卡片放进当前对话。 */
    onPoster: (String, String) -> Unit = { _, _ -> },
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
    /**
     * 点对话里的产出物时，在当前这一页直接看结果。
     *
     * 传的是 (path, name)：文件已经在电脑上，这里只按扩展名决定用哪个查看器，
     * 不为分类多打一次往返。为 null 时退回 onOpenFiles 的「打开所在文件夹」。
     */
    onPreviewFile: ((String, String) -> Unit)? = null,
    /**
     * 把「产出文件」卡片上的那一份直接下到手机。
     *
     * 用户的原话是"所有 @ 功能虽然生成了东西，但我在手机端完全找不到，我要手机上也能
     * 看到生成好的东西、直接可以下载"。路径来自电脑端的产物扫描（chat.js announceArtifacts），
     * 下载走的是文件区同一个下载器。
     */
    onDownloadPath: ((String, String) -> Unit)? = null,
    pendingSends: Int = 0,
    pendingDropped: Int = 0,
    /** 这个会话还有更早的历史没取回来（agent 支持 chat.page 时才可能为 true）。 */
    chatHasOlder: Boolean = false,
    onLoadOlderChat: () -> Unit = {},
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
    // 会话页的「+」先问一句再建：落到错的目录里，是用户在手机上没法自己修的那类错误。
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
                            RecordedTranscript(recordedSession, onOpenFiles, onPreviewFile)
                        }
                    }
                }
                activeChat != null -> {
                    // 聊天页顶部那一条：左边「最近会话」，正上方「TermDesk」点了回主页。
                    //
                    // 做在这一层而不是改 AppShell 的顶栏：那条顶栏是四个分区共用的，改它要
                    // 一路改调用点；而这两个入口只对"正在聊天"这一屏有意义——离开会话之后
                    // 它们自己就没了，正好。
                    var chatListOpen by remember(activeChat.id) { mutableStateOf(false) }
                    Column(Modifier.fillMaxSize()) {
                        ChatTopStrip(
                            onOpenChats = { chatListOpen = true },
                            onHome = onLeaveChat,
                        )
                        Box(Modifier.weight(1f)) {
                            Conversation(
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
                                onNote = onNote,
                                onPoster = onPoster,
                                onCancel = onCancel,
                                onConfigure = onConfigureChat,
                                onSetMode = onSetChatMode,
                                onClose = onCloseChat,
                                connected = connected,
                                pendingSends = pendingSends,
                                pendingDropped = pendingDropped,
                                hasOlder = chatHasOlder,
                                onLoadOlder = onLoadOlderChat,
                                // 产出物先给「看效果」：在当前页打开它；右侧文件夹图标仍可跳到目录。
                                onOpenFile = onOpenFiles,
                                onPreviewFile = onPreviewFile,
                                onDownloadPath = onDownloadPath,
                            )
                        }
                    }
                    if (chatListOpen) {
                        RecentChatsPanel(
                            chats = chats,
                            currentId = activeChat.id,
                            onPick = { id ->
                                chatListOpen = false
                                onOpenChat(id)
                            },
                            onDismiss = { chatListOpen = false },
                        )
                    }
                }
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
    if (pendingApproval != null) {
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
        } else if (open) {
            ApprovalDialog(
                approval = pendingApproval,
                onAnswer = onRespondApproval,
                onDefer = { deferredApproval = pendingApproval.requestId },
            )
        } else {
            // 会话没打开时也必须有入口：内核在等一个人回答，而用户可能正停在别的页面。
            // 上一版这一整块挂在 `open` 上，没打开那个会话时连一句提示都没有，
            // 从用户那边看就是"内核不动了，手机上什么都没有"。
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Button(
                    onClick = {
                        deferredApproval = null
                        pendingApproval.chatId?.let(onOpenChat)
                    },
                    modifier = Modifier.padding(bottom = 18.dp),
                ) {
                    Text("内核在等你确认 · 点这里回答")
                }
            }
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

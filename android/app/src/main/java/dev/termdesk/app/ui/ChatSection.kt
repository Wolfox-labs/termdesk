package dev.termdesk.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.runtime.rememberCoroutineScope
import dev.termdesk.app.data.ImageTools
import dev.termdesk.app.data.PosterRenderer
import dev.termdesk.app.data.PosterStyles
import dev.termdesk.app.data.PosterText
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
    onCreateChat: (String?) -> Unit,
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
                                emptyHint = "显示当前设备上的历史记录。",
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
private fun ApprovalDialog(
    approval: ChatApproval,
    onAnswer: (String, String) -> Unit,
    /** Hide the dialog without answering. The question stays pending; the bar behind it is the way back. */
    onDefer: () -> Unit,
) {
    var now by remember(approval.requestId) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(approval.requestId) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val seconds = (approval.remainingMs(now) / 1000).toInt()

    AlertDialog(
        // Back / outside tap now do what the copy has always promised. The question is not
        // decided by this — it stays pending on the PC — so the worst case is that the person
        // comes back to it, which is exactly what "稍后再说" says.
        onDismissRequest = onDefer,
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
                        // P7：引擎给的选项 label 可能是空的，于是按钮上什么都没有——
                        // 真机上就是两个没有文字的可点区域，读屏也读不出来。给个兜底文案。
                        Text(
                            option.label.ifBlank {
                                when (option.style) {
                                    "danger" -> "拒绝"
                                    "primary" -> "允许"
                                    else -> "选择"
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        // A visible way out, so the sentence above it is not a promise about a gesture nobody
        // can guess.
        dismissButton = {
            TextButton(onClick = onDefer) { Text("稍后处理") }
        },
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
            .heightIn(min = 48.dp)
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
            maxLines = 2,
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
        else -> "默认"
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
        Text(chat.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            KernelBadge(chat.engine)
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildString {
                    append(chat.model.ifBlank { "默认模型" })
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
            Column {
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
                }
                Text(
                    modelIds.size.toString() + " 个模型",
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
                    models == null && chat.engine != "codex" -> "正在读取模型列表…"
                    !note.isNullOrBlank() -> note
                    chat.engine == "codex" -> "模型列表需在 Codex 中配置。"
                    else -> "此助手未提供模型列表。"
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
        Text(session.title?.takeIf { it.isNotBlank() } ?: session.id, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
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

/**
 * 「什么都能选」的 MIME 通配符。
 *
 * 单独提成常量不只是为了整洁：这个字面量里出现了星号紧跟斜杠的形状，而 **Kotlin 的块注释
 * 是可以嵌套的**——同样的形状一旦出现在文档注释内部，就会又开一层注释，于是外层注释一直
 * 开到文件末尾，把后面整片代码都吃掉（这个坑真踩过一次，报错位置和真正的原因差了上千行）。
 * 收在一处，就不用每次写文档注释时提心吊胆。
 */
private const val MIME_ANY = "*/*"

/** Searchable model picker: an ACP kernel can declare more models than a screen holds. */
/**
 * 聊天页顶部那一条。
 *
 * 左边「最近会话」——用户的原话是"少走两步"：正在聊天时想换一个会话，不该先退出去、
 * 再找列表。正中间「TermDesk」——它就是这个 App 的招牌，点一下回主页（主页占满屏幕）。
 * 两边各留一块等宽的空白，招牌才真的在正中间，而不是"看着差不多"。
 */
@Composable
private fun ChatTopStrip(onOpenChats: () -> Unit, onHome: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "☰ 最近会话",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenChats)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "TermDesk",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onHome)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Spacer(Modifier.weight(1f))
        // 和左边那块等宽，招牌才会落在正中间。
        Spacer(Modifier.width(84.dp))
    }
}

/**
 * 从屏幕左边拉出来的「最近会话」。
 *
 * 用 Dialog 而不是抽屉：这一层只需要"浮在上面、点一下就切走"，不必和分区导航那套纠缠；
 * `usePlatformDefaultWidth = false` 才能自己占到整屏、把列表贴在左边。
 */
@Composable
private fun RecentChatsPanel(
    chats: List<ChatInfo>,
    currentId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 「从左边滑出来，占半个屏幕」——用户的原话。
        // 上一版是一块写死 272dp 的面板：在 393dp 宽的机器上占了七成屏，加上整屏的暗色遮罩，
        // 看上去就是"点一下整屏都被它占住了"。现在宽度取屏幕的一半，并且真的滑进来。
        val halfWidth = (LocalConfiguration.current.screenWidthDp * 0.5f).dp
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val slide by animateDpAsState(
            targetValue = if (shown) 0.dp else -halfWidth,
            animationSpec = tween(durationMillis = 220),
            label = "recentChatsSlide",
        )
        Row(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(halfWidth)
                    .fillMaxHeight()
                    .offset(x = slide)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "最近会话",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "按最近使用从上到下排，点一条就切过去",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (chats.isEmpty()) {
                    Text(
                        text = "还没有会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    chats.forEach { item ->
                        val here = item.id == currentId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (here) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .clickable { onPick(item.id) }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (here) FontWeight.Medium else FontWeight.Normal,
                                    color = if (here) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = item.eventCount.toString() + " 条 · " +
                                        formatChatTime(item.lastUsedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                }
            }
            // 右边这片点一下就关，不用去够返回键。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = onDismiss),
            )
        }
    }
}

/** 当前这张海报的配方：右上角那些快捷键就是拿它再画一遍。 */
private data class PosterRecipe(
    val styleId: String,
    val title: String,
    val subtitle: String,
    val scene: String,
    val adjust: PosterRenderer.Adjust,
)

/**
 * 海报生成的表单——**内联在聊天页的面板区**，不再是 AlertDialog 悬浮窗。
 *
 * 为什么改成内联：用户点名"不要单独设置悬浮 UI 窗口，都在文本框那里弹出操作就可以"。
 * 真机上悬浮窗正好压在聊天气泡上，边看对话边写标题做不到；面板区本来就贴着输入框，
 * 和「网页生成」那套选完接着问的位置、手感完全一致。
 *
 * 字段一个没动：风格（胶囊单选）、主标题、副标题、场景需求，加取消 / 生成海报。
 * **这里仍然没有、也不该有任何底图预览**——底图是私密资源，界面只让用户选"感觉"，
 * 落到哪张图由 [PosterStyles] 在后台决定。
 */
@Composable
private fun PosterFormDialog(
    styleId: String,
    title: String,
    subtitle: String,
    scene: String,
    busy: Boolean,
    onStyle: (String) -> Unit,
    onTitle: (String) -> Unit,
    onSubtitle: (String) -> Unit,
    onScene: (String) -> Unit,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "做一张海报 · 选感觉、写标题",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 小标题下压一条主色分隔线：这块和旁边那些追问面板是一个长相。
        HorizontalDivider(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        Text(
            text = "风格",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PosterStyles.all.forEach { style ->
                ComposerChip(
                    style.badge,
                    if (style.id == styleId) "已选" else "点我",
                ) {
                    onStyle(style.id)
                }
            }
        }
        Text(
            text = PosterStyles.byId(styleId)?.hint.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = title,
            onValueChange = onTitle,
            enabled = !busy,
            singleLine = true,
            label = { Text("主标题", style = MaterialTheme.typography.bodySmall) },
            placeholder = {
                Text("例如：新媒体创意大赛", style = MaterialTheme.typography.bodySmall)
            },
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = subtitle,
            onValueChange = onSubtitle,
            enabled = !busy,
            singleLine = true,
            label = { Text("副标题（可留空）", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = scene,
            onValueChange = onScene,
            enabled = !busy,
            maxLines = 2,
            label = { Text("场景需求（可留空）", style = MaterialTheme.typography.bodySmall) },
            placeholder = {
                Text("例如：面向全院学生，扫码报名", style = MaterialTheme.typography.bodySmall)
            },
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        if (busy) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = "正在绘制…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // 原来悬浮窗底部那两个键右对齐；这里保持一样的位置、一样的文案、一样的回调。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
            // 主标题空着不让点：没有标题的海报画出来只是一张底图。
            TextButton(onClick = onStart, enabled = title.isNotBlank() && !busy) {
                Text(if (busy) "绘制中…" else "生成海报")
            }
        }
    }
}

/** 点开看大图：整屏一张海报 + 下载与关闭。 */
@Composable
private fun PosterPreviewDialog(
    file: String,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    onTweak: ((PosterRenderer.Tweak) -> Unit)? = null,
) {
    // 只加载这一张，而且是在 IO 线程上——大图在主线程解码会卡住界面。
    val bitmap by produceState<android.graphics.Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(file) }
    }
    // 用户点名的布局：**右上角**是可以再次处理的快捷键，**右下角**是一个小的下载标志。
    // 上一版把这些挂件摆在聊天卡片旁边（竖排贴在图上），既不顺手也挤掉图的宽度。
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(onClick = onClose),
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "生成的海报",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 56.dp),
                )
            } ?: Text(
                text = "图片读取中…",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )

            // 左上角：关掉。整块背景也能点关，这个只是给一个明确的目标。
            TextButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Text("✕", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }

            // 右上角：可再次处理的快捷键。
            if (onTweak != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    posterKeys.forEach { key ->
                        PosterQuickKey(key.label) { onTweak.invoke(key.tweak) }
                    }
                }
            }

            // 右下角：小的下载标志。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "↓",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * 图片处理的表单——**内联在聊天页的面板区**，不再是 AlertDialog 悬浮窗。
 *
 * 为什么改成内联：用户点名"不要单独设置悬浮 UI 窗口，都在文本框那里弹出操作就可以"。
 * 处理图片本来就是盯着结果反复调的事，悬浮窗既挡聊天气泡、也挡刚处理好的那张图；
 * 面板区贴着输入框，和「网页生成」那套追问是同一个位置。
 *
 * 用户点名的五样东西，顺序照旧，一个不少：
 *  1. 【从相册选择图片】—— 其实是系统文件选择器，所以相册、内存库、微信/QQ 文件都能进；
 *  2. 【压缩质量】滑动条，0~100%，默认 80；
 *  3. 【输出格式】JPG / PNG / WEBP 单选；
 *  4. 【水印文字】输入框，可留空；
 *  5. 【开始处理】+ 【取消】，处理时显示进度。
 *
 * 处理过程全在手机上（[ImageTools]），不走内核：压缩、转格式、打水印都是确定性操作，
 * 交给模型只会把图传来传去。
 */
@Composable
private fun ImageFormDialog(
    pickedCount: Int,
    quality: Int,
    format: String,
    watermark: String,
    busy: Boolean,
    progress: Pair<Int, Int>,
    onPickImages: () -> Unit,
    onQuality: (Int) -> Unit,
    onFormat: (String) -> Unit,
    onWatermark: (String) -> Unit,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "处理图片 · 在手机上直接做完",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 小标题下压一条主色分隔线：这块和旁边那些追问面板是一个长相。
        HorizontalDivider(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        Button(
            onClick = onPickImages,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (pickedCount == 0) "从相册选择图片"
                else "已选 " + pickedCount + " 张 · 重新选择",
            )
        }
        Text(
            text = "相册、内存、微信 / QQ 文件都能从这里进（可多选）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = "压缩质量 · " + quality + "%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = quality.toFloat(),
            onValueChange = { onQuality(it.roundToInt()) },
            valueRange = 0f..100f,
            enabled = !busy,
        )

        Spacer(Modifier.height(4.dp))
        Text(
            text = "输出格式",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf("JPG", "PNG", "WEBP").forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !busy) { onFormat(option) },
                ) {
                    RadioButton(
                        selected = option == format,
                        onClick = { onFormat(option) },
                        enabled = !busy,
                    )
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "水印文字（可留空）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = watermark,
            onValueChange = onWatermark,
            enabled = !busy,
            singleLine = true,
            placeholder = {
                Text("例如：© 数智融媒", style = MaterialTheme.typography.bodySmall)
            },
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )

        if (busy) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = "正在处理 " + progress.first + " / " + progress.second + " …",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // 处理中「取消」和「开始处理」都按住不让点：中途点掉会让用户以为没做完。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
            // 一张图都没选就不让点：按下去也只能空转。
            TextButton(onClick = onStart, enabled = pickedCount > 0 && !busy) {
                Text(if (busy) "处理中…" else "开始处理")
            }
        }
    }
}

/**
 * 文案撰写的表单——**内联在聊天页的面板区**，不再是 AlertDialog 悬浮窗。
 *
 * 为什么改成内联：用户点名"不要单独设置悬浮 UI 窗口，都在文本框那里弹出操作就可以"，
 * 悬浮窗正好压住聊天气泡。三栏照旧，一栏不少：
 *  - 类型：点一下在面板区展开一行 chips 直接选（原来的搜索式选择浮层已经删掉）；
 *  - 卖点：一个输入框，空着不让发——没有卖点的文案生成出来必然是废话；
 *  - 语气：三个单选按钮，选了就亮；
 *  - 底部两个按钮：取消 / 开始生成。
 *
 * 为什么用表单而不是多轮问答：这三件事本来就是并列的一次性输入，多轮问要等三个来回。
 */
@Composable
private fun CopyFormDialog(
    fields: CopyFieldSet,
    type: String,
    point: String,
    tone: String,
    onPickType: () -> Unit,
    onPoint: (String) -> Unit,
    onTone: (String) -> Unit,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "写文案 · 填三栏就开工",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 小标题下压一条主色分隔线：这块和旁边那些追问面板是一个长相。
        HorizontalDivider(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )

        Text(
            text = "文案类型",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onPickType)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = type,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "▾",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "核心卖点",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = point,
            onValueChange = onPoint,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            placeholder = {
                Text(
                    "例如：防脱发洗发水 / 平价美白",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            textStyle = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            shape = RoundedCornerShape(9.dp),
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = "语气风格",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        fields.tones.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onTone(option) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == tone, onClick = { onTone(option) })
                Text(
                    text = option,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("取消") }
            // 卖点空着就不让点：生成出来的必然是废话，不如先在按钮上拦住。
            TextButton(onClick = onStart, enabled = point.isNotBlank()) {
                Text("开始生成")
            }
        }
    }
}

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
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onSelect(choice) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}
/** Recorded history, listed like conversations because that is what it is. */
@Composable
private fun RecordedTranscript(
    session: SessionDetail,
    onOpenFile: ((String) -> Unit)? = null,
    onPreviewFile: ((String, String) -> Unit)? = null,
) {
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
    // Mapped and folded once per session. The seq matters here: a folded run is keyed by its
    // first event's seq, and recorded events arrive without one — every row would share the
    // same key, which is how a list recycles the wrong line.
    val rows = remember(session.engine, session.id, session.events) {
        foldTranscript(
            session.events.mapIndexed { index, event ->
                ChatEvent(
                    seq = index + 1,
                    at = 0L,
                    kind = event.kind,
                    role = event.role,
                    text = event.text,
                    name = event.name,
                    state = event.state,
                    exitCode = event.exitCode,
                    // Carried through, not dropped: it is what labels an injected block as a
                    // goal, a job list or a skill catalogue instead of a bare "上下文".
                    sourceKind = event.sourceKind,
                    streaming = false,
                    files = event.files,
                )
            },
        )
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 10.dp, end = 10.dp, top = 10.dp, bottom = 14.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is TranscriptRow.One -> ChatEventRow(row.event, onOpenFile, onPreviewFile)
                is TranscriptRow.Run -> ProcessRunLine(row.events, onOpenFile, onPreviewFile)
            }
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
                maxLines = 2,
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
    commands: ChatCommands?,
    lastUpload: UploadedFile?,
    draftScope: String,
    onClaimChat: (String, Boolean) -> Unit,
    onUploadFile: (android.net.Uri, String) -> Unit,
    onClearUpload: () -> Unit,
    onSend: (String, String) -> Unit,
    /** 手机本地做完一件事之后写一行系统提示（见 AgentClient.note）。 */
    onNote: (String) -> Unit = {},
    /** 海报画好之后把卡片放进当前对话（见 AgentClient.poster）。 */
    onPoster: (String, String) -> Unit = { _, _ -> },
    onCancel: (String) -> Unit,
    onConfigure: (String, String?, String?) -> Unit,
    onSetMode: (String, String) -> Unit,
    onClose: (String) -> Unit,
    connected: Boolean,
    pendingSends: Int = 0,
    pendingDropped: Int = 0,
    /** Open a produced file's folder in the file section (see `DeliverableLine`). */
    onOpenFile: ((String) -> Unit)? = null,
    /** 点产出物时在当前页直接看结果；为 null 时退回上面的「打开所在文件夹」。 */
    onPreviewFile: ((String, String) -> Unit)? = null,
    /** 「产出文件」卡片上的下载：把这一份存到手机上。 */
    onDownloadPath: ((String, String) -> Unit)? = null,
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
        else -> "默认"
    }
    val chosenModeLabel = chosenMode?.let { id -> modeOptions.firstOrNull { it.first == id }?.second ?: id } ?: "默认"
    var modelPickerOpen by remember(chat.id) { mutableStateOf(false) }
    var modePickerOpen by remember(chat.id) { mutableStateOf(false) }
    // The file picked with `+`, once the PC has actually got it. The prompt then
    // carries its path, which every kernel can read - no kernel-specific image
    // block is invented here.
    val context = LocalContext.current
    val draftStore = remember(context) { ChatDraftStore(context.getSharedPreferences("termdesk.chat-drafts", Context.MODE_PRIVATE)) }
    val savedDraft = remember(draftScope, chat.id) { draftStore.read(draftScope, chat.id) }
    var draft by remember(draftScope, chat.id) { mutableStateOf(TextFieldValue(savedDraft.text)) }
    var attachment by remember(draftScope, chat.id) { mutableStateOf(savedDraft.attachment) }
    /** 文本与光标一起改，并落盘（远程带来的草稿持久化）。 */
    fun updateDraftValue(value: TextFieldValue) {
        draft = value
        draftStore.write(draftScope, chat.id, ChatDraft(draft.text, attachment))
    }
    /** 只要文本：清空输入框、插入标记时用。 */
    fun updateDraft(value: String) {
        updateDraftValue(TextFieldValue(value))
    }
    fun updateAttachment(value: UploadedFile?) {
        attachment = value
        draftStore.write(draftScope, chat.id, ChatDraft(draft.text, attachment))
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onUploadFile(uri, chat.cwd)
    }
    // 相册单独一个入口：用户心里"相册"和"文件"本来就是分开的两件事，
    // 只给一个文件选择器，人会以为选不了照片。
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onUploadFile(uri, chat.cwd)
    }
    /** 输入框左边那个加号点开之后，先问一句"相册还是文件"。 */
    var attachChooserOpen by remember(chat.id) { mutableStateOf(false) }
    LaunchedEffect(lastUpload?.path) {
        val done = lastUpload ?: return@LaunchedEffect
        if (done.path.startsWith(chat.cwd.trimEnd('\\', '/'))) {
            updateAttachment(done)
            onClearUpload()
        }
    }
    // Fresh state per chat: opening or switching a conversation always lands at
    // its end, never at a leftover scroll offset from the previous one.
    val listState = key(chat.id) { rememberLazyListState() }
    // ── 「@网页生成」这条路的界面状态 ──────────────────────────────────
    // 骨架资源包内嵌在 APK 里（assets/templates/web/），发消息时把选中的那一份
    // 整份装配进指令。读它要 Context（上面已取过一次），所以在这里记住，别在每次重组时重读。
    val skeletonOf: (PageTemplate) -> String = remember(context) {
        { template -> WebTemplates.readSkeleton(context.assets, template) }
    }
    // 选完 @网页生成 之后要挑的那套骨架；非 null 就是面板开着。
    var templatePanel by remember(chat.id) { mutableStateOf<List<PageTemplate>?>(null) }
    // 发送之前欠用户的那几次询问：配色、主题。点发送不等于发出去。
    var colorPanel by remember(chat.id) { mutableStateOf(false) }
    var themePanel by remember(chat.id) { mutableStateOf(false) }
    var themeInput by remember(chat.id) { mutableStateOf("") }
    // 要点和主题一样，是**一个自己的小输入框**，不是那一大段指令全文。
    // 「看全文」才是看全文的地方；点「要点」只该给一个能打字的地方。
    var pointsPanel by remember(chat.id) { mutableStateOf(false) }
    var pointsInput by remember(chat.id) { mutableStateOf("") }
    // 主题这一问固定问一次；问过就不再挡路，用户跳过也照样能发。
    var themeAsked by remember(chat.id) { mutableStateOf(false) }

    // ── 「@PPT生成」那条路的界面状态 ────────────────────────────────────
    // 蓝图资源包同样内嵌在 APK 里（assets/templates/ppt/），读它也要 Context，
    // 所以和骨架一样在这里取一次、记住。
    val blueprintOf: (DeckTemplate) -> String = remember(context) {
        { deck -> DeckTemplates.readBlueprint(context.assets, deck) }
    }
    // 选完 @PPT生成 之后要挑的那套蓝图；非 null 就是面板开着。
    var deckPanel by remember(chat.id) { mutableStateOf<List<DeckTemplate>?>(null) }
    // 页数和主题一样是「本地固定问一次」：跳过就用模板的默认页数，不挡发送。
    var pagesPanel by remember(chat.id) { mutableStateOf(false) }
    var pagesInput by remember(chat.id) { mutableStateOf("") }
    var pagesAsked by remember(chat.id) { mutableStateOf(false) }
    // 大纲面板：rows 记页数区间，inputs 是每条**可以单独改**的「章节|内容」。
    // 两者分开是有意的——输入框里只给能改的两样，页数区间由 App 保证首尾相接，
    // 用户不会一不小心把页码改乱。
    var outlinePanel by remember(chat.id) { mutableStateOf(false) }
    var outlineRows by remember(chat.id) { mutableStateOf<List<OutlineRow>>(emptyList()) }
    var outlineInputs by remember(chat.id) { mutableStateOf<List<String>>(emptyList()) }

    /** 开一块面板就关掉其余所有面板：真机上它们叠起来会把屏幕撑爆。 */
    fun closeComposerPanels(keep: () -> Unit, others: Array<() -> Unit>) {
        others.forEach { it() }
        keep()
    }

    // ── 「@文案撰写」那条路：一张悬浮表单 ─────────────────────────────
    // 非 null 就是弹窗开着；值就是那一类产出物的字段定义（类型/语气有哪些选项）。
    var copyFormFor by remember(chat.id) { mutableStateOf<ChatMention?>(null) }
    var copyType by remember(chat.id) { mutableStateOf("") }
    var copyPoint by remember(chat.id) { mutableStateOf("") }
    var copyTone by remember(chat.id) { mutableStateOf("") }
    // 类型那一栏的"下拉框"复用已有的搜索式选择器（和选模型、选权限模式同一个）。
    var copyTypePicker by remember(chat.id) { mutableStateOf(false) }

    /** 把表单三栏拼成草稿正文——和用户手打 `键：值` 一模一样，所以之后手改那一行也生效。 */
    fun copyDraftText(mentionId: String, type: String, point: String, tone: String): String {
        val lines = ArrayList<String>()
        lines.add("@" + mentionId)
        if (type.isNotBlank()) lines.add(Copywriting.MARK_TYPE + "：" + type)
        if (point.isNotBlank()) lines.add(Copywriting.MARK_POINT + "：" + point)
        if (tone.isNotBlank()) lines.add(Copywriting.MARK_TONE + "：" + tone)
        return lines.joinToString("\n")
    }

    /** 打开表单，并把草稿里已经填过的值带回去（用户改完再打开不该从零开始）。 */
    fun openCopyForm(mention: ChatMention, from: MentionDraft) {
        val pairs: Map<String, String> = DraftTokens.pairs(from.free)
        val fields: CopyFieldSet = mention.copy ?: Copywriting.FIELDS
        copyType = pairs[Copywriting.MARK_TYPE].orEmpty().ifBlank { fields.defaultType }
        copyPoint = pairs[Copywriting.MARK_POINT].orEmpty()
        copyTone = pairs[Copywriting.MARK_TONE].orEmpty().ifBlank { fields.defaultTone }
        copyFormFor = mention
    }

    /**
     * 表单点「开始生成」：拼指令 → **直接发出去** → 关掉弹窗、清空输入框。
     *
     * 用户要的就是这个手感：填完点一下就走，不用再回输入框按一次发送。
     */
    fun submitCopyForm() {
        val mention: ChatMention = copyFormFor ?: return
        val text: String = copyDraftText(mention.id, copyType, copyPoint, copyTone)
        val expanded: String = ChatMentions.expand(text, skeletonOf, blueprintOf)
        onSend(chat.id, expanded)
        updateDraft("")
        copyFormFor = null
    }

    // ── 「@海报生成」那条路：也是全程在手机上画 ──────────────────────────
    var posterFormOpen by remember(chat.id) { mutableStateOf(false) }
    var posterStyleId by remember(chat.id) { mutableStateOf(PosterStyles.fallback.id) }
    var posterTitle by remember(chat.id) { mutableStateOf("") }
    var posterSubtitle by remember(chat.id) { mutableStateOf("") }
    var posterScene by remember(chat.id) { mutableStateOf("") }
    var posterBusy by remember(chat.id) { mutableStateOf(false) }
    var posterNote by remember(chat.id) { mutableStateOf("") }
    /** 点开看大图的那张（null = 没在看）。 */
    var posterViewing by remember(chat.id) { mutableStateOf<String?>(null) }
    /** 当前这张海报的配方：右上角那些快捷键就是拿它再画一遍。 */
    var posterRecipe by remember(chat.id) { mutableStateOf<PosterRecipe?>(null) }
    val posterScope = rememberCoroutineScope()

    /** 画一张。首次生成、换风格、点快捷调整——全都走这里，只有一份绘制入口。 */
    fun paintPoster(recipe: PosterRecipe) {
        if (posterBusy) return
        posterBusy = true
        posterRecipe = recipe
        posterScope.launch {
            val style = PosterStyles.byId(recipe.styleId) ?: PosterStyles.fallback
            val out = runCatching {
                PosterRenderer.render(
                    context = context,
                    request = PosterRenderer.Request(
                        style = style,
                        title = recipe.title,
                        subtitle = recipe.subtitle,
                        scene = recipe.scene,
                        adjust = recipe.adjust,
                    ),
                )
            }.getOrNull()
            posterBusy = false
            if (out == null) {
                onNote("海报没画出来：底图读不到，请再试一次。")
            } else {
                onPoster(PosterText.caption(recipe.title), out.file)
                posterNote = if (out.tweaks.isEmpty()) "" else "已调：" + out.tweaks.joinToString(" ")
            }
        }
    }

    /** 表单点「生成海报」：风格按用户选的来，用户没选就按标题与场景自动挑。 */
    fun submitPosterForm() {
        val style = PosterStyles.byId(posterStyleId)
            ?: PosterStyles.pick(posterTitle + " " + posterScene)
        posterFormOpen = false
        updateDraft("")
        paintPoster(
            PosterRecipe(
                styleId = style.id,
                title = posterTitle,
                subtitle = posterSubtitle,
                scene = posterScene,
                adjust = PosterRenderer.Adjust(),
            ),
        )
    }

    /** 右上角竖排那几个键：在现有配方上叠一次调整，立刻重画。 */
    fun tweakPoster(tweak: PosterRenderer.Tweak) {
        val current = posterRecipe ?: return
        paintPoster(current.copy(adjust = current.adjust.apply(tweak)))
    }

    /** 右下角那个下载键：写进手机相册。 */
    fun downloadPoster(file: String) {
        posterScope.launch {
            val where = withContext(Dispatchers.IO) { PosterRenderer.export(context, file) }
            posterNote = if (where == null) "保存失败，请检查存储权限。" else "已保存到" + where + "。"
        }
    }

    // ── 「@图片处理」那条路：全程在手机上做完，不走内核 ──────────────────
    var imageFormOpen by remember(chat.id) { mutableStateOf(false) }
    var imageUris by remember(chat.id) { mutableStateOf<List<Uri>>(emptyList()) }
    var imageQuality by remember(chat.id) { mutableStateOf(80) }
    var imageFormat by remember(chat.id) { mutableStateOf("JPG") }
    var imageWatermark by remember(chat.id) { mutableStateOf("") }
    var imageBusy by remember(chat.id) { mutableStateOf(false) }
    var imageProgress by remember(chat.id) { mutableStateOf(0 to 0) }
    val imageScope = rememberCoroutineScope()

    /**
     * 打开三张内联表单（海报 / 图片 / 文案）里的一张，并关掉其余所有面板。
     *
     * 表单从悬浮窗搬进面板区之后，它们和模板、配色、要点那些面板躺在同一个滚动列里：
     * 两块叠着半屏就放不下，用户正要填的那块会被顶到看不见的地方，所以谁打开谁独占。
     */
    fun openInlineForm(keep: () -> Unit) {
        closeComposerPanels(
            keep = keep,
            others = arrayOf(
                { templatePanel = null }, { colorPanel = false }, { themePanel = false },
                { pointsPanel = false }, { deckPanel = null }, { pagesPanel = false },
                { outlinePanel = false }, { posterFormOpen = false },
                { imageFormOpen = false }, { copyFormFor = null },
                { copyTypePicker = false },
            ),
        )
    }

    /**
     * 选图用的是**系统的文件选择器**（`OpenMultipleDocuments`），不是只读相册。
     *
     * 用户要求能从「本地相册 / 内存库 / 微信文件 / QQ文件」里挑。能同时够到这几个来源的
     * 只有系统那套 DocumentsUI：相册、内部存储，以及微信、QQ 注册的文件提供程序都会出现在
     * 它的侧栏里。MIME 限定成图片类型，所以列表里只会列图片。
     */
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { picked ->
        if (picked.isNotEmpty()) imageUris = picked.take(30)
    }

    /** 点「开始处理」：进度条转起来 → 后台批量处理 → 往对话里写一行系统提示 → 关窗。 */
    fun submitImageForm() {
        if (imageUris.isEmpty() || imageBusy) return
        imageBusy = true
        imageProgress = 0 to imageUris.size
        imageScope.launch {
            val result = ImageTools.process(
                context = context,
                uris = imageUris,
                options = ImageTools.Options(imageQuality, imageFormat, imageWatermark),
            ) { done, total ->
                // 回调跑在 IO 线程上；Compose 的状态要回主线程写。
                imageScope.launch { imageProgress = done to total }
            }
            val line = ImageTools.noteText(result)
            onNote(line)
            imageBusy = false
            // 一张都没成：**把表单留在原地**，让用户直接再点一次「开始处理」。
            // 上一版无论成败都关窗，失败之后想重试只能从头再选一遍图（还没有重试入口）。
            if (result.saved > 0) {
                imageFormOpen = false
                imageUris = emptyList()
                updateDraft("")
            }
        }
    }

    /** 文案表单填全了没有：类型和卖点都得有。没填全就先弹表单，不发半截指令。 */
    fun copyFormFilled(md: MentionDraft): Boolean {
        val pairs: Map<String, String> = DraftTokens.pairs(md.free)
        return !pairs[Copywriting.MARK_TYPE].isNullOrBlank() &&
            !pairs[Copywriting.MARK_POINT].isNullOrBlank()
    }

    /** 大纲一条的可编辑写法：`章节|这几页讲什么`。 */
    fun rowText(row: OutlineRow): String = row.section + "|" + row.content

    /** 把用户改过的一行拆回 章节 + 内容；没写竖线就整行当章节名，内容留着不动。 */
    fun parseRowText(text: String, fallback: OutlineRow): OutlineRow {
        val at: Int = text.indexOf('|')
        if (at < 0) return fallback.copy(section = text.trim().ifBlank { fallback.section })
        return fallback.copy(
            section = text.substring(0, at).trim().ifBlank { fallback.section },
            content = text.substring(at + 1).trim(),
        )
    }

    /**
     * 本地铺大纲：模板章节 + 用户要点 + 用户要的页数。
     *
     * 用户要的是「快速形成大纲」——所以这件事整个在本地做完，不花模型回合，
     * 他点开大纲的那一刻就已经有东西可改了。
     */
    fun composeOutlineRows(md: MentionDraft): List<OutlineRow> {
        val deck: DeckTemplate = md.deck ?: return emptyList()
        val sections: List<DeckSection> = DeckTemplates.parseSections(blueprintOf(deck))
        return DeckTemplates.compose(sections, md.theme, md.points, md.effectivePages())
    }
    // 「将展开成完整指令」是否展开显示全文。
    var previewOpen by remember(chat.id) { mutableStateOf(false) }
    // Follow the stream only while the user is already at (or has returned to)
    // the bottom. Scrolling up hands control back to the reader immediately.
    var stickToBottom by remember(chat.id) { mutableStateOf(true) }
    var programmaticScroll by remember(chat.id) { mutableStateOf(false) }

    /**
     * 把草稿**当作最终指令**发出去。
     *
     * 抽成函数的原因：「发送」和 PPT 大纲面板上的「确认，开始生成」是同一件事的两条入口。
     * 上一版后者只把大纲写回输入框、从不发送，用户点完"开始生成"发现什么都没发生
     * （摘要多出一行「N 条大纲」，消息还留在输入框里，得再点一次发送）。
     */
    fun sendDraftExpanded(text: String) {
        // 「@模板」在这里展开：内核读到的就是这段完整指令，
        // transcript 显示的也是同一份内容，所以这个入口决定的是
        // 「内核真的会去写文件」，而不是只往输入框里塞几个字。
        val expanded = ChatMentions.expand(text, skeletonOf, blueprintOf)
        // The attachment travels as a path in the prompt: every kernel
        // can read a file, and the transcript then shows what was sent.
        val full = attachment?.let { "[附件] ${it.name} → ${it.path}\n$expanded" } ?: expanded
        onSend(chat.id, full)
        updateDraft("")
        updateAttachment(null)
        previewOpen = false
        themeAsked = false
        pagesAsked = false
    }

    /** 打开指定的那一问；返回 true 表示真的开了面板。 */
    fun openAsk(ask: String, md: MentionDraft): Boolean {
        // 先把所有面板关掉：两块面板叠在一起时，屏幕矮一点就会把后开的那块挤出可视区。
        templatePanel = null; colorPanel = false; themePanel = false; pointsPanel = false
        deckPanel = null; pagesPanel = false; outlinePanel = false
        when (ask) {
            MentionDraft.LOCAL_COLORS -> {
                colorPanel = true
                return true
            }
            MentionDraft.LOCAL_THEME -> {
                themeAsked = true
                themeInput = md.theme.ifBlank { md.free }
                themePanel = true
                return true
            }
            MentionDraft.LOCAL_PAGES -> {
                pagesAsked = true
                pagesInput = md.pages
                pagesPanel = true
                return true
            }
        }
        return false
    }

    /** PPT 的大纲确认：本地铺一份（不花模型回合），面板一开就有东西可改。 */
    fun openOutlineAsk(md: MentionDraft) {
        if (!md.needsOutlineAsk()) return
        if (outlineInputs.isEmpty()) {
            val fresh = composeOutlineRows(md)
            outlineRows = fresh
            outlineInputs = fresh.map { "" }
        }
        templatePanel = null; colorPanel = false; themePanel = false; pointsPanel = false
        deckPanel = null; pagesPanel = false
        outlinePanel = true
    }

    /**
     * 本地追问是一条**链**：答完这一问就接着问下一问，最后落到大纲确认。
     *
     * 每一问的「已问过」标记（[themeAsked] / [pagesAsked]）都算数，所以用户跳过的那一问
     * 不会又被问一次；而用户答过之后 `nextAsk()` 自己就不再返回它了。
     */
    fun advanceAsk() {
        val md = MentionDraft.parse(draft.text)
        for (ask in md.mention?.localAsks.orEmpty()) {
            when (ask) {
                MentionDraft.LOCAL_COLORS -> if (md.needsColorAsk()) {
                    openAsk(ask, md)
                    return
                }
                MentionDraft.LOCAL_THEME -> if (md.needsThemeAsk() && !themeAsked) {
                    openAsk(ask, md)
                    return
                }
                MentionDraft.LOCAL_PAGES -> if (md.needsPagesAsk() && !pagesAsked) {
                    openAsk(ask, md)
                    return
                }
            }
        }
        // 基础信息齐了：PPT 还差大纲那道闸门——直接铺一份给他改，改完点「确认，开始生成」。
        openOutlineAsk(md)
    }

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

    // Folded once per event list: a turn's plumbing (tool calls, results, reasoning) becomes one
    // line per run instead of a screenful, and it is folded here rather than inside the list
    // because a `LazyListScope` is not a place where state can be remembered.
    val rows = remember(events) { foldTranscript(events) }
    // 过程行整类不画：思考、工具、命令、步骤、内核日志。要的是"作品生成中…"加最终产物，
    // 不是几屏绿色文本框（见 ChatTranscript.isProcessRow 上的说明）。
    //
    // 作品任务（由 @ 模板发起的对话，首条用户消息是【任务：…】）还要更狠一点：
    // **模型说的话也不画**。用户原话——"聊天区域要干净，没做完就是作品生成中，
    // 做完了就是作品已完成，下面是生成的东西"。
    val deliverableTask = remember(events) {
        events.firstOrNull { it.kind == "message" && it.role == "user" }
            ?.text?.trimStart()?.startsWith("【任务：") == true
    }
    val shownRows = remember(rows, deliverableTask) {
        rows.filterNot { row ->
            isProcessRow(row) ||
                (deliverableTask && row is TranscriptRow.One && row.event.isAssistant)
        }
    }
    val deliverableDone = deliverableTask && !chat.isRunning && !sending &&
        events.any { it.isDeliverable }

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
        if (chat.ownership.isBusy) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("由 "+(chat.ownership.owner?.deviceName ?: "另一台设备")+" 控制", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { onClaimChat(chat.id, true) }, enabled = connected && !chat.isRunning) { Text(if (chat.isRunning) "生成中" else "接管") }
            }
        }
        Box(Modifier.weight(1f)) {
            if (events.isEmpty()) {
                Text(
                    "发送消息开始对话",
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
                    // 作品做完之后先给一行结论，下面才是产物卡片。
                    if (deliverableDone) {
                        item(key = "td-done") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "✓ 作品已完成",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    items(shownRows, key = { it.key }) { row ->
                        when (row) {
                            is TranscriptRow.One -> ChatEventRow(
                                event = row.event,
                                onOpenFile = onOpenFile,
                                onPreviewFile = onPreviewFile,
                                onPosterOpen = { posterViewing = it },
                                onPosterDownload = { downloadPoster(it) },
                                onPosterTweak = { tweakPoster(it) },
                                onDownloadFile = onDownloadPath,
                            )
                            is TranscriptRow.Run -> ProcessRunLine(row.events, onOpenFile, onPreviewFile)
                        }
                    }
                    // 正在生成：只留这一行。过程（工具、思考、命令、步骤、内核日志）整类不画，
                    // 用户要的是"作品生成中…"，不是几屏绿色文本框；生成完了这行自己就没了。
                    if (chat.isRunning || sending) {
                        item(key = "td-working") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "作品生成中…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
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
                ComposerChip("＋", "附件", withChevron = false) { filePicker.launch(arrayOf(MIME_ANY)) }
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
                IconButton(onClick = { updateAttachment(null) }, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "移除附件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        // ── 「@」模板：菜单 + 已套用提示 ─────────────────────────────────
        // 模板是纯文本的：选中哪一项，输入框里就出现 @标记；发送时在 onSend 里
        // 展开成完整指令发给内核。没有隐藏状态——删掉 @标记，下面的提示行自己消失。
        val mentionQuery = remember(draft.text) { ChatMentions.query(draft.text) }
        val mentionMatches = remember(mentionQuery) {
            mentionQuery?.let { ChatMentions.match(it) }.orEmpty()
        }
        val appliedMentions = remember(draft.text) { ChatMentions.applied(draft.text) }
        // 这一段文本将被装配成什么，只算一次。展开是纯字符串操作，但骨架有好几 KB，
        // 放在每次重组里重算是白烧电。
        val mentionDraft = remember(draft.text) { MentionDraft.parse(draft.text) }
        val expandedPreview = remember(draft.text) {
            ChatMentions.expand(draft.text, skeletonOf, blueprintOf)
        }

        // 明说会展开成什么，不做暗箱：用户按下发送之前就知道内核将收到一整段指令。
        //
        // 这里原来只画一个 badge（「WEB」）就说"将展开成完整指令"，正文一个字都不给看。
        // 用户在手机上打完主题，看不见它去了哪里——这正是「发送要求后在自动生成的字
        // 里面没有体现」。现在这一行给出三样东西：装配摘要、三个能点回来重选的入口、
        // 以及能就地展开的指令全文。
        // 面板区整体可滚、并给一个高度上限。
        //
        // 真机上暴露的问题：模板面板和配色面板同时开着、软键盘又弹起来的时候，剩余高度
        // 根本放不下这一叠面板；而 Column 对放不下的子项是**裁掉**、不是让它滚，于是
        // 配色色板被压成一条边，连颜色名字都看不见。这里让它自己滚——屏幕多矮都够得着。
        //
        // 上限取「半屏」而不是写死 300dp：写死的那一版在真机上只放得下 @ 菜单的前 4 项，
        // 第 5 项「表格生成」起被裁在输入框后面，用户会以为菜单里根本没有那几条。
        val panelMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = panelMaxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
        if (appliedMentions.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "将展开成完整指令：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    appliedMentions.forEach { mention ->
                        Text(
                            text = mention.badge,
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
                    Text(
                        text = if (previewOpen) "收起" else "看全文",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { previewOpen = !previewOpen }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                // 网页类才有骨架和配色，所以这三行只在这种时候出现。
                val template = mentionDraft.template
                val schemes = mentionDraft.mention?.templates.orEmpty()
                val deck = mentionDraft.deck
                val decks = mentionDraft.mention?.decks.orEmpty()
                // 表格那条路没有"挑一套模板"这一步：体裁由 App 按主题自己挑。
                // 这里算出来只是为了在芯片上把它显示给用户看。
                val tableKinds = mentionDraft.mention?.tables.orEmpty()
                val tableKind = if (tableKinds.isEmpty()) null
                else TableKinds.pick(draft.text, tableKinds)
                // 用悬浮表单收需求的那一类（目前只有文案撰写）。
                val copyMention = mentionDraft.mention?.takeIf { it.copy != null }
                if (template != null && schemes.isNotEmpty()) {
                    // 真正要发给模型的那份骨架：槽位已经在本地填过了。摘要里的
                    // 「还有几处交给模型补」就是数它，而不是数指令原文里的说明文字。
                    val filledSkeleton = remember(template, draft.text) {
                        WebTemplates.fill(
                            template,
                            skeletonOf(template),
                            mentionDraft.theme,
                            mentionDraft.points,
                            DraftTokens.pairs(mentionDraft.free),
                            mentionDraft.colors ?: ColorSchemes.fallback,
                        )
                    }
                    Text(
                        text = WebTemplates.summaryLine(
                            template,
                            mentionDraft.colors,
                            mentionDraft.theme,
                            mentionDraft.points,
                        ) + remainingHint(filledSkeleton),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 骨架 / 配色 / 主题这几块是**先后步骤**，不是三块并排的设置项。
                        // 每次只留一块开着：真机上几块叠在一起时，总高度超出屏幕，
                        // 后面那块（往往是用户正要答的配色）就被挤到看不见的地方去了。
                        // 所以下面每一个芯片都负责把**其余所有面板**关掉。
                        ComposerChip("骨架", template.id) {
                            closeComposerPanels(
                                keep = { templatePanel = schemes },
                                others = arrayOf(
                                    { colorPanel = false }, { themePanel = false },
                                    { pointsPanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip("配色", mentionDraft.colors?.id ?: "未选") {
                            closeComposerPanels(
                                keep = { colorPanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { themePanel = false },
                                    { pointsPanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip(
                            "主题",
                            mentionDraft.theme.ifBlank { "未填" },
                        ) {
                            themeInput = mentionDraft.theme.ifBlank { mentionDraft.free }
                            closeComposerPanels(
                                keep = { themePanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { pointsPanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip(
                            "要点",
                            WebTemplates.bulletPoints(mentionDraft.points).size.let {
                                if (it == 0) "未填" else it.toString() + " 条"
                            },
                        ) {
                            // 这里原来是 previewOpen = true —— 点「要点」弹出的是那一大段指令全文，
                            // 既没法打字、也不是用户想要的东西。改成和主题一样的一个小输入框。
                            pointsInput = mentionDraft.points
                            closeComposerPanels(
                                keep = { pointsPanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                    }
                } else if (deck != null && decks.isNotEmpty()) {
                    // ── PPT：同一套逻辑，但问的是页数，而且多一道「大纲」 ──────────
                    Text(
                        text = DeckTemplates.summaryLine(
                            deck,
                            mentionDraft.colors,
                            mentionDraft.theme,
                            mentionDraft.pages,
                            mentionDraft.outline,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 「自动」两个字是给用户看的凭据：这套模板是 App 按主题挑的，
                        // 不是随手下发的默认值。不满意就点开换一套。
                        ComposerChip("模板", "自动：" + deck.id) {
                            closeComposerPanels(
                                keep = { deckPanel = decks },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { pointsPanel = false },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip("配色", mentionDraft.colors?.id ?: "未选") {
                            closeComposerPanels(
                                keep = { colorPanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { themePanel = false },
                                    { pointsPanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip("主题", mentionDraft.theme.ifBlank { "未填" }) {
                            themeInput = mentionDraft.theme.ifBlank { mentionDraft.free }
                            closeComposerPanels(
                                keep = { themePanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { pointsPanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip(
                            "页数",
                            mentionDraft.pages.ifBlank {
                                deck.defaultPages.toString() + "（默认）"
                            },
                        ) {
                            pagesInput = mentionDraft.pages
                            closeComposerPanels(
                                keep = { pagesPanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { pointsPanel = false },
                                    { deckPanel = null }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip(
                            "要点",
                            WebTemplates.bulletPoints(mentionDraft.points).size.let {
                                if (it == 0) "未填" else it.toString() + " 条"
                            },
                        ) {
                            pointsInput = mentionDraft.points
                            closeComposerPanels(
                                keep = { pointsPanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip(
                            "大纲",
                            if (mentionDraft.outline.isEmpty()) "未确认"
                            else mentionDraft.outline.size.toString() + " 条",
                        ) {
                            // 点开就等于「我要看一眼/改一下」：还没铺过就先本地铺一份，
                            // 不要给用户一个空面板让他自己从零写。
                            if (outlineInputs.isEmpty()) {
                                val fresh = composeOutlineRows(mentionDraft)
                                outlineRows = fresh
                                outlineInputs = fresh.map { "" }
                            }
                            closeComposerPanels(
                                keep = { outlinePanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { pointsPanel = false },
                                    { deckPanel = null }, { pagesPanel = false },
                                ),
                            )
                        }
                    }
                } else if (tableKind != null) {
                    // ── 表格：只问主题，别的都不问 ──────────────────────────
                    // 表格不需要配色、不需要页数、也不需要确认大纲——那是给"要排版的
                    // 长东西"准备的。所以这里只有两个芯片：改主题、补要点。
                    Text(
                        text = "表格生成 · " + tableKind.id + " · " +
                            (if (mentionDraft.theme.isBlank()) "主题未填" else "主题 " + mentionDraft.theme),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 「经验」两个字是给用户看的凭据：表头不是随便来的，
                        // 是按主题自己挑的一种常见体裁。
                        ComposerChip("体裁", "经验：" + tableKind.id) {
                            closeComposerPanels(
                                keep = { },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { pointsPanel = false },
                                    { deckPanel = null }, { pagesPanel = false },
                                    { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip("主题", mentionDraft.theme.ifBlank { "未填" }) {
                            themeInput = mentionDraft.theme.ifBlank { mentionDraft.free }
                            closeComposerPanels(
                                keep = { themePanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { pointsPanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                        ComposerChip(
                            "要点",
                            WebTemplates.bulletPoints(mentionDraft.points).size.let {
                                if (it == 0) "未填" else it.toString() + " 条"
                            },
                        ) {
                            pointsInput = mentionDraft.points
                            closeComposerPanels(
                                keep = { pointsPanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                    }
                } else if (copyMention != null) {
                    // ── 文案：摘要 + 一个「重填」入口 ────────────────────────
                    // 表单收完需求就发走了，所以这里只留两件事：看一眼刚才填了什么、
                    // 想改就再点开表单（表单会把已填的值带回来）。
                    val pairs: Map<String, String> = DraftTokens.pairs(mentionDraft.free)
                    val type: String = pairs[Copywriting.MARK_TYPE].orEmpty().ifBlank { "未选" }
                    val point: String = pairs[Copywriting.MARK_POINT].orEmpty()
                    Text(
                        text = "文案撰写 · " + type + " · " +
                            (if (point.isBlank()) "卖点未填" else "卖点 " + point),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ComposerChip("文案表单", type) {
                            // 表单是面板区里的一块，所以先把它之外的面板全关掉，免得叠着。
                            openInlineForm(keep = { openCopyForm(copyMention, mentionDraft) })
                        }
                        ComposerChip(
                            "要点",
                            WebTemplates.bulletPoints(mentionDraft.points).size.let {
                                if (it == 0) "未填" else it.toString() + " 条"
                            },
                        ) {
                            pointsInput = mentionDraft.points
                            closeComposerPanels(
                                keep = { pointsPanel = true },
                                others = arrayOf(
                                    { templatePanel = null }, { colorPanel = false },
                                    { themePanel = false }, { deckPanel = null },
                                    { pagesPanel = false }, { outlinePanel = false },
                                ),
                            )
                        }
                    }
                }

                if (previewOpen) {
                    Text(
                        text = expandedPreview,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 6.dp),
                    )
                }
            }
        }

        if (mentionQuery != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // 这里**不再**自己套一层 220dp 的视口：那层视口正好只放得下 4 行，
                    // 第 5 项「表格生成」起就被裁在输入框后面，而内层又把滚动吃掉，
                    // 于是用户以为菜单里只有 4 条。现在只留外层那一个视口，并给出项数提示。
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (mentionMatches.isEmpty()) {
                    // 空结果也要给人一条出路：把现有模板列出来，而不是留一片空白。
                    Text(
                        text = "没有这个模板",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                    )
                    Text(
                        text = "现在有：" + ChatMentions.all.joinToString("、") { "@" + it.title },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                    )
                } else {
                    Text(
                        text = "共 " + mentionMatches.size + " 项 · 面板可上下滑动",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 6.dp),
                    )
                    mentionMatches.forEach { mention ->
                        // 光标跟着插入内容走：不指定的话它会留在用户敲 @ 的地方，
                        // 下一句主题就打到 @ 和标记之间去了。
                        MentionRow(mention) {
                            val inserted = ChatMentions.insertion(draft.text, mention)
                            draft = TextFieldValue(inserted.text, TextRange(inserted.caret))
                            val picked = MentionDraft.parse(inserted.text)
                            var opened = false
                            // 带资源包的条目（网页生成）接着问骨架、再问配色、再问主题；
                            // 其余条目到这里就结束了，用户直接打字即可。
                            if (mention.templates.isNotEmpty()) {
                                // 从 @ 菜单进来时只开骨架那一块；配色是挑完骨架之后的下一步，
                                // 由骨架选择自己把它打开，不在这里一起铺开。
                                templatePanel = mention.templates
                                colorPanel = false
                                themePanel = false
                                opened = true
                            }
                            // 三张表单（文案 / 图片 / 海报）现在都内联在面板区里，点完就地展开；
                            // 开之前先把其余面板连同另外两张表单一起关掉，真机上叠着放不下。
                            if (mention.copy != null) {
                                openInlineForm(keep = { openCopyForm(mention, picked) })
                                opened = true
                            }
                            // 图片处理是纯本地操作，点完直接开它自己的表单。
                            if (mention.image) {
                                openInlineForm(keep = { imageFormOpen = true })
                                opened = true
                            }
                            // 海报生成也一样：点完开表单，界面上绝不出现底图本身。
                            if (mention.poster) {
                                posterStyleId = PosterStyles.pick(picked.theme + " " + picked.free).id
                                if (posterTitle.isBlank()) posterTitle = picked.theme
                                openInlineForm(keep = { posterFormOpen = true })
                                opened = true
                            }
                            // 剩下几条（PPT / 表格 / 网页之外的本地追问）选完就把**第一问**
                            // 直接问出来。上一版只有点「发送」才问，用户选完模板面对的是
                            // 一片安静，只能自己去点芯片。
                            if (!opened) picked.nextAsk()?.let { ask -> openAsk(ask, picked) }
                        }
                    }
                }
            }
        }

        // ── 骨架选择：资源包里有几套就列几套 ──────────────────────────────
        // 「我们不会只用这个模板，之后会有多个模板进行选择」——所以这里不是二选一，
        // 而是把 assets/templates/web/ 里已经内嵌的那几套全列出来。
        templatePanel?.let { options ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "选一套骨架（内嵌在 App 里，离线可用）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                )
                options.forEach { option ->
                    TemplateRow(option) {
                        val next = MentionDraft.withTemplate(MentionDraft.parse(draft.text), option)
                        draft = caretAtEnd(MentionDraft.render(next))
                        templatePanel = null
                        // 挑完骨架接着固定问配色：这是发送之前的那一次。
                        colorPanel = true
                    }
                }
            }
        }

        // ── 配色：发送之前固定问这一次 ───────────────────────────────────
        if (colorPanel) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(top = 8.dp, bottom = 4.dp),
            ) {
                Text(
                    text = "先用哪套配色？选完会写进上面那行字里，之后还能手改。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                )
                // 8 套配色一行放 4 套、分两行摆完。
                // 上一版是单行 + horizontalScroll：屏幕上只看得见 3 套半（第 4 套「暖橙」被右
                // 边缘裁掉），又没有任何"还能横滑"的提示，用户读到的就是"只有 4 套"。
                ColorSchemes.all.chunked(4).forEach { line ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        line.forEach { scheme ->
                            ColorChip(scheme, selected = mentionDraft.colors?.id == scheme.id) {
                                val next = MentionDraft.withColors(MentionDraft.parse(draft.text), scheme)
                                draft = caretAtEnd(MentionDraft.render(next))
                                colorPanel = false
                                // 接着问下一问：网页那条是主题，PPT 那条是主题之后还有页数。
                                advanceAsk()
                            }
                        }
                    }
                }
                TextButton(
                    onClick = {
                        // 「先不选」也要落成文本里的一套具体配色，否则下次点发送还会再拦一次。
                        val next = MentionDraft.withColors(MentionDraft.parse(draft.text), ColorSchemes.fallback)
                        draft = caretAtEnd(MentionDraft.render(next))
                        colorPanel = false
                        advanceAsk()
                    },
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text("用默认的「" + ColorSchemes.fallback.id + "」", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // ── 主题：本地固定问的第二个问题 ────────────────────────────────
        if (themePanel) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            ) {
                Text(
                    text = "这次的主题是什么？写完会直接填进骨架的大标题（不想填就点跳过，交给模型拟）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = themeInput,
                        onValueChange = { themeInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("例如：校园开放日", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        shape = RoundedCornerShape(9.dp),
                    )
                    TextButton(
                        onClick = {
                            val md = MentionDraft.parse(draft.text)
                            val typed = themeInput.trim()
                            if (typed.isNotEmpty()) {
                                draft = caretAtEnd(MentionDraft.render(MentionDraft.withTheme(md, typed)))
                            }
                            themePanel = false
                            // 答完主题接着问下一问（PPT 是配色，网页是配色，表格就没有了）。
                            advanceAsk()
                        },
                    ) {
                        Text("确定", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(
                    onClick = {
                        themePanel = false
                        advanceAsk()
                    },
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text("跳过，交给模型拟", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // ── 要点：和主题一样的一个小输入框 ────────────────────────────
        // 这里原来点一下会弹出**整段指令全文**（previewOpen = true）：一大段没用的字，
            // 既不能打字也不是用户想要的。它本来就该和主题并列——一个格子，打了就填进卡片，
        // 不打也能照发。想看全文有「看全文」，两件事不该共用一个入口。
        if (pointsPanel) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            ) {
                Text(
                    text = "这次要讲哪几个要点？一条一条写，用「、」隔开就会分成几张卡片" +
                        "（不想填就点跳过，交给模型按主题拟）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = pointsInput,
                        onValueChange = { pointsInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("例如：专业介绍、实验室开放", style = MaterialTheme.typography.bodySmall)
                        },
                        textStyle = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        shape = RoundedCornerShape(9.dp),
                    )
                    TextButton(
                        onClick = {
                            val md = MentionDraft.parse(draft.text)
                            val typed = pointsInput.trim()
                            if (typed.isNotEmpty()) {
                                draft = caretAtEnd(MentionDraft.render(MentionDraft.withPoints(md, typed)))
                            }
                            pointsPanel = false
                        },
                    ) {
                        Text("确定", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = { pointsPanel = false }, modifier = Modifier.padding(start = 4.dp)) {
                    Text("跳过，交给模型按主题拟", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // ── PPT：模板选择（默认那一套是 App 按主题自动挑的） ──────────────
        deckPanel?.let { options ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "选一套版式（内嵌在 App 里，离线可用；「自动」那套是 App 按你写的主题挑的）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                )
                options.forEach { option ->
                    DeckRow(option) {
                        // 换模板要**连带作废旧大纲**：章节结构换了，旧大纲的页数分配
                        // 和章节名就不再对得上，留着比没有更糟。
                        val next = MentionDraft.withOutline(
                            MentionDraft.withDeck(MentionDraft.parse(draft.text), option),
                            emptyList(),
                        )
                        draft = caretAtEnd(MentionDraft.render(next))
                        deckPanel = null
                        outlineRows = emptyList()
                        outlineInputs = emptyList()
                    }
                }
            }
        }

        // ── PPT：页数 ──────────────────────────────────────────────────
        if (pagesPanel) {
            val deckNow = MentionDraft.parse(draft.text).deck
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            ) {
                Text(
                    text = "这次做几页？页数决定大纲里每部分分到几页" +
                        "（章节比页数还多时会以章节为准，封面和结尾不能没有）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 预设直接给数字，省得在手机上敲键盘——大多数人就用这几个。
                    (deckNow?.pagePresets ?: emptyList()).forEach { preset ->
                        val picked = pagesInput.trim() == preset.toString()
                        Text(
                            text = preset.toString() + " 页",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (picked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { pagesInput = preset.toString() }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = pagesInput,
                        // 只收数字：页数是个数，让人先打错再报错不如根本打不进去。
                        onValueChange = { typed -> pagesInput = typed.filter { it.isDigit() }.take(2) },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "页数，例如 " + (deckNow?.defaultPages ?: 12),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        shape = RoundedCornerShape(9.dp),
                    )
                    TextButton(
                        onClick = {
                            val n: Int? = pagesInput.trim().toIntOrNull()
                            if (n != null && n > 0) {
                                // 页数一改，旧大纲的分配就不成立了，同样作废重铺。
                                val next = MentionDraft.withOutline(
                                    MentionDraft.withPages(MentionDraft.parse(draft.text), n),
                                    emptyList(),
                                )
                                draft = caretAtEnd(MentionDraft.render(next))
                                outlineRows = emptyList()
                                outlineInputs = emptyList()
                            }
                            pagesPanel = false
                            // 页数是最后一问：接着把大纲铺出来给他确认。
                            advanceAsk()
                        },
                    ) {
                        Text("确定", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(
                    onClick = {
                        pagesPanel = false
                        advanceAsk()
                    },
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text(
                        "跳过，用模板默认页数",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // ── 三个内联表单：贴着输入框就地展开，不再是独立悬浮窗 ────────────────
        // 用户点名"不要单独设置悬浮 UI 窗口，都在文本框那里弹出操作就可以"：悬浮窗正好压住
        // 聊天气泡，而这里是「网页生成」那套追问用的同一块地方。三张表单互斥，同时只开一块。

        // 海报生成：风格 + 主标题 + 副标题 + 场景。
        if (posterFormOpen) {
            PosterFormDialog(
                styleId = posterStyleId,
                title = posterTitle,
                subtitle = posterSubtitle,
                scene = posterScene,
                busy = posterBusy,
                onStyle = { posterStyleId = it },
                onTitle = { posterTitle = it },
                onSubtitle = { posterSubtitle = it },
                onScene = { posterScene = it },
                onCancel = { posterFormOpen = false },
                onStart = { submitPosterForm() },
            )
        }

        // 图片处理：选图、质量、格式、水印，点一下就在手机上做完。
        if (imageFormOpen) {
            ImageFormDialog(
                pickedCount = imageUris.size,
                quality = imageQuality,
                format = imageFormat,
                watermark = imageWatermark,
                busy = imageBusy,
                progress = imageProgress,
                onPickImages = { imagePicker.launch(arrayOf("image/*")) },
                onQuality = { imageQuality = it },
                onFormat = { imageFormat = it },
                onWatermark = { imageWatermark = it },
                onCancel = { imageFormOpen = false },
                onStart = { submitImageForm() },
            )
        }

        // 文案类型：原来是 ChoicePickerDialog（带搜索框的独立浮层，压住聊天气泡）。
        // 现在就是面板里的一行 chips——点一下直接选中，不用先弹窗再收回。
        if (copyFormFor != null && copyTypePicker) {
            val typeFields: CopyFieldSet = copyFormFor?.copy ?: Copywriting.FIELDS
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "选一个文案类型 · 点一下就用",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    typeFields.types.forEach { option ->
                        ComposerChip(option, if (option == copyType) "已选" else "点我") {
                            copyType = option
                            copyTypePicker = false
                        }
                    }
                }
            }
        }

        // 文案撰写：类型、卖点、语气三栏，填完点「开始生成」直接拼指令发出去。
        copyFormFor?.let { mention ->
            CopyFormDialog(
                fields = mention.copy ?: Copywriting.FIELDS,
                type = copyType,
                point = copyPoint,
                tone = copyTone,
                onPickType = { copyTypePicker = true },
                onPoint = { copyPoint = it },
                onTone = { copyTone = it },
                onCancel = { copyFormFor = null },
                onStart = { submitCopyForm() },
            )
        }

        // ── PPT：大纲（逐条可改；确认之后才真的发出去） ──────────────────
        if (outlinePanel) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            ) {
                Text(
                    text = "确认一下大纲：左边是页数，右边写「章节|这几页讲什么」。" +
                        "灰字是模板建议，直接打字就覆盖；不改就用建议的内容。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                // 「确认」放在**面板顶部**：底部那份要滚到底才能点到，而面板里全是输入框，
                // 手指落在输入框上拖动只会选中文字、滚不动，于是按钮永远够不着（真机实测）。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            val md = MentionDraft.parse(draft.text)
                            val rows = outlineRows.mapIndexed { index, row ->
                                parseRowText(outlineInputs.getOrNull(index).orEmpty(), row)
                            }
                            val confirmed = MentionDraft.render(MentionDraft.withOutline(md, rows))
                            draft = caretAtEnd(confirmed)
                            outlineRows = rows
                            outlinePanel = false
                            sendDraftExpanded(confirmed)
                        },
                    ) {
                        Text(
                            "确认，开始生成",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                outlineInputs.forEachIndexed { index, value ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        Text(
                            text = outlineRows.getOrNull(index)?.range.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.width(40.dp),
                        )
                        OutlinedTextField(
                            value = value,
                            onValueChange = { typed ->
                                // 只改这一条：其余各条原样不动——「单独修改某条」就是这个意思。
                                val list = outlineInputs.toMutableList()
                                if (index in list.indices) {
                                    list[index] = typed
                                    outlineInputs = list
                                }
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                // 提示就是模板建议的那一条，但**只是灰字**：用户直接打字即可，
                                // 不用先删掉预填内容（用户原话："还要把那些删掉再写自己要的"）。
                                Text(
                                    text = outlineRows.getOrNull(index)?.let { rowText(it) }
                                        ?: "章节|这几页讲什么",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                )
                            },
                            textStyle = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            shape = RoundedCornerShape(9.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            // 重铺一份：用户把某几条改乱了、或者改了主题要点想从头来。
                            val fresh = composeOutlineRows(MentionDraft.parse(draft.text))
                            outlineRows = fresh
                            outlineInputs = fresh.map { "" }
                        },
                    ) {
                        Text("重新生成大纲", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            // 确认 = 把改过的每一条写回草稿，然后**立刻发出去**。
                            // 写到文本里，用户之后还能直接在输入框里改某一行；
                            // 而"开始生成"这四个字必须真的开始生成——上一版只收起面板，
                            // 消息还留在输入框里，用户以为已经在跑了。
                            val md = MentionDraft.parse(draft.text)
                            val rows = outlineRows.mapIndexed { index, row ->
                                parseRowText(outlineInputs.getOrNull(index).orEmpty(), row)
                            }
                            val confirmed = MentionDraft.render(MentionDraft.withOutline(md, rows))
                            draft = caretAtEnd(confirmed)
                            outlineRows = rows
                            outlinePanel = false
                            sendDraftExpanded(confirmed)
                        },
                    ) {
                        Text(
                            "确认，开始生成",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        }

        // `/` opens the conversation's own command panel. It lists what TermDesk
        // can actually do to THIS conversation - not a pretend copy of a kernel's
        // menu - and everything in it does something real when tapped.
        if (draft.text.startsWith("/") && !draft.text.contains(' ') && !draft.text.contains('\n')) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // The panel now carries the kernel's own commands as well as the
                    // four local ones, and with the keyboard up the lower rows were
                    // simply cut off. Cap it and let it scroll instead of hiding
                    // entries the user was told exist.
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                SlashRow("模型", if (modelOptions.isEmpty()) "此助手未提供模型列表" else chosenModelLabel, modelOptions.isNotEmpty()) {
                    updateDraft("")
                    modelPickerOpen = true
                }
                SlashRow("权限模式", if (modeOptions.isEmpty()) "此助手不支持切换权限模式" else chosenModeLabel, modeOptions.isNotEmpty()) {
                    updateDraft("")
                    modePickerOpen = true
                }
                SlashRow("附件", "上传至当前设备，随消息发送", true) {
                    updateDraft("")
                    filePicker.launch(arrayOf("*/*"))
                }
                SlashRow("关闭会话", "结束这个对话", true) {
                    updateDraft("")
                    onClose(chat.id)
                }
                // Commands the PC's kernel actually declares (DSH: /compact,
                // /export, /goal, /permission, ...). Tapping one puts the line in
                // the box instead of running it: compacting a conversation by
                // accident is not undoable, so the last step stays the user's.
                val declared = commands
                if (declared != null && declared.commands.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    for (command in declared.commands) {
                        val hint = command.hint?.takeIf { it.isNotBlank() }
                            ?: command.description.takeIf { it.isNotBlank() }
                            ?: "由 " + chat.engine + " 执行"
                        SlashRow(command.name, hint, true) {
                            updateDraft("/" + command.name + " ")
                        }
                    }
                } else if (!declared?.note.isNullOrBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    SlashRow("命令", declared?.note ?: "", false) {}
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Composer(
            draft = draft,
            onDraftChange = { updateDraftValue(it) },
            onAttach = { attachChooserOpen = true },
            sending = sending,
            running = chat.isRunning,
            // No `connected` here. With the link down this used to grey the arrow out and swallow
            // the tap, which is precisely the state the offline queue exists for: the message is
            // held on the phone, the "待发 N 条 · 连上就发" line explains it, and it goes out when
            // the link returns. A dead button taught the opposite.
            canSend = !chat.ownership.isBusy && (chat.ready || chat.isRunning || chat.status != "stopped"),
            pendingCount = pendingSends,
            droppedCount = pendingDropped,
            onSend = {
                val text = draft.text.trim()
                if (text.isNotEmpty()) {
                    // 点了发送不等于发出去：这一次必须先把欠用户的那个问题问掉。
                    // 欠哪几问、什么顺序，由模板自己的 localAsks 决定（[MentionDraft.nextAsk]）：
                    //   · 「配色」是硬拦——它决定整页的取色，缺了就只能靠模型猜；
                    //   · 「主题」「页数」只固定问一次，用户跳过就照发；
                    //   · 「大纲」是 PPT 特有的硬拦——用户逐条改过的东西没确认之前
                    //     不能发，否则模型会自己重排一遍，他改的那几条就白改了。
                    val pending = MentionDraft.parse(text)
                    val ask: String? = pending.nextAsk()
                    when {
                        ask == MentionDraft.LOCAL_COLORS -> {
                            colorPanel = true; templatePanel = null; themePanel = false
                            pointsPanel = false; deckPanel = null; pagesPanel = false
                            outlinePanel = false
                        }
                        ask == MentionDraft.LOCAL_THEME && !themeAsked -> {
                            themeAsked = true
                            themeInput = pending.theme.ifBlank { pending.free }
                            themePanel = true; templatePanel = null; colorPanel = false
                            pointsPanel = false; deckPanel = null; pagesPanel = false
                            outlinePanel = false
                        }
                        ask == MentionDraft.LOCAL_PAGES && !pagesAsked -> {
                            pagesAsked = true
                            pagesInput = pending.pages
                            pagesPanel = true; templatePanel = null; colorPanel = false
                            themePanel = false; pointsPanel = false; deckPanel = null
                            outlinePanel = false
                        }
                        // 海报生成也是纯本地操作：点发送就等于"我要做海报"，开内联表单。
                        pending.mention?.poster == true -> {
                            openInlineForm(keep = { posterFormOpen = true })
                        }
                        // 图片处理是纯本地操作：不该把 `@图片处理` 当消息发出去，
                        // 点发送就等价于"我要处理图片"，把内联表单开出来。
                        pending.mention?.image == true -> {
                            openInlineForm(keep = { imageFormOpen = true })
                        }
                        // 文案：表单没填全就先把它开出来，不要发一段没头没尾的指令出去。
                        // 走到这里通常是因为用户是手打 `@文案撰写` 进来的，没经过菜单。
                        pending.mention?.copy != null && !copyFormFilled(pending) -> {
                            openInlineForm(keep = { openCopyForm(pending.mention!!, pending) })
                        }
                        pending.needsOutlineAsk() -> {
                            // PPT 走到这里说明基础信息齐了：**在本地铺一份大纲**给用户改。
                            // 不花模型回合，点一下就有东西可看、可改——这就是「快速形成大纲」。
                            if (outlineInputs.isEmpty()) {
                                val fresh = composeOutlineRows(pending)
                                outlineRows = fresh
                                outlineInputs = fresh.map { "" }
                            }
                            outlinePanel = true; templatePanel = null; colorPanel = false
                            themePanel = false; pointsPanel = false; deckPanel = null
                            pagesPanel = false
                        }
                        else -> {
                            sendDraftExpanded(text)
                        }
                    }
                }
            },
            onCancel = { onCancel(chat.id) },
        )

        // 加号点开的小选择：先问一句"相册还是文件"，再跳对应的系统选择器。
        if (attachChooserOpen) {
            AlertDialog(
                onDismissRequest = { attachChooserOpen = false },
                title = { Text("上传附件", style = MaterialTheme.typography.titleSmall) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            text = "选一个来源，下一步会跳到手机对应的选择界面。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        ComposerChip("相册", "照片 / 截图") {
                            attachChooserOpen = false
                            photoPicker.launch("image/*")
                        }
                        Spacer(Modifier.height(6.dp))
                        ComposerChip("文件", "内存 / 微信 / QQ") {
                            attachChooserOpen = false
                            filePicker.launch(arrayOf(MIME_ANY))
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { attachChooserOpen = false }) { Text("取消") }
                },
            )
        }

        // 海报生成的表单已经搬进上面的面板区；这里只剩"点开看大图"——
        // 那是看和下载的动作，整屏一张图才看得清，和填需求不是一回事。
        posterViewing?.let { file ->
            PosterPreviewDialog(
                file = file,
                onClose = { posterViewing = null },
                onDownload = { downloadPoster(file) },
                onTweak = { tweakPoster(it) },
            )
        }

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
            // A fixed 96dp column broke names longer than it ("permission" came
            // out as "/", "permissio", "n"). The label takes the width it needs
            // and the hint gets the rest, so a long command stays one line.
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 96.dp).padding(end = 10.dp),
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

/** One line in the `@` panel: what it is, what you get back, and it needs nothing from you. */
@Composable
private fun MentionRow(mention: ChatMention, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = mention.badge,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "@" + mention.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = mention.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One line in the skeleton picker: what it looks like, and that it ships inside the app. */
@Composable
private fun TemplateRow(template: PageTemplate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = template.badge,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = template.id,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = template.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 一套 PPT 蓝图在面板里的一行。
 *
 * 和 [TemplateRow] 同一个形状，只是数据源不同（PPT 的资源是一份章节蓝图，
 * 不是一份 HTML）。两套模板各自有自己的行，是因为它们的字段本来就不同——
 * 硬合成一个反而要到处写分支。
 */
@Composable
private fun DeckRow(deck: DeckTemplate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = deck.badge,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = deck.id,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = deck.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One palette in the colour picker.
 *
 * The four squares are the actual colours the page will use — bg, card, primary, text —
 * so choosing is looking, not reading. Naming a colour ("科技蓝") is not enough to know
 * what the page will look like, and that gap is exactly what the user reported.
 */
@Composable
private fun ColorChip(scheme: ColorScheme, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            scheme.swatch.forEach { hex ->
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(parseHex(hex)),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = (if (selected) "✓ " else "") + scheme.id,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** `#RRGGBB` / `#AARRGGBB` → Compose 的 Color；认不出来给中性灰，绝不抛。 */
private fun parseHex(hex: String): Color {
    val body: String = hex.removePrefix("#")
    val value: Long = body.toLongOrNull(16) ?: return Color(0xFF888888)
    return when (body.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> Color(0xFF888888)
    }
}

/**
 * 把重新渲染好的草稿装回输入框，**光标放到末尾**。
 *
 * 这不是讲究：`TextFieldValue(text)` 的默认选区是 `TextRange(0)`——也就是把光标扔回
 * 最前面。真机上就是这个症状：改一次配色，光标跳到行首，接着打的字全插到了
 * `@网页生成` 前面。挑模板那条路径早就为同一件事踩过一次坑（见 `ChatMentions.insertion`），
 * 所以凡是由程序主动换掉草稿的地方，都必须自己把光标位置一并给出来。
 */
private fun caretAtEnd(text: String): TextFieldValue = TextFieldValue(text, TextRange(text.length))

/**
 * 预览条上那句「还有几处要交给模型补」。
 *
 * 数字来自**真正填过的那份骨架**，不是估计：剩下几个 `{{...}}` 就是几个。
 * 用户能一眼看出这次到底是「骨架基本填好了、模型只补空」还是「全交给它写」。
 */
private fun remainingHint(filledSkeleton: String): String {
    if (filledSkeleton.isEmpty()) return " · 骨架没读到，这次让模型自己写"
    val left = WebTemplates.remainingSlots(filledSkeleton)
    return if (left > 0) " · 还有 " + left + " 处交给模型补" else " · 槽位已填满"
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
    /**
     * 文本与光标一起传进来。
     *
     * 这里收 `TextFieldValue` 而不是 `String`，是因为「@模板」这条路径要**指定光标位置**：
     * 只传 String 的话，输入框在外面换掉文本时会保留原来的选区，插入完标记光标还停在
     * 用户敲 @ 的地方（见 `ChatMentions.insertion`）。正常打字时这一对原样透传，
     * 不改任何行为。
     */
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    sending: Boolean,
    running: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    /** 输入框左边那个圆形加号：点了先问「相册还是文件」。 */
    onAttach: (() -> Unit)? = null,
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
        // 输入框左边一个圆形加号：上传附件的第一入口。
        // 点了先问「相册还是文件」，再跳到对应的系统选择器（相册、内存、微信/QQ 文件都能到）。
        if (onAttach != null) {
            IconButton(
                onClick = onAttach,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "上传附件",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
        }
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
        if (!running || draft.text.isNotBlank()) {
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
            IconButton(onClick = onSend, enabled = canSend && draft.text.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = if (running) "发送（排队）" else "发送",
                    tint = if (canSend && draft.text.isNotBlank()) {
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

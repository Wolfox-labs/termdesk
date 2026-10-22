package dev.termdesk.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.termdesk.app.data.parsePairUri
import kotlinx.coroutines.launch

import android.app.NotificationManager
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.termdesk.app.data.AgentNotification
import dev.termdesk.app.data.AgentNotifications
import dev.termdesk.app.data.BatteryGuidance
import dev.termdesk.app.data.ConnectionService
import dev.termdesk.app.data.LinkState
import dev.termdesk.app.data.OpenRequest
import dev.termdesk.app.data.PairRequest
import dev.termdesk.app.data.SessionInfo

/** Same tag as the receiving side, so one logcat filter shows the whole path. */
private const val TAG_NOTIFY = "TermDeskNotify"

@Composable
fun AppRoot(
    vm: AppViewModel = viewModel(),
    pairRequest: PairRequest? = null,
    onPairHandled: () -> Unit = {},
    /** A conversation a notification tap asked for, if the launch carried one. */
    openRequest: OpenRequest? = null,
    onOpenHandled: () -> Unit = {},
) {
    val link by vm.link.collectAsState()
    val status by vm.status.collectAsState()
    val processes by vm.processes.collectAsState()
    val services by vm.services.collectAsState()
    val loading by vm.loading.collectAsState()
    val inventoryError by vm.inventoryError.collectAsState()
    val lastAction by vm.lastAction.collectAsState()
    val pendingNotifications by vm.pendingNotifications.collectAsState()
    val listing by vm.listing.collectAsState()
    // Collected so startPath recomputes once the agent reports its roots.
    val roots by vm.fsRoots.collectAsState()
    val openFile by vm.openFile.collectAsState()
    val preview by vm.preview.collectAsState()
    val search by vm.search.collectAsState()
    val searching by vm.searching.collectAsState()
    val storage by vm.storage.collectAsState()
    val localKernel by vm.localKernel.collectAsState()
    val transfer by vm.transfer.collectAsState()
    val termLines by vm.termLines.collectAsState()
    val kernelTarget by vm.kernelTarget.collectAsState()
    val termSession by vm.termSession.collectAsState()
    val termCwd by vm.termCwd.collectAsState()
    val termSessions by vm.termSessions.collectAsState()
    val termBusy by vm.termBusy.collectAsState()
    val termUnavailable by vm.termUnavailable.collectAsState()
    val codexConfig by vm.codexConfig.collectAsState()
    val codexTemplates by vm.codexTemplates.collectAsState()
    val codexTarget by vm.codexTarget.collectAsState()
    val computers by vm.computerList.collectAsState()
    val activeComputerId by vm.activeComputerId.collectAsState()
    val chats by vm.chats.collectAsState()
    val chatModels by vm.chatModels.collectAsState()
    val chatCommands by vm.chatCommands.collectAsState()
    val lastUpload by vm.lastUpload.collectAsState()
    val activeChat by vm.activeChat.collectAsState()
    val chatEvents by vm.chatEvents.collectAsState()
    val chatSending by vm.chatSending.collectAsState()
    val approvals by vm.approvals.collectAsState()
    val workspaces by vm.workspaces.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val sessionDetail by vm.sessionDetail.collectAsState()
    val kernelRuns by vm.kernelRuns.collectAsState()
    val kernelRunsNote by vm.kernelRunsNote.collectAsState()
    val pendingSends by vm.pendingCount.collectAsState()
    val pendingDropped by vm.pendingDropped.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val uiLayout by vm.uiLayout.collectAsState()
    val palette by vm.palette.collectAsState()
    val chatHasOlder by vm.chatHasOlder.collectAsState()
    val featurePacks by vm.featurePacks.collectAsState()
    val engines by vm.engines.collectAsState()
    val defaultEngine by vm.defaultEngine.collectAsState()
    val sessionSort by vm.sessionSort.collectAsState()
    val workingDirectory by vm.workingDirectory.collectAsState()
    val chatTerminals by vm.chatTerminals.collectAsState()
    val terminalView by vm.terminalView.collectAsState()

    /**
     * What a new conversation runs on.
     *
     * The kernel is a standing choice made on the home page, not a question asked
     * at every "new conversation"; when nothing has been chosen yet, the first
     * kernel this machine can actually drive is used instead of a guess.
     */
    val selectableEngineIds = engines.filter { it.selectable }.map { it.id }.toSet()
    val engineForNewChat = defaultEngine?.takeIf { it in selectableEngineIds }
        ?: engines.firstOrNull { it.selectable }?.id
    val canCreateChat = link is LinkState.Connected && engineForNewChat != null

    // A stale preference must never win over the current agent's discovery result.
    // Do this only after discovery has returned; an empty list during connection is
    // not evidence that the saved preference is invalid.
    LaunchedEffect(kernelTarget, engines, defaultEngine) {
        if (engines.isNotEmpty() && defaultEngine != engineForNewChat) {
            vm.setDefaultEngine(engineForNewChat)
        }
    }


    val snackbarHostState = remember { SnackbarHostState() }

    // Which section is open lives here, not inside AppShell: a file viewer
    // replaces the whole shell, and a section remembered
    // inside it would silently reset to 会话 every time one was opened.
    var section by rememberSaveable { mutableStateOf(Section.Home) }
    var connectionOpen by remember { mutableStateOf(vm.savedToken.isBlank()) }


    // A scanned pairing link carries everything the app needs: address and
    // token. It is the only path that does not involve typing a secret.
    //
    // 两条来源汇合到这里：系统相机把链接当 intent 交进来（pairRequest），
    // 或者 App 自己扫完（scannedPair）。解析同一个函数，后续处理也同一段。
    var scannedPair by remember { mutableStateOf<PairRequest?>(null) }
    val scanScope = rememberCoroutineScope()
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents
        val parsed = text?.let { parsePairUri(it) }
        if (parsed == null) {
            if (text != null) {
                scanScope.launch { snackbarHostState.showSnackbar("这不是 TermDesk 的配对码") }
            }
        } else {
            scannedPair = parsed
        }
    }
    val effectivePair = pairRequest ?: scannedPair
    LaunchedEffect(effectivePair) {
        val request = effectivePair ?: return@LaunchedEffect
        if (pairRequest != null) onPairHandled() else scannedPair = null
        if (!request.isUsable) return@LaunchedEffect
        connectionOpen = false
        // The PC hands over its own LAN/Tailscale addresses at pairing time; once
        // the relay is down, nothing can tell this phone where else to look.
        vm.connect(request.url, request.token, request.relay, request.more)
        snackbarHostState.showSnackbar("已通过二维码配对 · ${request.name ?: request.url}")
    }

    // ---- notifications ---------------------------------------------------------
    // The PC decides WHAT is worth telling the owner (pc-agent/src/notify.js). The only
    // judgement left on this side is whether they are already looking at that very
    // conversation, which the PC cannot know. An approval is never suppressed: the kernel is
    // blocked until somebody answers it.
    var backgroundGuideOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        AgentNotifications.ensureChannel(context)
        Log.i(TAG_NOTIFY, "通知渠道已就绪（${AgentNotifications.CHANNEL_ID}）")
    }

    val rootView = LocalView.current
    val lifecycleOwner = remember(rootView) { rootView.findViewTreeLifecycleOwner() }
    // `!= false` on purpose: with no owner to ask, assume the window is visible. Swallowing a
    // notification is worse than showing one at a slightly wrong moment.
    var windowVisible by remember(lifecycleOwner) {
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
                val now = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                if (now != windowVisible) Log.i(TAG_NOTIFY, if (now) "窗口回到前台" else "窗口退到后台")
                windowVisible = now
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    // ---- notifications ---------------------------------------------------------
    // Approval notifications are tracked so they can be taken back. Two facts are kept
    // apart, because they are different evidence: "this question was pending a moment ago"
    // (a single frame said so) and "the agent has stated the whole pending list" (which can
    // clear a question that was settled while this phone was away).
    var openApprovals by remember { mutableStateOf<List<AgentNotification>>(emptyList()) }
    var seenPending by remember { mutableStateOf<Set<String>>(emptySet()) }
    val approvalsEpoch by vm.approvalsEpoch.collectAsState()
    LaunchedEffect(approvals) { seenPending = seenPending + approvals.map { it.requestId } }

    LaunchedEffect(pendingNotifications, windowVisible, section, activeChat?.id) {
        if (pendingNotifications.isEmpty()) return@LaunchedEffect
        val manager = context.getSystemService(NotificationManager::class.java)
        for (note in pendingNotifications) {
            val looking = windowVisible && section == Section.Sessions &&
                note.chatId != null && note.chatId == activeChat?.id
            if (looking && !note.isApproval) {
                Log.i(TAG_NOTIFY, "不打扰：正在看这个会话（${note.id}）")
                continue
            }
            manager?.notify(AgentNotifications.idFor(note), AgentNotifications.build(context, note))
            // The line that separates "the notification path broke" from "the system did not
            // show what it was handed". Only logcat can tell those apart.
            Log.i(TAG_NOTIFY, "投递 ${note.id} ${note.kind}（窗口可见=$windowVisible，manager=${manager != null}）")
            if (note.isApproval && note.requestId != null) {
                openApprovals = openApprovals.filterNot { it.requestId == note.requestId } + note
            }
        }
        vm.consumeNotifications()
    }

    // ---- a question that is no longer being asked ---------------------------------
    // "The kernel is waiting for you" is only true while it is. Left alone, the notification
    // outlives the wait: answered from this phone, answered from the desktop, or timed out,
    // and it still says somebody is blocked.
    LaunchedEffect(approvals, approvalsEpoch) {
        if (openApprovals.isEmpty()) return@LaunchedEffect
        val live = approvals.map { it.requestId }.toSet()
        val gone = openApprovals.filter { note ->
            val id = note.requestId ?: return@filter false
            val listed = approvalsEpoch > 0
            (id in seenPending || listed) && id !in live
        }
        if (gone.isEmpty()) return@LaunchedEffect
        val manager = context.getSystemService(NotificationManager::class.java)
        for (note in gone) {
            manager?.cancel(AgentNotifications.idFor(note))
            Log.i(TAG_NOTIFY, "撤销 ${note.id}：这个问题已经有人答了")
        }
        openApprovals = openApprovals - gone.toSet()
    }

    // A tap on one of those notifications asks for a specific conversation. It may be gone —
    // the agent restarted, or the chat was closed while the phone was away — in which case
    // the list is shown rather than an error about something nobody asked for by name.
    LaunchedEffect(openRequest, chats) {
        val target = openRequest ?: return@LaunchedEffect
        onOpenHandled()
        section = Section.Sessions
        if (chats.any { it.id == target.chatId }) vm.openChat(target.chatId)
    }
    val connected = link is LinkState.Connected
    // On-demand repair; stream events carry normal updates. Returning to the
    // foreground or reconnecting must also repair changed/deleted old events.
    LaunchedEffect(connected, activeChat?.id, windowVisible) {
        if (connected && windowVisible) activeChat?.id?.let(vm::syncChat)
    }
    // Kernel discovery is metadata only (it never runs a model), so it can be
    // refreshed on every connect: Settings and the new-chat sheet then show what
    // this machine can actually talk to instead of a guessed list. The recorded
    // session index is read at the same moment because the home page shows it as
    // "what was running on this machine".
    LaunchedEffect(connected) {
        if (connected) {
            vm.loadEngines()
            vm.loadSessions()
        }
    }

    // The conversation's own panel offers the model / effort pickers, so the
    // Codex catalog has to exist as soon as a Codex conversation is open.
    LaunchedEffect(activeChat?.engine) {
        if (activeChat?.engine == "codex") vm.loadCodexConfig()
    }

    // "What is running over there" is NOT asked for here any more. It was asked once
    // per connection, which meant the answer was a snapshot from connect time — and
    // that is exactly the snapshot that showed nothing while a desktop application
    // was running a conversation. The section that draws the list now refreshes it
    // while it is on screen (see the effect in ChatSection), so the question is asked
    // when somebody is actually looking at the answer.

    val hostname = if (kernelTarget == "local") "本机手机" else when (val state = link) {
        is LinkState.Connected -> state.hostname
        is LinkState.NodeOffline -> state.hostname
        else -> "TermDesk"
    }
    val connectionLabel = when (val state = link) {
        is LinkState.NodeOffline -> "VPS 已连接 · ${state.hostname} 内核离线，上线后自动恢复"
        // 这里原来写「正在连接 · 已缓存内容仍可查看」，但同一屏的正文写着
        // 「未连接电脑」「在设备管理中连接电脑」——一句话两种状态。统一成一句。
        is LinkState.Connecting -> "正在连接电脑…"
        is LinkState.ProtocolMismatch -> "版本不匹配 · ${state.reason}"
        is LinkState.Failed -> "暂时无法连接 · 点击管理设备"
        else -> "离线模式 · 点击连接或管理设备"
    }

    // ---- staying awake while the window is away --------------------------------
    // Measured on the device: backgrounded, the frames the PC sent were written to a socket
    // that was still open, and the phone did nothing with them until the window came back —
    // Android freezes the process and the bytes wait in the buffer. So the app asks for a
    // foreground service exactly while it is off screen AND the link is up: that is the one
    // state where it is doing something the owner asked for (waiting to say the answer is
    // ready) rather than merely existing.
    //
    // Stopping it is not a detail: a "connected" notice left over a link that is gone is a
    // notification the user cannot get rid of without turning the feature off. The "不用了"
    // action stops the service only; the link is the app's business and survives until the
    // window comes back or the connection drops, whichever happens first.
    LaunchedEffect(connected, windowVisible) {
        if (connected && !windowVisible) {
            ConnectionService.start(context, hostname)
            Log.i(TAG_NOTIFY, "前台服务：窗口在后台且已连接，开始守着这条连接")
        } else {
            ConnectionService.stop(context)
        }
    }

    // The cause of "my notifications arrive late", said once per launch to somebody who is
    // looking at the app: the notification shade already offers the same door, and an owner who
    // never opens the shade would otherwise only ever see the symptom.
    LaunchedEffect(connected, windowVisible) {
        if (!connected || !windowVisible || BatteryGuidance.isExempt(context)) return@LaunchedEffect
        Log.i(TAG_NOTIFY, "系统未放行后台运行，已提示去设置")
        // An explicit duration: with an action label Material3 defaults to Indefinite, which
        // pinned this over the bottom of the screen for as long as the app was open — the last
        // lines of a transcript could not be reached, and a hint nobody can dismiss is worse
        // than no hint. The notification keeps the same door for anyone who wants it later.
        val answer = snackbarHostState.showSnackbar(
            message = "系统可能不允许 TermDesk 在后台运行，通知会晚到",
            actionLabel = "去设置",
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (answer == SnackbarResult.ActionPerformed) {
            backgroundGuideOpen = true
        }
    }

    if (backgroundGuideOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { backgroundGuideOpen = false },
            title = { Text("允许后台运行") },
            text = { Text("为及时收到消息，请在系统设置中允许 TermDesk 后台运行。\n\n小米 / Redmi 可检查“省电策略”和“后台自启动”；OPPO 可检查“允许后台活动”。菜单名称以当前系统为准。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    backgroundGuideOpen = false
                    runCatching { context.startActivity(BatteryGuidance.intentFor(context)) }
                }) { Text("打开系统设置") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { backgroundGuideOpen = false }) { Text("稍后") }
            },
        )
    }

    // Surface every action outcome, including refusals, so the user is never
    // left wondering whether a tap did anything.
    LaunchedEffect(lastAction) {
        val action = lastAction ?: return@LaunchedEffect
        if (action.message.isNotEmpty()) {
            snackbarHostState.showSnackbar(
                message = action.message,
                duration = SnackbarDuration.Short,
            )
        }
        vm.clearLastAction()
    }

    // Back dismisses what is on top, in order: an open file, then a preview.
    // Deeper layers (drawer, conversation, section) register their own handlers
    // and win by being composed later. Only when nothing is open does the press
    // reach the OS and leave the app.
    BackHandler(enabled = openFile != null || preview != null) {
        when {
            preview != null -> vm.closePreview()
            else -> vm.closeOpenFile()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            connectionOpen -> ConnectionScreen(
                link = link,
                initialUrl = vm.savedUrl,
                // Deliberately empty: the saved credential is used when the box is
                // left alone, and typing over it must be a decision, not a default.
                initialToken = "",
                computers = vm.computerList.collectAsState().value,
                activeId = vm.activeComputerId.collectAsState().value,
                onSelect = { id -> connectionOpen = false; vm.selectComputer(id) },
                onForgetComputer = vm::forgetComputer,
                onConnect = { url, token -> connectionOpen = false; vm.connect(url, token) },
                onClose = { connectionOpen = false },
                onDisconnect = vm::disconnect,
                onForget = { vm.forgetDevice(); connectionOpen = true },
                onScan = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("对准电脑上的配对二维码")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false),
                    )
                },
            )

            openFile != null -> FileEditor(
                file = openFile!!,
                saveResult = lastAction,
                connected = connected,
                onSave = vm::writeFile,
                onClose = vm::closeOpenFile,
            )

            else -> AppShell(
                status = status,
                hostname = hostname,
                processes = processes,
                services = services,
                inventoryError = inventoryError,
                listing = listing,
                loading = loading,
                transfer = transfer,
                startPath = vm.startPath,
                onRefreshProcesses = vm::refreshProcesses,
                onRefreshServices = vm::refreshServices,
                onKillProcess = vm::killProcess,
                onServiceAction = vm::controlService,
                onNavigate = vm::listDirectory,
                onOpenFile = { entry -> vm.openPreview(entry) },
                onDownload = { entry -> vm.downloadFile(entry.path, entry.name) },
                // 聊天里「产出文件」卡片上的「下载」：同一个下载器，按路径取。
                onDownloadPath = { path, name -> vm.downloadFile(path, name) },
                onUpload = vm::uploadFile,
                onCreateEntry = vm::createEntry,
                onDeleteEntry = vm::deleteEntry,
                onRenameEntry = vm::renameEntry,
                termLines = termLines,
                kernelTarget = kernelTarget,
                termBusy = termBusy,
                termSession = termSession,
                termCwd = termCwd,
                termSessions = termSessions,
                onTerminalSwitch = vm::attachTerminal,
                onTerminalNew = vm::openTerminal,
                onTerminalList = vm::listTerminals,
                termUnavailable = termUnavailable,
                onTermOpen = vm::ensureTerminal,
                onSetKernelTarget = vm::setKernelTarget,
                onTermRun = vm::runCommand,
                onTermInterrupt = vm::interruptCommand,
                onTermClear = vm::clearTerminal,
                onTermClose = vm::closeTerminal,
                codexConfig = codexConfig,
                chatModels = chatModels,
                chatCommands = chatCommands,
                lastUpload = lastUpload,
                draftScope = vm.draftScope,
                onClaimChat = vm::claimChat,
                onUploadFile = vm::uploadFile,
                onClearUpload = vm::clearLastUpload,
                onRequestChatModels = vm::requestChatModels,
                onRequestChatCommands = vm::requestChatCommands,
                codexTemplates = codexTemplates,
                codexTarget = codexTarget,
                codexTargets = computers.sortedBy { if (it.id == activeComputerId) 0 else 1 },
                onOpenCodex = vm::openCodexConfig,
                onCloseCodex = vm::closeCodexConfig,
                onSelectCodexTarget = vm::selectCodexTarget,
                onCodexLoad = vm::reloadCodexConfig,
                onCodexApply = vm::applyCodexConfig,
                onCodexRestore = vm::restoreCodexBackup,
                defaultCwd = vm.defaultCwd,
                chats = chats,
                activeChat = activeChat,
                chatEvents = chatEvents,
                chatSending = chatSending,
                approvals = approvals,
                onRespondApproval = vm::respondApproval,
                workspaces = workspaces,
                sessions = sessions,
                recordedSession = sessionDetail,
                onLoadChats = vm::loadChats,
                // A new conversation starts on the kernel and in the directory the
                // dialog chose; the home page's standing choice is only the fallback.
                onCreateChat = { cwd, engine ->
                    // The chosen engine is enough on its own: the standing choice can
                    // be momentarily null right after a cold start, and that must not
                    // swallow the tap.
                    val chosen = engine ?: engineForNewChat
                    if (link is LinkState.Connected && chosen != null) {
                        vm.createChat(cwd = cwd ?: vm.defaultCwd, engine = chosen)
                        section = Section.Sessions
                    }
                },
                onOpenChat = { id ->
                    vm.openChat(id)
                    section = Section.Sessions
                },
                onSendChat = vm::sendChatMessage,
                onNote = vm::note,
                onPoster = vm::postPoster,
                onCancelChat = vm::cancelChat,
                onCloseChat = vm::closeChat,
                onLeaveChat = vm::leaveChat,
                onConfigureChat = vm::configureChat,
                onSetChatMode = vm::setChatMode,
                onCloseRecorded = vm::closeSession,
                onLoadSessions = { vm.loadSessions() },
                // Asked for when the section is shown, because answering it means
                // listing this machine's processes.
                kernelRuns = kernelRuns,
                kernelRunsNote = kernelRunsNote,
                onLoadKernelRuns = vm::loadKernelRuns,
                sessionSort = sessionSort,
                canCreateChat = canCreateChat,
                onSetSessionSort = vm::setSessionSort,
                // Ask the file section for that directory and switch to it: the
                // conversation says WHERE, the file browser is where it can be seen.
                onOpenFilesAt = { path ->
                    vm.listDirectory(path)
                    section = Section.Files
                },
                pendingSends = pendingSends,
                pendingDropped = pendingDropped,
                chatHasOlder = chatHasOlder,
                onLoadOlderChat = vm::loadOlderChat,
                onOpenSession = { session -> vm.openSession(session) },
                onResumeSession = vm::resumeSession,
                connected = connected,
                themeMode = themeMode,
                onThemeModeChange = vm::setThemeMode,
                layout = uiLayout,
                onLayoutChange = vm::setUiLayout,
                palette = palette,
                onPaletteChange = vm::setPalette,
                features = featurePacks,
                onRefreshFeatures = vm::refreshFeatures,
                onInstallFeature = vm::installFeature,
                onRemoveFeature = vm::removeFeature,
                engines = engines,
                defaultEngine = defaultEngine,
                onSetDefaultEngine = vm::setDefaultEngine,
                onRefreshEngines = vm::loadEngines,
                onDisconnect = { connectionOpen = true },
                connectionLabel = connectionLabel,
                section = section,
                onSectionChange = { section = it },
                search = search,
                searching = searching,
                onSearchFiles = vm::searchFiles,
                onClearSearch = vm::clearSearch,
                storage = storage,
                localKernel = localKernel,
                onLoadLocalKernel = vm::loadLocalKernelManifest,
                onInstallLocalKernel = vm::installLocalKernel,
                onRemoveLocalKernel = vm::removeLocalKernel,
                onLoadStorage = vm::loadStorage,
                onClearStorage = vm::clearStorage,
                workingDirectory = workingDirectory.ifBlank { vm.startPath },
                onSetWorkingDirectory = vm::setWorkingDirectory,
                roots = roots,
                onNewChatNow = {
                    if (canCreateChat) {
                        vm.createChat(cwd = vm.defaultCwd, engine = engineForNewChat)
                        section = Section.Sessions
                    }
                },
                chatTerminals = chatTerminals,
                terminalView = terminalView,
                onLoadTerminals = vm::loadChatTerminals,
                onOpenTerminal = { terminal ->
                    val chatId = activeChat?.id
                    if (chatId != null) {
                        vm.openChatTerminal(
                            chatId = chatId,
                            terminalId = terminal.id,
                            command = terminal.command,
                            origin = terminal.origin,
                            canWrite = terminal.canWrite,
                        )
                    }
                },
                onTerminalInput = { data ->
                    val chatId = activeChat?.id
                    val terminalId = terminalView?.terminalId
                    if (chatId != null && terminalId != null) vm.sendTerminalInput(chatId, terminalId, data)
                },
                onStopTerminal = { terminalId ->
                    activeChat?.let { vm.stopChatTerminal(it.id, terminalId) }
                },
                onCloseTerminalView = vm::closeTerminalView,
            )
        }

        preview?.let { open ->
            Dialog(
                onDismissRequest = vm::closePreview,
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                FilePreviewScreen(
                    preview = open,
                    onClose = vm::closePreview,
                    onSave = vm::savePreviewToDownloads,
                    onEdit = {
                        vm.closePreview()
                        vm.readFile(open.path)
                    },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(
                bottom = if (!connectionOpen && openFile == null && preview == null &&
                    primaryNavigationVisible(section, activeChat != null || sessionDetail != null)) 80.dp else 0.dp,
            ),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

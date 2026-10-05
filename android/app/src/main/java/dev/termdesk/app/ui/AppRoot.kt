package dev.termdesk.app.ui

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.termdesk.app.data.LinkState
import dev.termdesk.app.data.PairRequest
import dev.termdesk.app.data.SessionInfo

@Composable
fun AppRoot(
    vm: AppViewModel = viewModel(),
    pairRequest: PairRequest? = null,
    onPairHandled: () -> Unit = {},
) {
    val link by vm.link.collectAsState()
    val status by vm.status.collectAsState()
    val processes by vm.processes.collectAsState()
    val services by vm.services.collectAsState()
    val loading by vm.loading.collectAsState()
    val lastAction by vm.lastAction.collectAsState()
    val listing by vm.listing.collectAsState()
    // Collected so startPath recomputes once the agent reports its roots.
    vm.fsRoots.collectAsState()
    val openFile by vm.openFile.collectAsState()
    val preview by vm.preview.collectAsState()
    val search by vm.search.collectAsState()
    val searching by vm.searching.collectAsState()
    val storage by vm.storage.collectAsState()
    val transfer by vm.transfer.collectAsState()
    val termLines by vm.termLines.collectAsState()
    val termSession by vm.termSession.collectAsState()
    val termBusy by vm.termBusy.collectAsState()
    val termUnavailable by vm.termUnavailable.collectAsState()
    val codexConfig by vm.codexConfig.collectAsState()
    val codexTemplates by vm.codexTemplates.collectAsState()
    val chats by vm.chats.collectAsState()
    val activeChat by vm.activeChat.collectAsState()
    val chatEvents by vm.chatEvents.collectAsState()
    val chatSending by vm.chatSending.collectAsState()
    val approvals by vm.approvals.collectAsState()
    val workspaces by vm.workspaces.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val sessionDetail by vm.sessionDetail.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val engines by vm.engines.collectAsState()
    val defaultEngine by vm.defaultEngine.collectAsState()


    val snackbarHostState = remember { SnackbarHostState() }

    // New-chat picker: onCreateChat only requests the sheet with a suggested
    // cwd; kernel/model/cwd stay explicit user choices inside NewChatSheet.
    // Which section is open lives here, not inside AppShell: a file viewer (or
    // the new-chat sheet) replaces the whole shell, and a section remembered
    // inside it would silently reset to 会话 every time one was opened.
    var section by rememberSaveable { mutableStateOf(Section.Sessions) }
    var newChatSuggestedCwd by remember { mutableStateOf<String?>(null) }
    var newChatOpen by remember { mutableStateOf(false) }
    var connectionOpen by remember { mutableStateOf(vm.savedToken.isBlank()) }


    // A scanned pairing link carries everything the app needs: address and
    // token. It is the only path that does not involve typing a secret.
    LaunchedEffect(pairRequest) {
        val request = pairRequest ?: return@LaunchedEffect
        onPairHandled()
        if (!request.isUsable) return@LaunchedEffect
        connectionOpen = false
        vm.connect(request.url, request.token)
        snackbarHostState.showSnackbar("已通过二维码配对 · ${request.name ?: request.url}")
    }
    val connected = link is LinkState.Connected
    // Kernel discovery is metadata only (it never runs a model), so it can be
    // refreshed on every connect: Settings and the new-chat sheet then show what
    // this machine can actually talk to instead of a guessed list.
    LaunchedEffect(connected) { if (connected) vm.loadEngines() }

    // The conversation's own panel offers the model / effort pickers, so the
    // Codex catalog has to exist as soon as a Codex conversation is open.
    LaunchedEffect(activeChat?.engine) {
        if (activeChat?.engine == "codex") vm.loadCodexConfig()
    }

    val hostname = when (val state = link) {
        is LinkState.Connected -> state.hostname
        is LinkState.NodeOffline -> state.hostname
        else -> "TermDesk"
    }
    val connectionLabel = when (val state = link) {
        is LinkState.NodeOffline -> "VPS 已连接 · ${state.hostname} 内核离线，上线后自动恢复"
        is LinkState.Connecting -> "正在连接节点 · 已缓存记录仍可查看"
        is LinkState.Failed -> "连接暂不可用 · 点击查看：${state.reason}"
        else -> "离线模式 · 点击连接或管理设备"
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

    // Back dismisses what is on top, in order: the new-chat sheet, then an open
    // file. Deeper layers (drawer, conversation, section) register their own
    // handlers and win by being composed later. Only when nothing is open does
    // the press reach the OS and leave the app.
    BackHandler(enabled = newChatOpen || openFile != null || preview != null) {
        when {
            newChatOpen -> newChatOpen = false
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
                initialToken = vm.savedToken,
                onConnect = { url, token -> connectionOpen = false; vm.connect(url, token) },
                onClose = { connectionOpen = false },
                onDisconnect = vm::disconnect,
                onForget = { vm.forgetDevice(); connectionOpen = true },
            )

            // An open file takes over the work area: viewing and editing are
            // both focused modes, never a panel beside the browser.
            preview != null -> FilePreviewScreen(
                preview = preview!!,
                onClose = vm::closePreview,
                onSave = vm::savePreviewToDownloads,
            )

            openFile != null -> FileEditor(
                file = openFile!!,
                onSave = vm::writeFile,
                onClose = vm::closeOpenFile,
            )

            else -> AppShell(
                status = status,
                hostname = hostname,
                processes = processes,
                services = services,
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
                onUpload = vm::uploadFile,
                onCreateEntry = vm::createEntry,
                onDeleteEntry = vm::deleteEntry,
                onRenameEntry = vm::renameEntry,
                termLines = termLines,
                termBusy = termBusy,
                termSession = termSession,
                termUnavailable = termUnavailable,
                onTermOpen = vm::openTerminal,
                onTermRun = vm::runCommand,
                onTermInterrupt = vm::interruptCommand,
                onTermClear = vm::clearTerminal,
                onTermClose = vm::closeTerminal,
                codexConfig = codexConfig,
                codexTemplates = codexTemplates,
                onCodexLoad = vm::loadCodexConfig,
                onCodexApply = vm::applyCodexProvider,
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
                // Opens the picker; ChatSection keeps calling onCreateChat(cwd).
                onCreateChat = { cwd ->
                    newChatSuggestedCwd = cwd
                    newChatOpen = true
                },
                onOpenChat = vm::openChat,
                onSendChat = vm::sendChatMessage,
                onCancelChat = vm::cancelChat,
                onCloseChat = vm::closeChat,
                onLeaveChat = vm::leaveChat,
                onConfigureChat = vm::configureChat,
                onCloseRecorded = vm::closeSession,
                onLoadSessions = { vm.loadSessions() },
                onOpenSession = { session -> vm.openSession(session) },
                onResumeSession = vm::resumeSession,
                connected = connected,
                themeMode = themeMode,
                onThemeModeChange = vm::setThemeMode,
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
                onLoadStorage = vm::loadStorage,
                onClearStorage = vm::clearStorage,
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { data ->
            Snackbar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Text(data.visuals.message, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (newChatOpen) {
            LaunchedEffect(Unit) {
                vm.loadEngines()
                vm.loadCodexConfig()
            }
            NewChatSheet(
                engines = engines,
                workspaces = workspaces,
                codexConfig = codexConfig,
                defaultEngine = defaultEngine,
                suggestedCwd = newChatSuggestedCwd,
                defaultCwd = vm.defaultCwd,
                onCreateChat = { cwd, engine, provider, model, title, effort ->
                    newChatOpen = false
                    vm.createChat(cwd, engine, provider, model, title, effort)
                },
                onCreateDirectory = { parent, name ->
                    vm.createEntry(parent, name, true)
                    vm.loadSessions()
                },
                onDismiss = { newChatOpen = false },
            )
        }
    }
}

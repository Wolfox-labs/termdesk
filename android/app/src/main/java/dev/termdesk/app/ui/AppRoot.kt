package dev.termdesk.app.ui

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.termdesk.app.data.LinkState
import dev.termdesk.app.data.SessionInfo

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
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
    val workspaces by vm.workspaces.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val sessionDetail by vm.sessionDetail.collectAsState()
    val themeMode by vm.themeMode.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            link !is LinkState.Connected -> ConnectionScreen(
                link = link,
                initialUrl = vm.savedUrl,
                initialToken = vm.savedToken,
                onConnect = vm::connect,
            )

            // An open file takes over the work area: editing is a focused mode.
            openFile != null -> FileEditor(
                file = openFile!!,
                onSave = vm::writeFile,
                onClose = vm::closeOpenFile,
            )

            else -> AppShell(
                status = status,
                hostname = (link as LinkState.Connected).hostname,
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
                onOpenFile = { entry -> vm.readFile(entry.path) },
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
                workspaces = workspaces,
                sessions = sessions,
                recordedSession = sessionDetail,
                onLoadChats = vm::loadChats,
                onCreateChat = { cwd -> vm.createChat(cwd) },
                onOpenChat = vm::openChat,
                onSendChat = vm::sendChatMessage,
                onCancelChat = vm::cancelChat,
                onCloseChat = vm::closeChat,
                onLeaveChat = vm::leaveChat,
                onCloseRecorded = vm::closeSession,
                onLoadSessions = { vm.loadSessions() },
                onOpenSession = { session -> vm.openSession(session) },
                themeMode = themeMode,
                onThemeModeChange = vm::setThemeMode,
                onDisconnect = vm::disconnect,
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
    }
}

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.net.Uri
import dev.termdesk.app.data.ChatApproval
import dev.termdesk.app.data.ChatEvent
import dev.termdesk.app.data.ChatInfo
import dev.termdesk.app.data.ChatModels
import dev.termdesk.app.data.ChatTerminal
import dev.termdesk.app.data.TerminalView
import dev.termdesk.app.data.UploadedFile
import dev.termdesk.app.data.CodexConfig
import dev.termdesk.app.data.CodexProviderTemplate
import dev.termdesk.app.data.DirectoryListing
import dev.termdesk.app.data.KernelInfo
import dev.termdesk.app.data.LocalKernelState
import dev.termdesk.app.data.FileEntry
import dev.termdesk.app.data.HostStatus
import dev.termdesk.app.data.ProcessInfo
import dev.termdesk.app.data.SearchResults
import dev.termdesk.app.data.StorageEntry
import dev.termdesk.app.data.StorageUse
import dev.termdesk.app.data.ServiceInfo
import dev.termdesk.app.data.SessionDetail
import dev.termdesk.app.data.SessionInfo
import dev.termdesk.app.data.TermLine
import dev.termdesk.app.data.TransferState
import dev.termdesk.app.data.WorkspaceInfo
import dev.termdesk.app.ui.theme.MeterChars
import dev.termdesk.app.ui.theme.ThemeMode

/** Sections of the app. */
enum class Section(val label: String, val icon: ImageVector) {
    Home("主页", Icons.Outlined.Home),
    Sessions("会话", Icons.Outlined.ChatBubbleOutline),
    Files("文件", Icons.Outlined.FolderOpen),
    Terminal("终端", Icons.Outlined.Terminal),
    System("系统", Icons.Outlined.Memory),
    Settings("设置", Icons.Outlined.Settings),
}

/**
 * Phone-sized workstation shell.
 *
 * There is no permanent navigation rail: a 400dp phone cannot spare 56dp of
 * every screen for it, so sections live in a drawer opened from the leading
 * button. The bar is never stacked either — the sessions area supplies its own
 * single row, so a conversation title and the section name never fight for the
 * same strip.
 */
@Composable
fun AppShell(
    status: HostStatus?,
    hostname: String,
    processes: List<ProcessInfo>,
    services: List<ServiceInfo>,
    listing: DirectoryListing?,
    loading: Boolean,
    transfer: TransferState?,
    startPath: String,
    onRefreshProcesses: (String) -> Unit,
    onRefreshServices: (String) -> Unit,
    onKillProcess: (Int) -> Unit,
    onServiceAction: (String, String) -> Unit,
    onNavigate: (String) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onDownload: (FileEntry) -> Unit,
    onUpload: (Uri, String) -> Unit,
    onCreateEntry: (String, String, Boolean) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    termLines: List<TermLine>,
    kernelTarget: String,
    onSetKernelTarget: (String) -> Unit,
    termBusy: Boolean,
    termSession: String?,
    termCwd: String?,
    termUnavailable: String?,
    onTermOpen: () -> Unit,
    onTermRun: (String) -> Unit,
    onTermInterrupt: () -> Unit,
    onTermClear: () -> Unit,
    onTermClose: () -> Unit,
    codexConfig: CodexConfig?,
    chatModels: Map<String, ChatModels>,
    lastUpload: UploadedFile?,
    onUploadFile: (android.net.Uri, String) -> Unit,
    onClearUpload: () -> Unit,
    onRequestChatModels: (String) -> Unit,
    codexTemplates: List<CodexProviderTemplate>,
    onCodexLoad: () -> Unit,
    onCodexApply: (String, String, String?, String?, Long?) -> Unit,
    onCodexRestore: (String?) -> Unit,
    defaultCwd: String,
    chats: List<ChatInfo>,
    activeChat: ChatInfo?,
    chatEvents: List<ChatEvent>,
    chatSending: Boolean,
    approvals: List<ChatApproval>,
    onRespondApproval: (String, String) -> Unit,
    workspaces: List<WorkspaceInfo>,
    sessions: List<SessionInfo>,
    recordedSession: SessionDetail?,
    onLoadChats: () -> Unit,
    onCreateChat: (String?) -> Unit,
    onOpenChat: (String) -> Unit,
    onSendChat: (String, String) -> Unit,
    onCancelChat: (String) -> Unit,
    onCloseChat: (String) -> Unit,
    onLeaveChat: () -> Unit,
    onConfigureChat: (String, String?, String?) -> Unit,
    onSetChatMode: (String, String) -> Unit,
    onCloseRecorded: () -> Unit,
    onLoadSessions: () -> Unit,
    onOpenSession: (SessionInfo) -> Unit,
    onResumeSession: (SessionDetail) -> Unit,
    connected: Boolean,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    engines: List<KernelInfo>,
    defaultEngine: String?,
    onSetDefaultEngine: (String?) -> Unit,
    onRefreshEngines: () -> Unit,
    onDisconnect: () -> Unit,
    connectionLabel: String,
    section: Section,
    onSectionChange: (Section) -> Unit,
    search: SearchResults?,
    searching: Boolean,
    onSearchFiles: (String, String) -> Unit,
    onClearSearch: () -> Unit,
    storage: StorageUse?,
    onLoadStorage: () -> Unit,
    onClearStorage: (StorageEntry) -> Unit,
    localKernel: LocalKernelState,
    onLoadLocalKernel: () -> Unit,
    onInstallLocalKernel: () -> Unit,
    onRemoveLocalKernel: () -> Unit,
    /** The directory new conversations work in (the home page's standing choice). */
    workingDirectory: String,
    onSetWorkingDirectory: (String) -> Unit,
    /** Directories the agent allows, offered as candidates on the home page. */
    roots: List<String>,
    /** Create a conversation right now, on the kernel chosen on the home page. */
    onNewChatNow: () -> Unit,
    /** The command lines each conversation ran, and the one being viewed. */
    chatTerminals: Map<String, List<ChatTerminal>>,
    terminalView: TerminalView?,
    onLoadTerminals: (String) -> Unit,
    onOpenTerminal: (ChatTerminal) -> Unit,
    onTerminalInput: (String) -> Unit,
    onStopTerminal: (String) -> Unit,
    onCloseTerminalView: () -> Unit,
) {
    var panelOpen by remember { mutableStateOf(false) }
    // Sections live in a drawer rather than a permanent rail: a phone is about
    // 400dp wide and the rail charged every screen 56dp for it.
    var sectionDrawerOpen by remember { mutableStateOf(false) }

    // System back walks up the app before it leaves it: the drawer first, then
    // the slide-over panel, then back to the home page. The conversation
    // itself registers its own handler, which is composed later and wins.
    BackHandler(enabled = sectionDrawerOpen || panelOpen || section != Section.Home) {
        when {
            sectionDrawerOpen -> sectionDrawerOpen = false
            panelOpen -> panelOpen = false
            else -> onSectionChange(Section.Home)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // The sessions area draws its own single-row bar (list title or
            // conversation title), so the shell only adds one for tool sections.
            if (section != Section.Sessions || sessionsShowLocalGate(section, kernelTarget, engines)) {
                TopBar(
                    section = section,
                    panelOpen = panelOpen,
                    onOpenSections = { sectionDrawerOpen = true },
                    onTogglePanel = { panelOpen = !panelOpen },
                    onDisconnect = onDisconnect,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            if (!connected) {
                Text(
                    connectionLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onDisconnect() }
                        .padding(10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(Modifier.weight(1f)) {
                SectionBody(
                    section = section,
                    status = status,
                    search = search,
                    searching = searching,
                    onSearchFiles = onSearchFiles,
                    onClearSearch = onClearSearch,
                    storage = storage,
                    onLoadStorage = onLoadStorage,
                    onClearStorage = onClearStorage,
                    localKernel = localKernel,
                    onLoadLocalKernel = onLoadLocalKernel,
                    onInstallLocalKernel = onInstallLocalKernel,
                    onRemoveLocalKernel = onRemoveLocalKernel,
                    processes = processes,
                    services = services,
                    listing = listing,
                    loading = loading,
                    transfer = transfer,
                    startPath = startPath,
                    onRefreshProcesses = onRefreshProcesses,
                    onRefreshServices = onRefreshServices,
                    onKillProcess = onKillProcess,
                    onServiceAction = onServiceAction,
                    onNavigate = onNavigate,
                    onOpenFile = onOpenFile,
                    onDownload = onDownload,
                    onUpload = onUpload,
                    onCreateEntry = onCreateEntry,
                    onDeleteEntry = onDeleteEntry,
                    onRenameEntry = onRenameEntry,
                    termLines = termLines,
                    kernelTarget = kernelTarget,
                    onSetKernelTarget = onSetKernelTarget,
                    termBusy = termBusy,
                    termSession = termSession,
                    termCwd = termCwd,
                    termUnavailable = termUnavailable,
                    onTermOpen = onTermOpen,
                    onTermRun = onTermRun,
                    onTermInterrupt = onTermInterrupt,
                    onTermClear = onTermClear,
                    onTermClose = onTermClose,
                    codexConfig = codexConfig,
                    chatModels = chatModels,
                    lastUpload = lastUpload,
                    onUploadFile = onUploadFile,
                    onClearUpload = onClearUpload,
                    onRequestChatModels = onRequestChatModels,
                    codexTemplates = codexTemplates,
                    onCodexLoad = onCodexLoad,
                    onCodexApply = onCodexApply,
                    onCodexRestore = onCodexRestore,
                    defaultCwd = defaultCwd,
                    chats = chats,
                    activeChat = activeChat,
                    chatEvents = chatEvents,
                    chatSending = chatSending,
                    approvals = approvals,
                    onRespondApproval = onRespondApproval,
                    workspaces = workspaces,
                    sessions = sessions,
                    recordedSession = recordedSession,
                    engines = engines,
                    onOpenSections = { sectionDrawerOpen = true },
                    onLoadChats = onLoadChats,
                    onCreateChat = onCreateChat,
                    onOpenChat = onOpenChat,
                    onSendChat = onSendChat,
                    onCancelChat = onCancelChat,
                    onCloseChat = onCloseChat,
                    onLeaveChat = onLeaveChat,
                    onConfigureChat = onConfigureChat,
                    onSetChatMode = onSetChatMode,
                    onCloseRecorded = onCloseRecorded,
                    onLoadSessions = onLoadSessions,
                    onOpenSession = onOpenSession,
                    onResumeSession = onResumeSession,
                    connected = connected,
                    hostname = hostname,
                    connectionLabel = connectionLabel,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    defaultEngine = defaultEngine,
                    onSetDefaultEngine = onSetDefaultEngine,
                    onRefreshEngines = onRefreshEngines,
                    workingDirectory = workingDirectory,
                    onSetWorkingDirectory = onSetWorkingDirectory,
                    roots = roots,
                    onNewChatNow = onNewChatNow,
                    onGoto = onSectionChange,
                    chatTerminals = chatTerminals,
                    terminalView = terminalView,
                    onLoadTerminals = onLoadTerminals,
                    onOpenTerminal = onOpenTerminal,
                    onTerminalInput = onTerminalInput,
                    onStopTerminal = onStopTerminal,
                    onCloseTerminalView = onCloseTerminalView,
                )
            }
        }

        // Scrim: tap anywhere outside the panel to dismiss it.
        AnimatedVisibility(visible = panelOpen, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { panelOpen = false },
            )
        }

        AnimatedVisibility(
            visible = panelOpen,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            ContextPanel(
                status = status,
                onClose = { panelOpen = false },
                modifier = Modifier.width(290.dp).fillMaxHeight(),
            )
        }

        // Section drawer — the phone's navigation surface.
        AnimatedVisibility(visible = sectionDrawerOpen, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { sectionDrawerOpen = false },
            )
        }

        AnimatedVisibility(
            visible = sectionDrawerOpen,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            SectionDrawer(
                hostname = hostname,
                section = section,
                connected = connected,
                onSelect = {
                    onSectionChange(it)
                    sectionDrawerOpen = false
                },
            )
        }
    }
}

/** Bar for the tool sections (files / terminal / system / settings). */
@Composable
private fun TopBar(
    section: Section,
    panelOpen: Boolean,
    onOpenSections: () -> Unit,
    onTogglePanel: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenSections) {
            Icon(
                imageVector = Icons.Outlined.Menu,
                contentDescription = "分区",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = section.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        IconButton(onClick = onTogglePanel) {
            Icon(
                imageVector = Icons.Outlined.Memory,
                contentDescription = "主机状态",
                tint = if (panelOpen) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDisconnect) {
            Icon(
                imageVector = Icons.Outlined.PowerSettingsNew,
                contentDescription = "断开连接",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Which machine is connected, and where you can go. */
@Composable
private fun SectionDrawer(
    hostname: String,
    section: Section,
    connected: Boolean,
    onSelect: (Section) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(236.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (connected) MeterChars.ok else MaterialTheme.colorScheme.outline),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    hostname,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (connected) "已连接" else "未连接",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Section.entries.forEach { entry ->
            val selected = entry == section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    )
                    .clickable { onSelect(entry) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * What a PC-backed module says while this phone is the chosen kernel.
 *
 * The local kernel runs a terminal here; conversations and the file browser are
 * still the PC's. Saying that out loud beats showing another machine's data under
 * a kernel name that says otherwise.
 */
/**
 * Is the conversation section showing the local-kernel notice instead of chats?
 *
 * One place decides, because two things depend on the answer: the body shows the
 * notice, and the shell has to draw its own bar in that case - the sessions
 * section normally draws its own, so without this the notice covered the whole
 * screen and the drawer became unreachable.
 */
private fun sessionsShowLocalGate(section: Section, kernelTarget: String, engines: List<KernelInfo>): Boolean =
    section == Section.Sessions && kernelTarget == "local" && engines.none { it.selectable }

@Composable
private fun LocalKernelNotice(
    title: String,
    reason: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            actionLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClick = onAction)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun SectionBody(
    section: Section,
    status: HostStatus?,
    processes: List<ProcessInfo>,
    services: List<ServiceInfo>,
    listing: DirectoryListing?,
    loading: Boolean,
    transfer: TransferState?,
    startPath: String,
    onRefreshProcesses: (String) -> Unit,
    onRefreshServices: (String) -> Unit,
    onKillProcess: (Int) -> Unit,
    onServiceAction: (String, String) -> Unit,
    onNavigate: (String) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onDownload: (FileEntry) -> Unit,
    onUpload: (Uri, String) -> Unit,
    onCreateEntry: (String, String, Boolean) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    termLines: List<TermLine>,
    kernelTarget: String,
    onSetKernelTarget: (String) -> Unit,
    termBusy: Boolean,
    termSession: String?,
    termCwd: String?,
    termUnavailable: String?,
    onTermOpen: () -> Unit,
    onTermRun: (String) -> Unit,
    onTermInterrupt: () -> Unit,
    onTermClear: () -> Unit,
    onTermClose: () -> Unit,
    codexConfig: CodexConfig?,
    chatModels: Map<String, ChatModels>,
    lastUpload: UploadedFile?,
    onUploadFile: (android.net.Uri, String) -> Unit,
    onClearUpload: () -> Unit,
    onRequestChatModels: (String) -> Unit,
    codexTemplates: List<CodexProviderTemplate>,
    onCodexLoad: () -> Unit,
    onCodexApply: (String, String, String?, String?, Long?) -> Unit,
    onCodexRestore: (String?) -> Unit,
    // P4
    defaultCwd: String,
    // live chats
    chats: List<ChatInfo>,
    activeChat: ChatInfo?,
    chatEvents: List<ChatEvent>,
    chatSending: Boolean,
    approvals: List<ChatApproval>,
    onRespondApproval: (String, String) -> Unit,
    workspaces: List<WorkspaceInfo>,
    sessions: List<SessionInfo>,
    recordedSession: SessionDetail?,
    onLoadChats: () -> Unit,
    // Starts a conversation right away on the kernel the home page shows.
    onCreateChat: (String?) -> Unit,
    onOpenChat: (String) -> Unit,
    onSendChat: (String, String) -> Unit,
    onCancelChat: (String) -> Unit,
    onCloseChat: (String) -> Unit,
    onLeaveChat: () -> Unit,
    onCloseRecorded: () -> Unit,
    onLoadSessions: () -> Unit,
    onOpenSession: (SessionInfo) -> Unit,
    onResumeSession: (SessionDetail) -> Unit,
    connected: Boolean,
    hostname: String,
    connectionLabel: String,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    engines: List<KernelInfo>,
    defaultEngine: String?,
    onSetDefaultEngine: (String?) -> Unit,
    onRefreshEngines: () -> Unit,
    onOpenSections: () -> Unit,
    onConfigureChat: (String, String?, String?) -> Unit,
    onSetChatMode: (String, String) -> Unit,
    search: SearchResults?,
    searching: Boolean,
    onSearchFiles: (String, String) -> Unit,
    onClearSearch: () -> Unit,
    storage: StorageUse?,
    onLoadStorage: () -> Unit,
    onClearStorage: (StorageEntry) -> Unit,
    localKernel: LocalKernelState,
    onLoadLocalKernel: () -> Unit,
    onInstallLocalKernel: () -> Unit,
    onRemoveLocalKernel: () -> Unit,
    workingDirectory: String,
    onSetWorkingDirectory: (String) -> Unit,
    roots: List<String>,
    onNewChatNow: () -> Unit,
    onGoto: (Section) -> Unit,
    chatTerminals: Map<String, List<ChatTerminal>>,
    terminalView: TerminalView?,
    onLoadTerminals: (String) -> Unit,
    onOpenTerminal: (ChatTerminal) -> Unit,
    onTerminalInput: (String) -> Unit,
    onStopTerminal: (String) -> Unit,
    onCloseTerminalView: () -> Unit,
) {
    when (section) {
        // The landing page: where this phone is pointed, what a new conversation
        // runs on, where it works, and how to get back into what was running.
        Section.Home -> HomeSection(
            hostname = hostname,
            connected = connected,
            connectionLabel = connectionLabel,
            status = status,
            kernelTarget = kernelTarget,
            engines = engines,
            currentEngine = defaultEngine,
            onSetKernelTarget = onSetKernelTarget,
            onSetEngine = onSetDefaultEngine,
            onRefreshEngines = onRefreshEngines,
            workingDirectory = workingDirectory,
            onSetWorkingDirectory = onSetWorkingDirectory,
            roots = roots,
            workspaces = workspaces,
            chats = chats,
            sessions = sessions,
            onNewChat = onNewChatNow,
            onOpenChat = onOpenChat,
            onOpenSession = { session ->
                onOpenSession(session)
                onGoto(Section.Sessions)
            },
            onBrowseFiles = { onGoto(Section.Files) },
            onOpenTerminal = { onGoto(Section.Terminal) },
            onOpenSystem = { onGoto(Section.System) },
        )
        // No local gate here any more: the sandbox agent serves this section too.
        Section.System -> SystemSection(
            status = status,
            processes = processes,
            services = services,
            loading = loading,
            onRefreshProcesses = onRefreshProcesses,
            onRefreshServices = onRefreshServices,
            onKillProcess = onKillProcess,
            onServiceAction = onServiceAction,
        )
        Section.Files -> FilesSection(
            listing = listing,
            loading = loading,
            transfer = transfer,
            initialPath = startPath,
            onNavigate = onNavigate,
            onOpenFile = onOpenFile,
            onDownload = onDownload,
            onUpload = onUpload,
            onCreate = onCreateEntry,
            onDelete = onDeleteEntry,
            onRename = onRenameEntry,
            search = search,
            searching = searching,
            onSearch = onSearchFiles,
            onClearSearch = onClearSearch,
        )
        Section.Terminal -> TerminalSection(
            lines = termLines,
            kernel = kernelTarget,
            busy = termBusy,
            sessionId = termSession,
            cwd = termCwd,
            unavailable = termUnavailable,
            onOpen = onTermOpen,
            onRun = onTermRun,
            onInterrupt = onTermInterrupt,
            onClear = onTermClear,
            onClose = onTermClose,
        )
        Section.Sessions -> if (sessionsShowLocalGate(section, kernelTarget, engines)) LocalKernelNotice(
            title = "本地内核",
            reason = "沙盒里还没有可用的 agent 内核：装上 opencode / dsh 之类任何一个，会话就能在这里跑起来",
            actionLabel = "切回这台电脑",
            onAction = { onSetKernelTarget("remote") },
        ) else ChatSection(
            chats = chats,
            activeChat = activeChat,
            events = chatEvents,
            sending = chatSending,
            approvals = approvals,
            onRespondApproval = onRespondApproval,
            workspaces = workspaces,
            sessions = sessions,
            defaultCwd = defaultCwd,
            recordedSession = recordedSession,
            engines = engines,
            codexConfig = codexConfig,
            chatModels = chatModels,
            lastUpload = lastUpload,
            onUploadFile = onUploadFile,
            onClearUpload = onClearUpload,
            onRequestChatModels = onRequestChatModels,
            onOpenSections = onOpenSections,
            onConfigureChat = onConfigureChat,
            onSetChatMode = onSetChatMode,
            onLoadChats = onLoadChats,
            onCreateChat = onCreateChat,
            onOpenChat = onOpenChat,
            onSend = onSendChat,
            onCancel = onCancelChat,
            onCloseChat = onCloseChat,
            onLeaveChat = onLeaveChat,
            onCloseRecorded = onCloseRecorded,
            onLoadSessions = onLoadSessions,
            onOpenSession = onOpenSession,
                        onResumeSession = onResumeSession,
                        connected = connected,
            terminals = activeChat?.let { chatTerminals[it.id] }.orEmpty(),
            terminalView = terminalView,
            onLoadTerminals = onLoadTerminals,
            onOpenTerminal = onOpenTerminal,
            onTerminalInput = onTerminalInput,
            onStopTerminal = onStopTerminal,
            onCloseTerminalView = onCloseTerminalView,
        )
        Section.Settings -> SettingsSection(
            kernelTarget = kernelTarget,
            onSetKernelTarget = onSetKernelTarget,
            config = codexConfig,
            templates = codexTemplates,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            engines = engines,
            defaultEngine = defaultEngine,
            onSetDefaultEngine = onSetDefaultEngine,
            onRefreshEngines = onRefreshEngines,
            storage = storage,
            onLoadStorage = onLoadStorage,
            onClearStorage = onClearStorage,
                    localKernel = localKernel,
                    onLoadLocalKernel = onLoadLocalKernel,
                    onInstallLocalKernel = onInstallLocalKernel,
                    onRemoveLocalKernel = onRemoveLocalKernel,
            onLoad = onCodexLoad,
            onApply = onCodexApply,
            onRestore = onCodexRestore,
        )
    }
}


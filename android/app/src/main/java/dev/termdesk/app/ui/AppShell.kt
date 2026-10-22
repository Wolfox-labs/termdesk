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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import dev.termdesk.app.data.ChatCommands
import dev.termdesk.app.data.ChatModels
import dev.termdesk.app.data.ChatTerminal
import dev.termdesk.app.data.TerminalView
import dev.termdesk.app.data.UploadedFile
import dev.termdesk.app.data.CodexApplyRequest
import dev.termdesk.app.data.CodexConfig
import dev.termdesk.app.data.CodexProviderTemplate
import dev.termdesk.app.data.CodexTargetState
import dev.termdesk.app.data.PairedComputer
import dev.termdesk.app.data.DirectoryListing
import dev.termdesk.app.data.KernelInfo
import dev.termdesk.app.data.KernelRun
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
import dev.termdesk.app.data.SessionSort
import dev.termdesk.app.data.TermLine
import dev.termdesk.app.data.TransferState
import dev.termdesk.app.data.WorkspaceInfo
import dev.termdesk.app.ui.theme.MeterChars
import dev.termdesk.app.ui.theme.Palette
import dev.termdesk.app.ui.theme.ThemeMode
import dev.termdesk.app.data.FeatureState

/** Sections of the app. */
enum class Section(val label: String, val icon: ImageVector) {
    Home("工作台", Icons.Outlined.Home),
    Sessions("会话", Icons.Outlined.ChatBubbleOutline),
    Files("文件", Icons.Outlined.FolderOpen),
    Terminal("终端", Icons.Outlined.Terminal),
    System("系统", Icons.Outlined.Memory),
    Settings("设置", Icons.Outlined.Settings),
}

/**
 * Shared by navigation and transient feedback, so messages never cover tabs.
 *
 * Settings is a focused, nested surface: its root and every sub-page hide the
 * four destinations, and leaving Settings restores them on the normal rule.
 * Kept free of composition state so the rule can be tested directly.
 */
internal fun navigationVisible(
    section: Section,
    conversationOpen: Boolean,
    keyboardVisible: Boolean,
): Boolean {
    if (keyboardVisible) return false
    if (section == Section.Settings) return false
    return !(section == Section.Sessions && conversationOpen)
}

@Composable
internal fun primaryNavigationVisible(section: Section, conversationOpen: Boolean): Boolean {
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    return navigationVisible(section, conversationOpen, keyboardVisible)
}

/**
 * Phone-sized workstation shell.
 *
 * Frequent destinations use bottom navigation. The drawer also exposes system
 * and settings. Navigation hides for focused conversations and keyboard input;
 * the sessions area supplies its own top row so titles are never stacked.
 */
@Composable
fun AppShell(
    status: HostStatus?,
    hostname: String,
    processes: List<ProcessInfo>,
    services: List<ServiceInfo>,
    inventoryError: String?,
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
    termSessions: List<dev.termdesk.app.data.TerminalInfo>,
    onTerminalSwitch: (String) -> Unit,
    onTerminalNew: () -> Unit,
    onTerminalList: () -> Unit,
    termUnavailable: String?,
    onTermOpen: () -> Unit,
    onTermRun: (String) -> Unit,
    onTermInterrupt: () -> Unit,
    onTermClear: () -> Unit,
    onTermClose: () -> Unit,
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
    codexTemplates: List<CodexProviderTemplate>,
    codexTarget: CodexTargetState,
    codexTargets: List<PairedComputer>,
    onOpenCodex: () -> Unit,
    onCloseCodex: () -> Unit,
    onSelectCodexTarget: (String) -> Unit,
    onCodexLoad: () -> Unit,
    onCodexApply: (CodexApplyRequest) -> Unit,
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
    /** (working directory, kernel): both chosen in the new-conversation dialog. */
    onCreateChat: (String?, String?) -> Unit,
    onOpenChat: (String) -> Unit,
    onSendChat: (String, String) -> Unit,
    onCancelChat: (String) -> Unit,
    onCloseChat: (String) -> Unit,
    onLeaveChat: () -> Unit,
    onConfigureChat: (String, String?, String?) -> Unit,
    onSetChatMode: (String, String) -> Unit,
    onCloseRecorded: () -> Unit,
    onLoadSessions: () -> Unit,
    /** Agent processes running on the PC that its own agent did not start. */
    kernelRuns: List<KernelRun>,
    pendingSends: Int,
    pendingDropped: Int,
    /** 这个会话还有更早的历史没取回来（agent 支持 chat.page 时才可能为 true）。 */
    chatHasOlder: Boolean,
    onLoadOlderChat: () -> Unit,
    kernelRunsNote: String?,
    onLoadKernelRuns: () -> Unit,
    sessionSort: SessionSort,
    canCreateChat: Boolean,
    onSetSessionSort: (SessionSort) -> Unit,
    /** Open the file section at a directory (from a conversation). */
    onOpenFilesAt: (String) -> Unit,
    /** 点对话里的产出物：在当前这一页直接看结果（见 ChatSection.onPreviewFile）。 */
    onPreviewFile: ((String, String) -> Unit)? = null,
    /** 「产出文件」卡片上的「下载」：给的是路径。 */
    onDownloadPath: ((String, String) -> Unit)? = null,
    /** 手机本地做完一件事之后，往当前对话里写一行系统提示。 */
    onNote: (String) -> Unit = {},
    /** 海报画好之后把卡片放进当前对话。 */
    onPoster: (String, String) -> Unit = { _, _ -> },    onOpenSession: (SessionInfo) -> Unit,
    onResumeSession: (SessionDetail) -> Unit,
    connected: Boolean,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    layout: UiLayout,
    onLayoutChange: (UiLayout) -> Unit,
    palette: Palette,
    onPaletteChange: (Palette) -> Unit,
    features: Map<String, FeatureState>,
    onRefreshFeatures: () -> Unit,
    onInstallFeature: (String) -> Unit,
    onRemoveFeature: (String) -> Unit,
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
    val showNavigation = primaryNavigationVisible(section, activeChat != null || recordedSession != null)

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
                    style = MaterialTheme.typography.bodySmall,
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
                    inventoryError = inventoryError,
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
                    termSessions = termSessions,
                    onTerminalSwitch = onTerminalSwitch,
                    onTerminalNew = onTerminalNew,
                    onTerminalList = onTerminalList,
                    termUnavailable = termUnavailable,
                    onTermOpen = onTermOpen,
                    onTermRun = onTermRun,
                    onTermInterrupt = onTermInterrupt,
                    onTermClear = onTermClear,
                    onTermClose = onTermClose,
                    codexConfig = codexConfig,
                    chatModels = chatModels,
                    chatCommands = chatCommands,
                    lastUpload = lastUpload,
                    draftScope = draftScope,
                    onClaimChat = onClaimChat,
                    onUploadFile = onUploadFile,
                    onClearUpload = onClearUpload,
                    onRequestChatModels = onRequestChatModels,
                    onRequestChatCommands = onRequestChatCommands,
                    codexTemplates = codexTemplates,
                    codexTarget = codexTarget,
                    codexTargets = codexTargets,
                    onOpenCodex = onOpenCodex,
                    onCloseCodex = onCloseCodex,
                    onSelectCodexTarget = onSelectCodexTarget,
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
                    kernelRuns = kernelRuns,
                    kernelRunsNote = kernelRunsNote,
                    onLoadKernelRuns = onLoadKernelRuns,
                    sessionSort = sessionSort,
                    onSetSessionSort = onSetSessionSort,
                    onOpenFilesAt = onOpenFilesAt,
                    onPreviewFile = onPreviewFile,
                    onDownloadPath = onDownloadPath,
                    onNote = onNote,
                    onPoster = onPoster,                    pendingSends = pendingSends,
                    pendingDropped = pendingDropped,
                    chatHasOlder = chatHasOlder,
                    onLoadOlderChat = onLoadOlderChat,
                    onOpenSession = onOpenSession,
                    onResumeSession = onResumeSession,
                    connected = connected,
                    hostname = hostname,
                    connectionLabel = connectionLabel,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    layout = layout,
                    onLayoutChange = onLayoutChange,
                    palette = palette,
                    onPaletteChange = onPaletteChange,
                    features = features,
                    onRefreshFeatures = onRefreshFeatures,
                    onInstallFeature = onInstallFeature,
                    onRemoveFeature = onRemoveFeature,
                    defaultEngine = defaultEngine,
                    onSetDefaultEngine = onSetDefaultEngine,
                    onRefreshEngines = onRefreshEngines,
                    workingDirectory = workingDirectory,
                    onSetWorkingDirectory = onSetWorkingDirectory,
                    roots = roots,
                    onNewChatNow = onNewChatNow,
                    canCreateChat = canCreateChat,
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
            if (showNavigation) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, windowInsets = WindowInsets(0, 0, 0, 0)) {
                    listOf(Section.Home, Section.Sessions, Section.Files, Section.Terminal).forEach { destination ->
                        NavigationBarItem(
                            selected = section == destination,
                            onClick = { onSectionChange(destination) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                        )
                    }
                }
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
            // 面板从哪一侧滑进来，是两套布局唯一的差别（见 UiLayout）。
            enter = slideInHorizontally { if (layout.panelAtStart) -it else it },
            exit = slideOutHorizontally { if (layout.panelAtStart) -it else it },
            modifier = Modifier.align(
                if (layout.panelAtStart) Alignment.CenterStart else Alignment.CenterEnd,
            ),
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
            .heightIn(min = 56.dp)
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
                imageVector = Icons.Outlined.Devices,
                contentDescription = "设备管理",
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
    inventoryError: String?,
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
    termSessions: List<dev.termdesk.app.data.TerminalInfo>,
    onTerminalSwitch: (String) -> Unit,
    onTerminalNew: () -> Unit,
    onTerminalList: () -> Unit,
    termUnavailable: String?,
    onTermOpen: () -> Unit,
    onTermRun: (String) -> Unit,
    onTermInterrupt: () -> Unit,
    onTermClear: () -> Unit,
    onTermClose: () -> Unit,
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
    codexTemplates: List<CodexProviderTemplate>,
    codexTarget: CodexTargetState,
    codexTargets: List<PairedComputer>,
    onOpenCodex: () -> Unit,
    onCloseCodex: () -> Unit,
    onSelectCodexTarget: (String) -> Unit,
    onCodexLoad: () -> Unit,
    onCodexApply: (CodexApplyRequest) -> Unit,
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
    onCreateChat: (String?, String?) -> Unit,
    onOpenChat: (String) -> Unit,
    onSendChat: (String, String) -> Unit,
    onCancelChat: (String) -> Unit,
    onCloseChat: (String) -> Unit,
    onLeaveChat: () -> Unit,
    onCloseRecorded: () -> Unit,
    onLoadSessions: () -> Unit,
    /** Agent processes running on the PC that its own agent did not start. */
    kernelRuns: List<KernelRun>,
    pendingSends: Int,
    pendingDropped: Int,
    /** 这个会话还有更早的历史没取回来（agent 支持 chat.page 时才可能为 true）。 */
    chatHasOlder: Boolean,
    onLoadOlderChat: () -> Unit,
    kernelRunsNote: String?,
    onLoadKernelRuns: () -> Unit,
    sessionSort: SessionSort,
    onSetSessionSort: (SessionSort) -> Unit,
    /** Open the file section at a directory (from a conversation). */
    onOpenFilesAt: (String) -> Unit,
    /** 点对话里的产出物：在当前这一页直接看结果（见 ChatSection.onPreviewFile）。 */
    onPreviewFile: ((String, String) -> Unit)? = null,
    /** 「产出文件」卡片上的「下载」：给的是路径。 */
    onDownloadPath: ((String, String) -> Unit)? = null,
    /** 手机本地做完一件事之后，往当前对话里写一行系统提示。 */
    onNote: (String) -> Unit = {},
    /** 海报画好之后把卡片放进当前对话。 */
    onPoster: (String, String) -> Unit = { _, _ -> },    onOpenSession: (SessionInfo) -> Unit,
    onResumeSession: (SessionDetail) -> Unit,
    connected: Boolean,
    hostname: String,
    connectionLabel: String,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    layout: UiLayout,
    onLayoutChange: (UiLayout) -> Unit,
    palette: Palette,
    onPaletteChange: (Palette) -> Unit,
    features: Map<String, FeatureState>,
    onRefreshFeatures: () -> Unit,
    onInstallFeature: (String) -> Unit,
    onRemoveFeature: (String) -> Unit,
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
    canCreateChat: Boolean,
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
            canCreateChat = canCreateChat,
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
            inventoryError = inventoryError,
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
            sessions = termSessions,
            onSwitch = onTerminalSwitch,
            onNewTerminal = onTerminalNew,
            onListTerminals = onTerminalList,
            unavailable = termUnavailable,
            onOpen = onTermOpen,
            onRun = onTermRun,
            onInterrupt = onTermInterrupt,
            onClear = onTermClear,
            onClose = onTermClose,
        )
        Section.Sessions -> if (sessionsShowLocalGate(section, kernelTarget, engines)) LocalKernelNotice(
            title = "手机上暂无可用助手",
            reason = "需先在手机上安装兼容的助手并配置账号和模型。也可切换到远程电脑。",
            actionLabel = "切换到远程电脑",
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
            defaultEngine = defaultEngine,
            roots = roots,
            recordedSession = recordedSession,
            engines = engines,
            codexConfig = codexConfig,
            chatModels = chatModels,
            chatCommands = chatCommands,
            lastUpload = lastUpload,
            draftScope = draftScope,
            onClaimChat = onClaimChat,
            onUploadFile = onUploadFile,
            onClearUpload = onClearUpload,
            onRequestChatModels = onRequestChatModels,
            onRequestChatCommands = onRequestChatCommands,
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
            kernelRuns = kernelRuns,
            kernelRunsNote = kernelRunsNote,
            onLoadKernelRuns = onLoadKernelRuns,
            sessionSort = sessionSort,
            onSetSessionSort = onSetSessionSort,
            onOpenFiles = onOpenFilesAt,
            onPreviewFile = onPreviewFile,
            onDownloadPath = onDownloadPath,
            onNote = onNote,
            onPoster = onPoster,            pendingSends = pendingSends,
            pendingDropped = pendingDropped,
            chatHasOlder = chatHasOlder,
            onLoadOlderChat = onLoadOlderChat,
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
            codex = codexTarget,
            targets = codexTargets,
            connected = connected,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            layout = layout,
            onLayoutChange = onLayoutChange,
            palette = palette,
            onPaletteChange = onPaletteChange,
            features = features,
            onRefreshFeatures = onRefreshFeatures,
            onInstallFeature = onInstallFeature,
            onRemoveFeature = onRemoveFeature,
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
            onOpen = onOpenCodex,
            onClose = onCloseCodex,
            onSelectTarget = onSelectCodexTarget,
        )
    }
}


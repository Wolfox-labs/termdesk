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
import dev.termdesk.app.data.ChatEvent
import dev.termdesk.app.data.ChatInfo
import dev.termdesk.app.data.CodexConfig
import dev.termdesk.app.data.CodexProviderTemplate
import dev.termdesk.app.data.DirectoryListing
import dev.termdesk.app.data.EngineInfo
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
/** Sections of the app. */
enum class Section(val label: String, val icon: ImageVector) {
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
    termBusy: Boolean,
    termSession: String?,
    termUnavailable: String?,
    onTermOpen: () -> Unit,
    onTermRun: (String) -> Unit,
    onTermInterrupt: () -> Unit,
    onTermClear: () -> Unit,
    onTermClose: () -> Unit,
    codexConfig: CodexConfig?,
    codexTemplates: List<CodexProviderTemplate>,
    onCodexLoad: () -> Unit,
    onCodexApply: (String, String, String?, String?, Long?) -> Unit,
    onCodexRestore: (String?) -> Unit,
    defaultCwd: String,
    chats: List<ChatInfo>,
    activeChat: ChatInfo?,
    chatEvents: List<ChatEvent>,
    chatSending: Boolean,
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
    onCloseRecorded: () -> Unit,
    onLoadSessions: () -> Unit,
    onOpenSession: (SessionInfo) -> Unit,
    onResumeSession: (SessionDetail) -> Unit,
    connected: Boolean,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    engines: List<EngineInfo>,
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
) {
    var panelOpen by remember { mutableStateOf(false) }
    // Sections live in a drawer rather than a permanent rail: a phone is about
    // 400dp wide and the rail charged every screen 56dp for it.
    var sectionDrawerOpen by remember { mutableStateOf(false) }

    // System back walks up the app before it leaves it: the drawer first, then
    // the slide-over panel, then back to the conversation list. The conversation
    // itself registers its own handler, which is composed later and wins.
    BackHandler(enabled = sectionDrawerOpen || panelOpen || section != Section.Sessions) {
        when {
            sectionDrawerOpen -> sectionDrawerOpen = false
            panelOpen -> panelOpen = false
            else -> onSectionChange(Section.Sessions)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // The sessions area draws its own single-row bar (list title or
            // conversation title), so the shell only adds one for tool sections.
            if (section != Section.Sessions) {
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
                    termBusy = termBusy,
                    termSession = termSession,
                    termUnavailable = termUnavailable,
                    onTermOpen = onTermOpen,
                    onTermRun = onTermRun,
                    onTermInterrupt = onTermInterrupt,
                    onTermClear = onTermClear,
                    onTermClose = onTermClose,
                    codexConfig = codexConfig,
                    codexTemplates = codexTemplates,
                    onCodexLoad = onCodexLoad,
                    onCodexApply = onCodexApply,
                    onCodexRestore = onCodexRestore,
                    defaultCwd = defaultCwd,
                    chats = chats,
                    activeChat = activeChat,
                    chatEvents = chatEvents,
                    chatSending = chatSending,
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
                    onCloseRecorded = onCloseRecorded,
                    onLoadSessions = onLoadSessions,
                    onOpenSession = onOpenSession,
                    onResumeSession = onResumeSession,
                    connected = connected,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    defaultEngine = defaultEngine,
                    onSetDefaultEngine = onSetDefaultEngine,
                    onRefreshEngines = onRefreshEngines,
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
    termBusy: Boolean,
    termSession: String?,
    termUnavailable: String?,
    onTermOpen: () -> Unit,
    onTermRun: (String) -> Unit,
    onTermInterrupt: () -> Unit,
    onTermClear: () -> Unit,
    onTermClose: () -> Unit,
    codexConfig: CodexConfig?,
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
    workspaces: List<WorkspaceInfo>,
    sessions: List<SessionInfo>,
    recordedSession: SessionDetail?,
    onLoadChats: () -> Unit,
    // Opens NewChatSheet (suggested cwd); see the AppShell parameter docs.
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
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    engines: List<EngineInfo>,
    defaultEngine: String?,
    onSetDefaultEngine: (String?) -> Unit,
    onRefreshEngines: () -> Unit,
    onOpenSections: () -> Unit,
    onConfigureChat: (String, String?, String?) -> Unit,
    search: SearchResults?,
    searching: Boolean,
    onSearchFiles: (String, String) -> Unit,
    onClearSearch: () -> Unit,
    storage: StorageUse?,
    onLoadStorage: () -> Unit,
    onClearStorage: (StorageEntry) -> Unit,
) {
    when (section) {
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
            busy = termBusy,
            sessionId = termSession,
            unavailable = termUnavailable,
            onOpen = onTermOpen,
            onRun = onTermRun,
            onInterrupt = onTermInterrupt,
            onClear = onTermClear,
            onClose = onTermClose,
        )
        Section.Sessions -> ChatSection(
            chats = chats,
            activeChat = activeChat,
            events = chatEvents,
            sending = chatSending,
            workspaces = workspaces,
            sessions = sessions,
            defaultCwd = defaultCwd,
            recordedSession = recordedSession,
            engines = engines,
            codexConfig = codexConfig,
            onOpenSections = onOpenSections,
            onConfigureChat = onConfigureChat,
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
        )
        Section.Settings -> SettingsSection(
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
            onLoad = onCodexLoad,
            onApply = onCodexApply,
            onRestore = onCodexRestore,
        )
    }
}


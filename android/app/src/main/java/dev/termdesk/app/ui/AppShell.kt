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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FolderOpen
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
import dev.termdesk.app.data.FileEntry
import dev.termdesk.app.data.HostStatus
import dev.termdesk.app.data.ProcessInfo
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
    Sessions("会话", Icons.Outlined.ChatBubbleOutline),
    Files("文件", Icons.Outlined.FolderOpen),
    Terminal("终端", Icons.Outlined.Terminal),
    System("系统", Icons.Outlined.Memory),
    Settings("设置", Icons.Outlined.Settings),
}

/**
 * Phone-sized workstation shell.
 *
 * A permanent three-pane layout does not fit a 400dp-wide phone: the earlier
 * fixed 72dp rail plus 268dp panel left under 60dp for content. So the shape is:
 * an icon-only rail, a full-width work area, and context that slides over on
 * demand instead of occupying width permanently.
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
    /**
     * Request a new agent conversation. The optional string is a suggested
     * working directory from the caller (chat index / drawer).
     *
     * Contract with ChatSection: ChatSection keeps calling `onCreateChat(cwd)`
     * unchanged. This callback must open the new-chat picker ([NewChatSheet]),
     * NOT create a chat directly — kernel (engine), model and cwd are explicit
     * user choices. Preferred future signature if renamed:
     * `onCreateChatRequested: (suggestedCwd: String?) -> Unit`.
     */
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
    onDisconnect: () -> Unit,
    connectionLabel: String,
) {
    var section by remember { mutableStateOf(Section.Sessions) }
    var panelOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(current = section, onSelect = { section = it })

            VerticalDivider(color = MaterialTheme.colorScheme.outline)

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .imePadding(),
            ) {
                TopBar(
                    hostname = hostname,
                    section = section,
                    panelOpen = panelOpen,
                    onTogglePanel = { panelOpen = !panelOpen },
                    onDisconnect = onDisconnect,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                if (!connected) Text(connectionLabel, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).clickable { onDisconnect() }.padding(10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Box(Modifier.weight(1f)) {
                    SectionBody(
                        section = section,
                        status = status,
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
                        onLoadChats = onLoadChats,
                        onCreateChat = onCreateChat,
                        onOpenChat = onOpenChat,
                        onSendChat = onSendChat,
                        onCancelChat = onCancelChat,
                        onCloseChat = onCloseChat,
                        onLeaveChat = onLeaveChat,
                        onCloseRecorded = onCloseRecorded,
                        onLoadSessions = onLoadSessions,
                        onOpenSession = onOpenSession,
                        onResumeSession = onResumeSession,
                        connected = connected,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                    )
                }
            }
        }

        // Scrim: tap anywhere outside the panel to dismiss it.
        AnimatedVisibility(
            visible = panelOpen,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
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
                modifier = Modifier
                    .width(290.dp)
                    .fillMaxHeight(),
            )
        }
    }
}

/**
 * Icon-only rail. Labels are omitted deliberately: at 145% system font scale a
 * labelled rail needs ~90dp, which is a quarter of the screen. The current
 * section name is shown in the top bar instead.
 */
@Composable
private fun NavigationRail(current: Section, onSelect: (Section) -> Unit) {
    Column(
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Section.entries.forEach { entry ->
            val selected = entry == current
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent,
                    )
                    .clickable { onSelect(entry) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = entry.label,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    hostname: String,
    section: Section,
    panelOpen: Boolean,
    onTogglePanel: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MeterChars.ok),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = hostname,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = section.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
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
        Section.Settings -> CodexSettingsSection(
            config = codexConfig,
            templates = codexTemplates,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onLoad = onCodexLoad,
            onApply = onCodexApply,
            onRestore = onCodexRestore,
        )
    }
}

@Composable
private fun Placeholder(title: String, icon: ImageVector, note: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

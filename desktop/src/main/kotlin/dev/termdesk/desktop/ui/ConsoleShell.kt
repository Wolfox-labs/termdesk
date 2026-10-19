package dev.termdesk.desktop.ui

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.desktop.agent.AgentApi
import dev.termdesk.desktop.agent.AgentProcess
import dev.termdesk.desktop.agent.AgentStatus
import dev.termdesk.desktop.workbench.DeviceRow
import dev.termdesk.desktop.workbench.SessionRow
import dev.termdesk.desktop.workbench.WorkbenchStore
import dev.termdesk.desktop.workbench.termdeskHome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

internal enum class ConsolePage(val label: String, val icon: ImageVector) {
    Overview("概览", Icons.Outlined.Dashboard),
    Devices("设备", Icons.Outlined.Smartphone),
    Kernels("内核", Icons.Outlined.Memory),
    Sessions("会话", Icons.Outlined.Forum),
}

private val PORT: Int = System.getenv("TERMDESK_PORT")?.toIntOrNull() ?: 7420

/**
 * The management console.
 *
 * One sidebar, one page, one table or master-detail surface at a time. This
 * window manages the phones paired with this computer, the kernels installed on
 * it and the sessions recorded by those kernels; it is not a second chat client.
 */
@Composable
fun ConsoleScreen(
    agent: AgentProcess?,
    logs: SnapshotStateList<String>,
    nodeAvailable: Boolean,
    autoStart: Boolean,
) {
    val scope = rememberCoroutineScope()
    val store = remember { WorkbenchStore(scope, PORT) }
    val api = remember { AgentApi(PORT) }
    var page by remember {
        mutableStateOf(
            ConsolePage.entries.firstOrNull {
                it.name.equals(System.getenv("TERMDESK_CONSOLE_PAGE"), ignoreCase = true)
            } ?: ConsolePage.Overview,
        )
    }
    var status by remember { mutableStateOf<AgentStatus?>(null) }
    var navExpanded by remember { mutableStateOf(System.getenv("TERMDESK_NAV") != "compact") }
    var eventsOpen by remember { mutableStateOf(System.getenv("TERMDESK_EVENTS") == "1") }
    var pairOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DeviceRow?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionRow?>(null) }
    var pathTarget by remember { mutableStateOf<dev.termdesk.desktop.workbench.KernelRow?>(null) }
    var registerKernel by remember { mutableStateOf(false) }
    var busyStart by remember { mutableStateOf(false) }

    suspend fun startAndWait() {
        if (!nodeAvailable || agent == null) {
            store.error = if (agent == null) "找不到 TermDesk 目录（可用 TERMDESK_ROOT 指定）" else "找不到 Node.js"
            return
        }
        busyStart = true
        agent.start(PORT)?.onFailure { store.error = it.message }
        for (i in 0 until 20) {
            if (File(termdeskHome(), ".termdesk/token").isFile) break
            delay(200)
        }
        busyStart = false
        store.connect()
    }

    LaunchedEffect(Unit) {
        if (autoStart) startAndWait() else store.connect()
    }
    LaunchedEffect(Unit) {
        while (true) {
            status = api.status()
            delay(3000)
        }
    }
    // A banner is a sentence, not a permanent fixture: it leaves on its own.
    LaunchedEffect(store.error) {
        val shown = store.error ?: return@LaunchedEffect
        delay(8000)
        if (store.error == shown) store.error = null
    }
    LaunchedEffect(store.info) {
        val shown = store.info ?: return@LaunchedEffect
        delay(5000)
        if (store.info == shown) store.info = null
    }

    Row(Modifier.fillMaxSize()) {
        Sidebar(
            store = store,
            status = status,
            page = page,
            expanded = navExpanded,
            busyStart = busyStart,
            onToggle = { navExpanded = !navExpanded },
            onPage = { page = it },
            onPair = { pairOpen = true },
            onStart = { scope.launch { startAndWait() } },
        )
        VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            PageBar(
                page = page,
                store = store,
                eventsOpen = eventsOpen,
                onRefresh = { store.refresh() },
                onToggleEvents = { eventsOpen = !eventsOpen },
                onPair = { pairOpen = true },
                onRegister = { registerKernel = true },
                onRescan = { store.rescanKernels() },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                store.error?.let { message ->
                    Box(Modifier.padding(top = 10.dp)) {
                        NoticeBanner(message, isError = true) { store.error = null }
                    }
                }
                store.info?.let { message ->
                    Box(Modifier.padding(top = 10.dp)) {
                        NoticeBanner(message, isError = false) { store.info = null }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 1760.dp).fillMaxSize()) {
                    when (page) {
                        ConsolePage.Overview -> OverviewPage(
                            store = store,
                            status = status,
                            onPair = { pairOpen = true },
                            onOpenDevices = { page = ConsolePage.Devices },
                            onOpenKernels = { page = ConsolePage.Kernels },
                            onOpenSessions = { page = ConsolePage.Sessions },
                            onStart = { scope.launch { startAndWait() } },
                        )
                        ConsolePage.Devices -> DevicesPage(
                            store = store,
                            onPair = { pairOpen = true },
                            onRename = { renameTarget = it },
                        )
                        ConsolePage.Kernels -> KernelsPage(
                            store = store,
                            onEditPath = { pathTarget = it },
                        )
                        ConsolePage.Sessions -> SessionsPage(
                            store = store,
                            onDelete = { deleteTarget = it },
                        )
                    }
                }
            }
            if (eventsOpen) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                EventsDrawer(store = store, logs = logs, onClose = { eventsOpen = false })
            }
        }
    }

    if (pairOpen) {
        PairPhoneDialog(api = api, onClose = { pairOpen = false }, onPaired = { store.loadPhones() })
    }
    renameTarget?.let { target ->
        RenamePhoneDialog(target, onDismiss = { renameTarget = null }) { name ->
            store.renamePhone(target.id, name)
            renameTarget = null
        }
    }
    pathTarget?.let { kernel ->
        KernelPathDialog(
            kernel = kernel,
            onDismiss = { pathTarget = null },
            onSave = { path ->
                store.setKernelPath(kernel.id, path)
                pathTarget = null
            },
        )
    }
    deleteTarget?.let { row ->
        DeleteSessionDialog(
            row = row,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                store.deleteSession(row)
                deleteTarget = null
            },
        )
    }
    if (registerKernel) {
        RegisterKernelDialog(
            onDismiss = { registerKernel = false },
            onRegister = { id, label, bin, args, transport ->
                store.registerKernel(id, label, bin, args, transport)
                registerKernel = false
            },
        )
    }
}

@Composable
private fun Sidebar(
    store: WorkbenchStore,
    status: AgentStatus?,
    page: ConsolePage,
    expanded: Boolean,
    busyStart: Boolean,
    onToggle: () -> Unit,
    onPage: (ConsolePage) -> Unit,
    onPair: () -> Unit,
    onStart: () -> Unit,
) {
    val width by animateDpAsState(if (expanded) 224.dp else 64.dp, label = "sidebar")
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.width(width).fillMaxHeight(),
    ) {
        Column(Modifier.fillMaxHeight().padding(vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).padding(start = if (expanded) 16.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
            ) {
                if (expanded) {
                    Column(Modifier.weight(1f)) {
                        Text("TermDesk", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "管理控制台",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.ChevronLeft, contentDescription = "收起侧边栏", modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                } else {
                    IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Menu, contentDescription = "展开侧边栏", modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            for (item in ConsolePage.entries) {
                NavItem(
                    item = item,
                    selected = item == page,
                    expanded = expanded,
                    badge = when (item) {
                        ConsolePage.Devices -> store.phones.size.takeIf { it > 0 }?.toString()
                        ConsolePage.Kernels -> store.kernels.count { it.available && it.enabled }.takeIf { it > 0 }?.toString()
                        ConsolePage.Sessions -> (store.chats.size).takeIf { it > 0 }?.toString()
                        ConsolePage.Overview -> null
                    },
                    onClick = { onPage(item) },
                )
            }
            Spacer(Modifier.weight(1f))
            if (expanded) {
                Text(
                    "代理",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusDot(store.connected)
                    Text(
                        if (store.connected) "已连接 :$PORT" else (store.reason ?: "未连接"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                status?.let {
                    Text(
                        buildString {
                            append(it.hostname ?: "这台电脑")
                            if (it.version != null) append(" · v${it.version}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                if (!store.connected) {
                    if (busyStart) {
                        Text("正在启动代理…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(16.dp))
                    } else {
                        TextButton(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            Text("启动本机代理")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TextButton(onClick = onPair) { Text("配对", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = { onPage(ConsolePage.Sessions) }) { Text("会话", style = MaterialTheme.typography.labelSmall) }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(store.connected, size = 9.dp)
                    if (!store.connected) {
                        IconButton(onClick = onStart, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = "启动本机代理", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    item: ConsolePage,
    selected: Boolean,
    expanded: Boolean,
    badge: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(7.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = if (expanded) 10.dp else 0.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
    ) {
        Icon(
            item.icon,
            contentDescription = item.label,
            modifier = Modifier.size(17.dp),
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            Spacer(Modifier.width(10.dp))
            Text(
                item.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            badge?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PageBar(
    page: ConsolePage,
    store: WorkbenchStore,
    eventsOpen: Boolean,
    onRefresh: () -> Unit,
    onToggleEvents: () -> Unit,
    onPair: () -> Unit,
    onRegister: () -> Unit,
    onRescan: () -> Unit,
) {
    val subtitle = when (page) {
        ConsolePage.Overview -> "这台电脑上的手机、内核与会话"
        ConsolePage.Devices -> {
            val valid = store.phones.count { !it.revoked }
            "${store.phones.size} 台已登记 · $valid 台有效"
        }
        ConsolePage.Kernels -> {
            val usable = store.kernels.count { it.available && it.enabled }
            "$usable 个可用 · 共 ${store.kernels.size} 个"
        }
        ConsolePage.Sessions -> "${store.chats.size} 个实时 · ${store.sessions.size} 条记录"
    }
    Row(
        Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(page.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (page) {
            ConsolePage.Devices -> Button(onClick = onPair) { Text("配对手机") }
            ConsolePage.Kernels -> {
                OutlinedButton(onClick = onRescan) { Text("重新扫描") }
                Button(onClick = onRegister) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("注册自定义")
                }
            }
            ConsolePage.Sessions, ConsolePage.Overview -> Unit
        }
        TextButton(onClick = onToggleEvents) { Text(if (eventsOpen) "收起事件" else "事件") }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EventsDrawer(store: WorkbenchStore, logs: SnapshotStateList<String>, onClose: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth().height(168.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ChipButton(tab == 0, "帧事件", { tab = 0 })
                ChipButton(tab == 1, "代理输出", { tab = 1 })
                Spacer(Modifier.weight(1f))
                Text(
                    if (tab == 0) "${store.frameLog.size} 条" else "${logs.size} 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "收起", modifier = Modifier.size(14.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            if (tab == 0) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 4.dp)) {
                    items(store.frameLog.takeLast(200).asReversed()) { frame ->
                        Text(
                            "${formatClock(frame.at)}  ${frame.type}${if (frame.detail.isBlank()) "" else "  ${frame.detail}"}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 4.dp)) {
                    items(logs.takeLast(300).asReversed()) { line ->
                        Text(
                            line.trimEnd(),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

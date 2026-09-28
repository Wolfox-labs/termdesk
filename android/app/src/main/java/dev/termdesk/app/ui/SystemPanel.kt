package dev.termdesk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.termdesk.app.data.HostStatus
import dev.termdesk.app.data.ProcessInfo
import dev.termdesk.app.data.ServiceInfo
import dev.termdesk.app.ui.theme.MeterChars

/** Sub-tabs inside the System section. */
enum class SystemTab(val label: String) {
    Metrics("概览"),
    Processes("进程"),
    Services("服务"),
}

@Composable
fun SystemSection(
    status: HostStatus?,
    processes: List<ProcessInfo>,
    services: List<ServiceInfo>,
    loading: Boolean,
    onRefreshProcesses: (String) -> Unit,
    onRefreshServices: (String) -> Unit,
    onKillProcess: (Int) -> Unit,
    onServiceAction: (String, String) -> Unit,
) {
    var tab by remember { mutableStateOf(SystemTab.Metrics) }

    // Refresh whenever a list tab becomes current.
    //
    // An earlier version guarded on `processes.isEmpty()`, which deadlocked:
    // once a request came back empty the guard prevented any retry, so the pane
    // stayed on "没有匹配的进程" forever. Requesting on every switch costs one
    // cheap round trip and always reflects reality.
    LaunchedEffect(tab) {
        when (tab) {
            SystemTab.Processes -> onRefreshProcesses("")
            SystemTab.Services -> onRefreshServices("")
            SystemTab.Metrics -> Unit
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabStrip(current = tab, onSelect = { tab = it }, loading = loading)

        when (tab) {
            SystemTab.Metrics -> MetricsPane(status)
            SystemTab.Processes -> ProcessPane(
                processes = processes,
                loading = loading,
                onRefresh = onRefreshProcesses,
                onKill = onKillProcess,
            )
            SystemTab.Services -> ServicePane(
                services = services,
                loading = loading,
                onRefresh = onRefreshServices,
                onAction = onServiceAction,
            )
        }
    }
}

@Composable
private fun TabStrip(current: SystemTab, onSelect: (SystemTab) -> Unit, loading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SystemTab.entries.forEach { entry ->
            val selected = entry == current
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                    )
                    .clickable { onSelect(entry) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

// ---- metrics ----

@Composable
private fun MetricsPane(status: HostStatus?) {
    if (status == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "正在读取主机状态…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MetricCard(
                label = "CPU",
                value = formatPercent(status.cpuUsagePercent),
                caption = "${status.cpuCores} 核",
                percent = status.cpuUsagePercent ?: 0.0,
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "内存",
                value = formatPercent(status.memoryUsedPercent),
                caption = "${formatBytes(status.memoryUsedBytes)} / ${formatBytes(status.memoryTotalBytes)}",
                percent = status.memoryUsedPercent,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            "${status.platform} · ${status.arch} · 已运行 ${formatUptime(status.uptimeSeconds)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            status.cpuModel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text("磁盘", style = MaterialTheme.typography.titleSmall)

        status.disks.forEach { disk ->
            DiskRow(
                root = disk.root,
                percent = disk.usedPercent,
                caption = "${formatBytes(disk.usedBytes)} / ${formatBytes(disk.totalBytes)}",
            )
        }
    }
}

// ---- shared search bar ----

@Composable
private fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            leadingIcon = {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = "清空", modifier = Modifier.size(16.dp))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh) {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = "刷新",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- processes ----

@Composable
private fun ProcessPane(
    processes: List<ProcessInfo>,
    loading: Boolean,
    onRefresh: (String) -> Unit,
    onKill: (Int) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var pendingKill by remember { mutableStateOf<ProcessInfo?>(null) }

    val shown = remember(processes, query) {
        if (query.isBlank()) processes
        else processes.filter { it.name.contains(query, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize()) {
        SearchBar(
            value = query,
            onValueChange = {
                query = it
                onRefresh(it)
            },
            placeholder = "按进程名筛选",
            onRefresh = { onRefresh(query) },
        )

        if (shown.isEmpty() && !loading) {
            EmptyHint("没有匹配的进程")
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.pid }) { proc ->
                ProcessRow(proc = proc, onKill = { pendingKill = proc })
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
        }
    }

    pendingKill?.let { target ->
        ConfirmDialog(
            title = "结束进程",
            body = "确定要结束 ${target.name} (pid ${target.pid}) 吗？未保存的数据会丢失。",
            confirmLabel = "结束进程",
            onConfirm = {
                onKill(target.pid)
                pendingKill = null
            },
            onDismiss = { pendingKill = null },
        )
    }
}

@Composable
private fun ProcessRow(proc: ProcessInfo, onKill: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                proc.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "pid ${proc.pid}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatBytes(proc.memBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (proc.cpuSeconds != null) {
                    Text(
                        "CPU ${formatSeconds(proc.cpuSeconds)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        IconButton(onClick = onKill) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "结束进程",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---- services ----

@Composable
private fun ServicePane(
    services: List<ServiceInfo>,
    loading: Boolean,
    onRefresh: (String) -> Unit,
    onAction: (String, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<ServiceInfo, String>?>(null) }

    val shown = remember(services, query) {
        if (query.isBlank()) services
        else services.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.displayName.contains(query, ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        SearchBar(
            value = query,
            onValueChange = {
                query = it
                onRefresh(it)
            },
            placeholder = "按服务名或描述筛选",
            onRefresh = { onRefresh(query) },
        )

        if (shown.isEmpty() && !loading) {
            EmptyHint("没有匹配的服务")
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.name }) { svc ->
                ServiceRow(
                    svc = svc,
                    onAction = { verb -> pending = svc to verb },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
        }
    }

    pending?.let { (svc, verb) ->
        val verbZh = mapOf("start" to "启动", "stop" to "停止", "restart" to "重启")[verb] ?: verb
        ConfirmDialog(
            title = "$verbZh 服务",
            body = "确定要$verbZh ${svc.displayName} 吗？",
            confirmLabel = verbZh,
            onConfirm = {
                onAction(svc.name, verb)
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun ServiceRow(svc: ServiceInfo, onAction: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (svc.isRunning) MeterChars.ok else MaterialTheme.colorScheme.outline),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                svc.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${svc.name} · ${svc.status}${svc.startType?.let { " · $it" } ?: ""}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Only running services can be restarted or stopped, so the middle
        // control is hidden for stopped ones rather than shown doing nothing.
        if (svc.isRunning) {
            IconButton(onClick = { onAction("restart") }) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "重启",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(onClick = { onAction(if (svc.isRunning) "stop" else "start") }) {
            Icon(
                imageVector = if (svc.isRunning) Icons.Outlined.Close else Icons.Outlined.PlayArrow,
                contentDescription = if (svc.isRunning) "停止" else "启动",
                tint = if (svc.isRunning) MaterialTheme.colorScheme.error else MeterChars.ok,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun formatSeconds(seconds: Double): String = when {
    seconds >= 3600 -> String.format(java.util.Locale.US, "%.1fh", seconds / 3600)
    seconds >= 60 -> String.format(java.util.Locale.US, "%.0fm", seconds / 60)
    else -> String.format(java.util.Locale.US, "%.0fs", seconds)
}

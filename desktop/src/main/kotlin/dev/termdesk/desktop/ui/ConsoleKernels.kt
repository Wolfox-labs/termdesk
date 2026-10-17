package dev.termdesk.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.app.ui.theme.Semantic
import dev.termdesk.desktop.workbench.KernelRow
import dev.termdesk.desktop.workbench.WorkbenchStore

/**
 * What this computer can actually run, read from the computer every time.
 *
 * The list is not a catalogue of brands: each row is the result of discovery
 * (a resolved executable) plus a local enable/disable preference. A kernel that
 * is not installed is hidden by default — showing a hopeful row for software the
 * person does not have is how the old list became noise.
 */
@Composable
internal fun KernelsPage(
    store: WorkbenchStore,
    onEditPath: (KernelRow) -> Unit,
) {
    var showUndetected by remember { mutableStateOf(false) }
    val installed = store.kernels.filter { it.available }
    val visible = if (showUndetected) store.kernels else installed

    Column(Modifier.fillMaxSize().padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (store.scannedAt > 0) {
                    "检测于 ${formatClock(store.scannedAt)} · 本机实时读取，不是写死的清单"
                } else {
                    "正在读取本机内核…"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
                Checkbox(checked = showUndetected, onCheckedChange = { showUndetected = it })
                Text("显示未检测到的", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (visible.isEmpty()) {
            EmptyState(
                title = if (store.kernels.isEmpty()) "还没有读取到内核" else "没有检测到已安装的内核",
                hint = if (store.kernels.isEmpty()) {
                    "确认代理已连接；代理会读取本机的 Codex、OpenCode、MiMo 等可执行文件。"
                } else {
                    "这台电脑没有找到可运行的 Agent。可以点“注册自定义”指定一个可执行文件，或勾选“显示未检测到的”查看检测位置。"
                },
                action = { TextButton(onClick = { showUndetected = true }) { Text("显示全部") } },
            )
            return@Column
        }

        ConsoleTableHeader(
            listOf(
                Column("内核", 2.3f),
                Column("状态", 1.0f),
                Column("可执行文件 / 检测结果", 3.4f),
                Column("启用", 0.6f),
                Column("操作", 0.8f),
            ),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.id }) { kernel ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(2.3f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                kernel.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!kernel.available) {
                                Pill("未安装", MaterialTheme.colorScheme.error)
                            } else if (!kernel.enabled) {
                                Pill("已禁用", MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (kernel.custom) Pill("自定义", MaterialTheme.colorScheme.primary)
                            if (kernel.override) Pill("手动路径", MaterialTheme.colorScheme.secondary)
                        }
                        Text(
                            "${kernel.id} · ${kernel.tier}" + if (kernel.transport.isBlank()) "" else " · ${kernel.transport}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Box(Modifier.weight(1.0f)) {
                        when {
                            !kernel.available -> Pill("未检测到", MaterialTheme.colorScheme.error)
                            !kernel.enabled -> Pill("已禁用", MaterialTheme.colorScheme.onSurfaceVariant)
                            kernel.selectable -> Pill("可用", Semantic.current.success)
                            else -> Pill("未验证", Semantic.current.warning)
                        }
                    }
                    Column(Modifier.weight(3.4f).padding(end = 12.dp)) {
                        Text(
                            kernel.path.ifBlank { kernel.detail },
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (kernel.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (kernel.override) {
                            Text(
                                "自动检测：${kernel.autoPath ?: "没有找到"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else if (kernel.available && kernel.canonicalDetail().isNotBlank()) {
                            Text(
                                kernel.canonicalDetail(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Box(Modifier.weight(0.6f)) {
                        Switch(
                            checked = kernel.enabled && kernel.available,
                            onCheckedChange = { on ->
                                store.manageKernel(if (on) "enable" else "disable", kernel.id)
                            },
                            enabled = kernel.available,
                        )
                    }
                    Box(Modifier.weight(0.8f)) {
                        var menu by remember(kernel.id) { mutableStateOf(false) }
                        TextButton(onClick = { menu = true }) {
                            Text("操作", style = MaterialTheme.typography.labelSmall)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("重新检测") },
                                onClick = { menu = false; store.probeKernel(kernel.id) },
                            )
                            DropdownMenuItem(
                                text = { Text("修改可执行文件…") },
                                onClick = { menu = false; onEditPath(kernel) },
                            )
                            if (kernel.override) {
                                DropdownMenuItem(
                                    text = { Text("恢复自动检测") },
                                    onClick = { menu = false; store.clearKernelPath(kernel.id) },
                                )
                            }
                            if (kernel.command.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text("复制启动命令") },
                                    onClick = {
                                        menu = false
                                        copyToClipboard(kernel.command)
                                        store.info = "已复制：${kernel.command}"
                                    },
                                )
                            }
                            if (kernel.path.isNotBlank() && store.activeDeviceId == "local") {
                                DropdownMenuItem(
                                    text = { Text("在电脑上打开位置") },
                                    onClick = { menu = false; store.revealPath(kernel.path) },
                                )
                            }
                            if (kernel.custom) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("移除自定义内核", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; store.removeKernel(kernel.id) },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "“重新检测”会真的启动一次 ACP 握手（其它内核只重新读取路径）；“禁用”只是这台电脑的本地偏好，不会卸载任何东西。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "路径覆盖与自定义内核存放在 ~/.termdesk/（kernel-paths.json、kernels/）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The agent's own sentence about how it talks to this kernel. */
private fun KernelRow.canonicalDetail(): String =
    if (detail.startsWith("已在这台电脑禁用")) "" else detail

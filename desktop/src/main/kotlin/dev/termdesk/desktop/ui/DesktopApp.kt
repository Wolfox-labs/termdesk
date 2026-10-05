package dev.termdesk.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.termdesk.app.ui.theme.Semantic
import dev.termdesk.desktop.agent.AgentApi
import dev.termdesk.desktop.agent.AgentProcess
import dev.termdesk.desktop.agent.AgentStatus
import dev.termdesk.desktop.agent.Kernel
import dev.termdesk.desktop.agent.PairInfo
import dev.termdesk.desktop.agent.QrGrid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

/**
 * The desktop window.
 *
 * Design rules, taken from the phone:
 *   - ONE bar at the top. No second row of chrome, ever — the phone app made
 *     that mistake and it was removed there for the same reason.
 *   - Cards on a paper background, 14dp radii, the same One Monokai palette
 *     (imported from the Android theme, so it cannot drift).
 *   - State is stated, not implied: every kernel says why it is or is not
 *     selectable, because that is the question this window exists to answer.
 */
@Composable
fun DesktopApp(
    agent: AgentProcess?,
    logs: SnapshotStateList<String>,
    nodeAvailable: Boolean,
    autoStart: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val api = remember { AgentApi(PORT) }

    var status by remember { mutableStateOf<AgentStatus?>(null) }
    var kernels by remember { mutableStateOf<List<Kernel>>(emptyList()) }
    var pair by remember { mutableStateOf<PairInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // An older agent answers /healthz but has none of the JSON endpoints.
    var legacy by remember { mutableStateOf(false) }

    // Optional one-step start. Off by default: nothing on this machine comes up
    // by itself, and the button is right there.
    LaunchedEffect(Unit) {
        if (autoStart) agent?.start(PORT)?.onFailure { message = it.message }
    }

    // One poll loop for the whole window. State every 2s; the kernel table less
    // often, because probing it spawns a protocol handshake per ACP kernel.
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            status = api.status()
            val live = status != null
            legacy = !live && api.health()
            if (live && tick % 7 == 0) kernels = api.kernels()
            if (!live) { kernels = emptyList(); pair = null }
            if (live && status?.tunnel?.running == true && (pair == null || tick % 5 == 0)) {
                pair = api.pair()
            }
            tick += 1
            delay(2000)
        }
    }

    val running = status != null

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(running = running || legacy, status = status)

            Row(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1.05f).fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    StatusCard(status, kernels, legacy)
                    KernelCard(kernels, running, legacy)
                    ActionCard(
                        running = running || legacy,
                        owned = agent?.isOwner == true,
                        canStart = agent != null && nodeAvailable,
                        message = message,
                        onStart = {
                            val target = agent
                            if (target == null) {
                                message = "找不到 TermDesk 目录（可用 TERMDESK_ROOT 指定）"
                            } else {
                                target.start(PORT).onFailure { message = it.message }
                                message = null
                            }
                        },
                        onStop = { agent?.stop() },
                        onRestart = {
                            agent?.stop()
                            scope.launch {
                                delay(900)
                                agent?.start(PORT)?.onFailure { message = it.message }
                            }
                        },
                        onPair = { openBrowser("http://127.0.0.1:$PORT/pair") },
                        onInstall = { openBrowser("http://127.0.0.1:$PORT/app") },
                    )
                }

                Column(
                    modifier = Modifier.weight(0.95f).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PairCard(status, pair, legacy)
                    LogCard(logs, Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The loopback port the window talks to.
 *
 * Overridable so a second agent — or a verification run — can be driven without
 * disturbing the one already listening on the default port.
 */
private val PORT: Int = System.getenv("TERMDESK_PORT")?.toIntOrNull() ?: 7420

/** The single top bar: title, state, version. Nothing stacked under it. */
@Composable
private fun TopBar(running: Boolean, status: AgentStatus?) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("TermDesk PC", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Text(
                status?.hostname ?: "未连接",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Pill(if (running) "运行中" else "已停止", if (running) Semantic.current.success else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Pill("v" + (status?.version ?: "—"), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = 0.15f)) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

@Composable
private fun StatusCard(status: AgentStatus?, kernels: List<Kernel>, legacy: Boolean) {
    SectionCard("状态", if (status == null && !legacy) "代理未运行" else null) {
        if (status == null && legacy) {
            Text(
                "代理在运行，但它是在这次改动之前启动的：重启一次就能看到完整信息（内核、公网地址、二维码）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        if (status == null) {
            Text(
                "点下面的『启动』把代理跑起来。启动后手机就能连上，公网地址也会一起建立。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Field("监听", "${status.host ?: "0.0.0.0"}:${status.port ?: PORT}")
        Field("终端", if (status.shell) "已开启（手机可执行任意命令）" else "已关闭")
        Field("目录", status.roots.joinToString("   ").ifEmpty { "—" })
        Field("运行", formatUptime(status.uptimeMs))
        Field(
            "内核",
            kernels.count { it.selectable }.toString() + " 个可选 · " + kernels.count { it.available && !it.selectable } + " 个已发现未接入",
        )
        if (status.lanUrls.isNotEmpty()) Field("局域网", status.lanUrls.take(2).joinToString("   "))
    }
}

@Composable
private fun KernelCard(kernels: List<Kernel>, running: Boolean, legacy: Boolean) {
    SectionCard("内核", "手机可选的就是下面打勾的") {
        if (legacy) {
            Text("重启代理后这里会列出这台电脑上可用的内核。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        if (!running) {
            Text("代理运行后这里会列出这台电脑上可用的内核。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        if (kernels.isEmpty()) {
            Text("正在探测…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        kernels.forEach { kernel -> KernelRow(kernel) }
    }
}

@Composable
private fun KernelRow(kernel: Kernel) {
    val mark = when {
        kernel.selectable -> "✔"
        kernel.available -> "○"
        else -> "·"
    }
    val markColor = when {
        kernel.selectable -> Semantic.current.success
        kernel.available -> Semantic.current.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(mark, color = markColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kernel.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Text(kernel.tier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            kernel.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ActionCard(
    running: Boolean,
    owned: Boolean,
    canStart: Boolean,
    message: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onPair: () -> Unit,
    onInstall: () -> Unit,
) {
    SectionCard("操作") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!running) {
                Button(onClick = onStart, enabled = canStart) { Text("启动") }
            } else if (owned) {
                OutlinedButton(onClick = onStop) { Text("停止") }
                Button(onClick = onRestart) { Text("重新启动") }
            } else {
                // Honest about ownership: the agent is up, but this window did
                // not start it and must not claim it can stop it.
                Text(
                    "代理正在运行，但不是这个窗口启动的（可能是 TermDesk.bat）。关掉那个窗口即可停止。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onPair) { Text("打开配对页") }
            TextButton(onClick = onInstall) { Text("安装/升级 App") }
        }
        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "这台电脑不会开机自启：窗口关掉、代理就停。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PairCard(status: AgentStatus?, pair: PairInfo?, legacy: Boolean) {
    SectionCard("手机配对", status?.tunnel?.label) {
        val grid = pair?.qr
        if (legacy) {
            Text(
                "重启代理后这里会出现扫码用的二维码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        if (grid == null || grid.rows.isEmpty()) {
            Text(
                if (status?.tunnel?.running == true) "正在生成二维码…" else "公网地址建立后这里会出现二维码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.Top) {
            Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                QrView(grid, 172.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("地址", pair.url ?: "—")
                Field("形态", if (status?.tunnel?.stable == true) "固定域名（重启不变）" else "临时地址（重启会变）")
                Text(
                    "用手机相机扫左边的码：手机会直接打开 TermDesk 并完成配对，不用手输令牌。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QrView(grid: QrGrid, size: Dp) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(size)) {
        drawRect(Color.White)
        val n = grid.rows.size
        if (n == 0) return@Canvas
        val cell = this.size.minDimension / n
        for (y in 0 until n) {
            val row = grid.rows[y]
            for (x in 0 until n) {
                if (x < row.length && row[x] == '1') {
                    // Half a pixel of overlap: at this size the rounding gaps
                    // between modules would otherwise read as noise to a scanner.
                    drawRect(ink, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.6f, cell + 0.6f))
                }
            }
        }
    }
}

@Composable
private fun LogCard(logs: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }
    SectionCard("代理输出", "按顺序记下每一条状态", modifier = modifier) {
        if (logs.isEmpty()) {
            Text("还没有输出。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(logs.size) { index ->
                Text(
                    logs[index].trimEnd(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatUptime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours} 小时 ${minutes} 分"
        minutes > 0 -> "${minutes} 分 ${seconds} 秒"
        else -> "${seconds} 秒"
    }
}

private fun openBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
    }
}
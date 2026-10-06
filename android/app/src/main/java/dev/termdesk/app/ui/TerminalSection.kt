package dev.termdesk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.termdesk.app.data.TermLine
import dev.termdesk.app.data.TerminalInfo
import dev.termdesk.app.ui.theme.Semantic

/** Commands worth one tap on a phone keyboard, per backend: the two machines
 *  speak different shells, so their shortcuts are not interchangeable. */
private fun quickCommands(backend: String): List<Pair<String, String>> = if (backend == "local") {
    listOf(
        "ls" to "ls -la",
        "pwd" to "pwd",
        "uname" to "uname -a",
        "disk" to "df -h .",
        "python" to "python3 -V",
        "procs" to "ps -ef | head -20",
        "env" to "echo \$PATH",
    )
} else {
    listOf(
        "ls" to "dir",
        "cd .." to "cd ..",
        "git status" to "git status",
        "git diff" to "git diff --stat",
        "top" to "Get-Process | Sort-Object CPU -Descending | Select-Object -First 15",
        "ip" to "ipconfig",
        "ports" to "netstat -ano | Select-String LISTEN",
        "env" to "\$env:PATH -split ';'",
    )
}

/**
 * `cd <dir>`, quoted for whichever shell is on the other end.
 *
 * The two backends really do quote differently, and not in a cosmetic way: a path
 * with a space works in both once quoted, but a path containing a quote only
 * works if it is escaped the way that shell expects. This is the difference
 * between reaching a directory called `it's` and not reaching it.
 */
private fun cdCommand(dir: String, kernel: String): String =
    if (kernel == "local") {
        "cd '" + dir.replace("'", "'\\''") + "'"
    } else {
        "cd \"" + dir.replace("\"", "`\"") + "\""
    }

@Composable
fun TerminalSection(
    lines: List<TermLine>,
    busy: Boolean,
    sessionId: String?,
    cwd: String?,
    sessions: List<TerminalInfo>,
    onSwitch: (String) -> Unit,
    onNewTerminal: () -> Unit,
    onListTerminals: () -> Unit,
    unavailable: String?,
    onOpen: () -> Unit,
    onRun: (String) -> Unit,
    kernel: String,
    onInterrupt: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    var input by remember { mutableStateOf("" ) }
    var history by remember { mutableStateOf(listOf<String>()) }
    var historyIndex by remember { mutableStateOf(-1) }
    var cwdDialog by remember { mutableStateOf(false) }
    var terminalList by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Open the session lazily, the first time this pane is shown - for whichever
    // backend this app is connected to. It used to be remote-only, which left the
    // local kernel with no session at all: every command then answered "no session"
    // instead of running.
    LaunchedEffect(sessionId, unavailable) {
        if (sessionId == null && unavailable == null) onOpen()
    }

    // Follow new output, the way a terminal should.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    val submit = {
        val cmd = input.trim()
        if (cmd.isNotEmpty()) {
            history = (history + cmd).takeLast(100)
            historyIndex = -1
            onRun(cmd)
            input = ""
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Header: status + controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tapping the name opens the terminal list. The phone holds several
            // shells at once (the agent keeps one per session, scrollback and all),
            // so this is the way between them - and the way to start another.
            Text(
                text = when {
                    kernel == "local" -> (sessionId?.let { "$it · bash" } ?: "本地内核 · bash")
                    unavailable != null -> "不可用"
                    sessionId == null -> "连接中…"
                    else -> "${sessionId} · PowerShell"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (unavailable != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = unavailable == null) {
                        terminalList = true
                        onListTerminals()
                    },
            )
            if (busy) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(15.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
            }
            IconButton(onClick = onClear, enabled = lines.isNotEmpty()) {
                Icon(Icons.Outlined.ClearAll, contentDescription = "清屏", modifier = Modifier.size(19.dp))
            }
            if (sessionId != null) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "关闭终端",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }

        // Where the shell is, and the way to move it.
        //
        // The directory is the shell's own report - the agent sends it with every
        // command's exit - so it stays right even when a command cd's by itself,
        // instead of the phone remembering what it thinks it typed. Hidden when
        // there is no shell to ask.
        if (unavailable == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { cwdDialog = true }
                    .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = cwd ?: "目录未知",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "切换",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (terminalList) {
            AlertDialog(
                onDismissRequest = { terminalList = false },
                title = { Text("终端") },
                text = {
                    Column {
                        if (sessions.isEmpty()) {
                            Text(
                                "还没有其它终端。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        sessions.forEach { s ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        terminalList = false
                                        onSwitch(s.id)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    s.id,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (s.id == sessionId) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (s.id == sessionId) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    when {
                                        s.running -> "运行中"
                                        s.id == sessionId -> "当前"
                                        else -> "空闲"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        terminalList = false
                        onNewTerminal()
                    }) { Text("新建终端") }
                },
                dismissButton = {
                    TextButton(onClick = { terminalList = false }) { Text("取消") }
                },
            )
        }

        if (cwdDialog) {
            var path by remember(cwd) { mutableStateOf(cwd.orEmpty()) }
            AlertDialog(
                onDismissRequest = { cwdDialog = false },
                title = { Text("切换工作目录") },
                text = {
                    Column {
                        Text(
                            "填一个目录，终端就切过去。相对路径以当前目录为基准；" +
                                "目录不存在时 shell 会报错，位置不会变。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = path,
                            onValueChange = { path = it },
                            singleLine = true,
                            label = { Text("目录") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val target = path.trim()
                        cwdDialog = false
                        if (target.isNotEmpty()) onRun(cdCommand(target, kernel))
                    }) { Text("切换") }
                },
                dismissButton = {
                    TextButton(onClick = { cwdDialog = false }) { Text("取消") }
                },
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // Output
        Box(Modifier.weight(1f)) {
            if (unavailable != null) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        unavailable,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "在电脑上运行：node src/server.js --enable-shell",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (lines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "输入命令开始",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Semantic.current.terminalBackground)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    items(lines.size) { i ->
                        val line = lines[i]
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            ),
                            color = when (line.stream) {
                                TermLine.Stream.INPUT -> MaterialTheme.colorScheme.primary
                                TermLine.Stream.STDERR -> MaterialTheme.colorScheme.error
                                // System notices use the Monokai warning hue so
                                // they stand apart from normal output.
                                TermLine.Stream.SYSTEM -> Semantic.current.warning
                                TermLine.Stream.STDOUT -> Semantic.current.terminalForeground
                            },
                        )
                    }
                }
            }
        }

        // Quick commands. The local backend has no remote session id, and its
        // shortcuts are the sandbox's own commands, not PowerShell's.
        if (kernel == "local" || sessionId != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                quickCommands(kernel).forEach { (label, cmd) ->
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(7.dp))
                            .clickable { onRun(cmd) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        // History nav + input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 8.dp, end = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    if (history.isEmpty()) return@IconButton
                    historyIndex = if (historyIndex == -1) history.lastIndex
                    else maxOf(0, historyIndex - 1)
                    input = history[historyIndex]
                },
                enabled = history.isNotEmpty() && sessionId != null,
            ) {
                Icon(
                    Icons.Outlined.KeyboardArrowUp,
                    contentDescription = "上一条命令",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                enabled = sessionId != null,
                placeholder = { Text("输入命令", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                // Plain text keys: no autocorrect or capitalisation, which would
                // mangle shell commands.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                modifier = Modifier.weight(1f),
            )

            if (busy) {
                IconButton(onClick = onInterrupt) {
                    Icon(
                        Icons.Outlined.Stop,
                        contentDescription = "中断",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                IconButton(onClick = submit, enabled = input.isNotBlank()) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = "执行",
                        tint = if (input.isNotBlank()) Semantic.current.success
                        else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

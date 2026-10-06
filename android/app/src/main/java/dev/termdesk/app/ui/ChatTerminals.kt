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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.ChatTerminal
import dev.termdesk.app.data.TerminalView
import dev.termdesk.app.ui.theme.MeterChars

/**
 * The command lines this conversation ran, over the top of it.
 *
 * It covers the conversation rather than sharing the width: a phone is about
 * 400dp, and output wants all of it. Two kinds of entry live here and the panel
 * says which is which — a command the kernel ran itself can be read and stopped,
 * one it asked this machine to run can also be typed into.
 */
@Composable
fun ChatTerminalsPanel(
    terminals: List<ChatTerminal>,
    view: TerminalView?,
    onOpen: (ChatTerminal) -> Unit,
    onInput: (String) -> Unit,
    onStop: (String) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    val outputState = rememberScrollState()

    // Output arrives while the panel is open; keep the tail in view.
    LaunchedEffect(view?.output?.length) {
        if (view?.output?.isNotEmpty() == true) outputState.scrollTo(outputState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (terminals.isEmpty()) "命令行" else "命令行 · ${terminals.size}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "刷新命令行清单",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "收起命令行",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))

        if (terminals.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("这个会话还没有跑过命令", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "内核在这个会话里执行的每条命令都会出现在这里；ACP 内核的命令由本机执行，可以直接输入。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        // The list sizes to its content and stops at a third of the screen: a
        // conversation can run many commands, and the output below is what the
        // panel is for.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp),
            state = rememberLazyListState(),
        ) {
            items(terminals, key = { it.id }) { terminal ->
                val selected = terminal.id == view?.terminalId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.background,
                        )
                        .clickable { onOpen(terminal) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    terminal.running -> MeterChars.warn
                                    terminal.exitCode == 0 -> MeterChars.ok
                                    else -> MaterialTheme.colorScheme.outline
                                },
                            ),
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            terminal.command.ifBlank { terminal.id },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            listOfNotNull(
                                if (terminal.running) "运行中" else "已结束 ${terminal.exitCode ?: ""}".trim(),
                                if (terminal.ours) "本机执行" else "内核执行",
                                shortCwd(terminal.cwd),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        val current = view
        if (current == null) {
            Text(
                "点一条命令看它的输出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .verticalScroll(outputState)
                    .padding(10.dp),
            ) {
                Text(
                    current.command,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        current.loading && current.output.isEmpty() -> "正在取输出…"
                        current.output.isEmpty() -> "（还没有输出）"
                        else -> current.output
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                if (current.truncated) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "（前面的输出已被截断）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    enabled = current.writable,
                    placeholder = {
                        Text(
                            when {
                                current.writable -> "输入并回车"
                                current.origin == "kernel" -> "这条命令已经结束"
                                else -> "内核自己执行的命令没有写入通道"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (typed.isNotEmpty()) {
                            onInput("$typed\n")
                            typed = ""
                        }
                    }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                if (current.state == "running" && current.origin == "kernel") {
                    TextButton(onClick = { onStop(current.terminalId) }) { Text("停止") }
                } else {
                    TextButton(
                        onClick = {
                            if (typed.isNotEmpty()) {
                                onInput("$typed\n")
                                typed = ""
                            }
                        },
                        enabled = current.writable,
                    ) { Text("发送") }
                }
            }
        }
    }
}

private fun shortCwd(cwd: String): String {
    if (cwd.isBlank()) return ""
    val parts = cwd.trimEnd('\\', '/').split('\\', '/').filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> cwd
        parts.size == 1 -> parts[0]
        else -> "${parts[parts.size - 2]}/${parts.last()}"
    }
}

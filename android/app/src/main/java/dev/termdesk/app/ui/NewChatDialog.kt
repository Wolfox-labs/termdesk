package dev.termdesk.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.KernelInfo
import dev.termdesk.app.data.WorkspaceInfo

/**
 * "Where, and on which assistant?" — asked before a conversation is created.
 *
 * The home page keeps a standing choice, but that choice is about the *next* turn
 * of work, not about every subject: pressing "+" in the conversations list is
 * starting something new, and it may belong in another directory or on another
 * kernel. Creating straight from the standing defaults made that impossible to say
 * at the one moment it is easiest to say it.
 *
 * Both lists are the machine's own answers — kernels that reported themselves
 * selectable, and directories this app has actually seen (workspaces plus the
 * roots the agent allows) — with a free-text path for anything else. Nothing is
 * invented, and the PC still validates the directory before creating anything.
 */
@Composable
internal fun NewChatDialog(
    engines: List<KernelInfo>,
    selectedEngine: String?,
    workspaces: List<WorkspaceInfo>,
    roots: List<String>,
    currentCwd: String,
    onDismiss: () -> Unit,
    onCreate: (cwd: String, engine: String) -> Unit,
) {
    val selectable = remember(engines) { engines.filter { it.selectable } }
    var engine by remember(selectable, selectedEngine) {
        mutableStateOf(
            selectedEngine?.takeIf { id -> selectable.any { it.id == id } }
                ?: selectable.firstOrNull()?.id,
        )
    }
    var cwd by remember(currentCwd) { mutableStateOf(currentCwd) }
    val candidates = remember(workspaces, roots, currentCwd) {
        (workspaces.map { it.cwd } + roots + currentCwd)
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建会话", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "助手",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                if (selectable.isEmpty()) {
                    Text(
                        "未检测到可用助手。先在设置中安装并登录一个助手。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                selectable.forEach { kernel ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .selectable(
                                selected = kernel.id == engine,
                                role = Role.RadioButton,
                                onClick = { engine = kernel.id },
                            )
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kernel.id == engine, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(kernel.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "工作目录",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                for (path in candidates) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { cwd = path }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            shortDirectory(path),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (path == cwd) {
                            Text(
                                "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    singleLine = true,
                    label = { Text("或输入路径", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            val chosen = engine
            TextButton(
                enabled = chosen != null && cwd.isNotBlank(),
                onClick = { if (chosen != null) onCreate(cwd.trim(), chosen) },
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** The tail of a path is what tells them apart on a 400dp screen. */
private fun shortDirectory(path: String): String {
    val parts = path.trimEnd('\\', '/').split('\\', '/').filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> path
        parts.size == 1 -> parts[0]
        else -> "${parts[parts.size - 2]}/${parts.last()}"
    }
}

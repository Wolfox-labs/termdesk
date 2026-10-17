package dev.termdesk.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.desktop.agent.AgentApi
import dev.termdesk.desktop.agent.PairInfo
import dev.termdesk.desktop.workbench.DeviceRow
import dev.termdesk.desktop.workbench.KernelRow
import dev.termdesk.desktop.workbench.SessionRow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Pairing, in the window.
 *
 * The QR is drawn here and the credential never leaves this machine in a
 * browser history: generate, scan, close. Each generation registers one phone,
 * so a code that is never scanned leaves a removable row rather than a reused
 * credential.
 */
@Composable
internal fun PairPhoneDialog(api: AgentApi, onClose: () -> Unit, onPaired: () -> Unit) {
    var info by remember { mutableStateOf<PairInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun load() {
        loading = true
        error = null
        scope.launch {
            val pair = api.pair()
            if (pair == null || !pair.ok) {
                error = "代理没有返回配对信息：确认它在运行，且版本包含 /pair.json"
            }
            info = pair
            loading = false
            onPaired()
        }
    }
    LaunchedEffect(Unit) { load() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("配对手机") },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 340.dp, max = 460.dp).heightIn(max = 580.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                when {
                    loading -> Text("正在生成一次性配对码…", style = MaterialTheme.typography.bodySmall)
                    error != null -> Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    info?.qr != null -> {
                        QrView(info!!.qr!!, 220.dp)
                        Text(
                            "用手机 TermDesk 扫码",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "码只在这个窗口里；不会打开浏览器。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        PairValueRow("地址", info!!.url ?: "—")
                        PairValueRow("设备", info!!.deviceId ?: "—")
                        Text(
                            "每次生成都会登记一台手机；没扫的可以在“设备”页删除。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { load() }) { Text("重新生成") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    info?.url?.let {
                        copyToClipboard(it)
                    }
                }) { Text("复制地址") }
                TextButton(onClick = onClose) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun PairValueRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { copyToClipboard(value) }, modifier = Modifier.height(28.dp)) {
            Text("复制", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun RenamePhoneDialog(target: DeviceRow, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(target.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名手机") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    target.id,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.widthIn(min = 320.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(name.trim()) }, enabled = name.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * Point a kernel at another executable.
 *
 * Both sides validate: this dialog catches an empty or non-existent file, and
 * the agent re-checks with the same rules discovery uses, so a saved path is a
 * path that can be spawned.
 */
@Composable
internal fun KernelPathDialog(kernel: KernelRow, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var path by remember { mutableStateOf(kernel.path.ifBlank { kernel.autoPath }) }
    var problem by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改可执行文件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    "${kernel.label} · ${kernel.id}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "只记录位置，不复制、不下载、不安装。保存后代理会立即重新检测。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    path,
                    { path = it; problem = null },
                    label = { Text("可执行文件（完整路径）") },
                    singleLine = true,
                    isError = problem != null,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.widthIn(min = 440.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        pickFile("选择 ${kernel.label} 的可执行文件")?.let {
                            path = it
                            problem = null
                        }
                    }) { Text("浏览…") }
                    if (kernel.autoPath.isNotBlank()) {
                        TextButton(onClick = { path = kernel.autoPath }) { Text("使用自动检测到的位置") }
                    }
                }
                if (kernel.autoPath.isNotBlank() && kernel.autoPath != path) {
                    Text(
                        "自动检测到：${kernel.autoPath}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                problem?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val target = path.trim()
                when {
                    target.isBlank() -> problem = "路径不能为空"
                    !File(target).isFile -> problem = "这个路径下没有文件"
                    else -> onSave(target)
                }
            }) { Text("保存并检测") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun RegisterKernelDialog(
    onDismiss: () -> Unit,
    onRegister: (String, String, String, List<String>, String) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var bin by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("acp") }
    var problem by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("注册自定义内核") },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 460.dp).heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    "写一份清单到 ~/.termdesk/kernels；不会安装或下载任何东西。ACP 内核需要自己支持 ACP 协议。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    id,
                    { id = it.lowercase(); problem = null },
                    label = { Text("标识（a-z0-9-_，2–41 位）") },
                    singleLine = true,
                )
                OutlinedTextField(label, { label = it; problem = null }, label = { Text("名称") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        bin,
                        { bin = it; problem = null },
                        label = { Text("可执行文件 / 命令") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { pickFile("选择可执行文件")?.let { bin = it } }) { Text("浏览…") }
                }
                OutlinedTextField(
                    args,
                    { args = it },
                    label = { Text("参数（空格分隔，可空）") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("acp" to "ACP 协议", "cli" to "CLI 输出").forEach { (value, text) ->
                        TextButton(onClick = { transport = value }) {
                            Text(
                                (if (transport == value) "● " else "○ ") + text,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                problem?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        !Regex("^[a-z0-9][a-z0-9_-]{1,40}$").matches(id) -> problem = "标识只能用小写字母、数字、_ 和 -，2–41 位"
                        label.isBlank() -> problem = "需要名称"
                        bin.isBlank() -> problem = "需要可执行文件"
                        else -> onRegister(id, label, bin, args.split(" ").filter { it.isNotBlank() }, transport)
                    }
                },
            ) { Text("注册") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun DeleteSessionDialog(row: SessionRow, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这条记录？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "${engineLabel(row.engine)} · ${row.updatedAt.replace("T", " ").take(16)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    row.path.ifBlank { "（内核没有提供路径）" },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "会删除磁盘上的会话文件，不能撤销。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

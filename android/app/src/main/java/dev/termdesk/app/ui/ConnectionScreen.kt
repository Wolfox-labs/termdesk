package dev.termdesk.app.ui

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.LinkState
import dev.termdesk.app.data.PairedComputer

/**
 * Pairing screen, and the list of computers this phone already knows.
 *
 * The list comes first because it is the common case: with two computers paired,
 * "connect to the other one" should be one tap, not a re-pairing with a fresh
 * code. The manual fields below are for adding a computer.
 */
@Composable
fun ConnectionScreen(
    link: LinkState,
    initialUrl: String,
    initialToken: String,
    computers: List<PairedComputer>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onForgetComputer: (String) -> Unit,
    onConnect: (String, String) -> Unit,
    onClose: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var confirmForgetCurrent by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf(initialToken) }
    val connecting = link is LinkState.Connecting
    // The address already has a credential on this phone, so the box can stay empty.
    val knownCredential = computers.any { it.url == url.trim() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "TermDesk",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "连接电脑",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (computers.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "已配对的电脑",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            computers.forEach { computer ->
                ComputerRow(
                    computer = computer,
                    active = computer.id == activeId,
                    busy = connecting && computer.id == activeId,
                    onSelect = { onSelect(computer.id) },
                    onForget = { onForgetComputer(computer.id) },
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        Text(
            text = if (computers.isEmpty()) "输入电脑提供的连接地址和配对码" else "添加电脑",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = url,
            // P1：中文输入法会把 `ws://` 当成拼音变成「晚上：、、」。这里只保留地址里
            // 可能出现的 ASCII 字符，非 ASCII 一律丢掉——用户敲什么就只留什么，
            // 不再出现"打进去是网址、显示出来是汉字"的情况。
            onValueChange = { typed ->
                url = typed.filter { ch ->
                    ch.isLetterOrDigit() && ch.code < 128 ||
                        ch in ":/.-_[]?=&%#@+~"
                }.trim()
            },
            label = { Text("连接地址") },
            placeholder = { Text("ws://192.168.1.10:7420") },
            singleLine = true,
            enabled = !connecting,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            label = { Text("配对码") },
            supportingText = if (knownCredential) ({ Text("留空使用已保存的凭据") }) else null,
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !connecting,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(22.dp))

        Button(
            onClick = { onConnect(url, token) },
            enabled = !connecting && url.isNotBlank() && (token.isNotBlank() || knownCredential),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            if (connecting) {
                // P4：连接中不能只剩一个转圈——文字要留着，否则读屏和自动化都读不出它在干什么。
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text("连接中…")
            } else {
                Text("连接")
            }
        }

        if (link is LinkState.Failed) {
            Spacer(Modifier.height(16.dp))
            Text(
                // P3：底层异常（failed to connect to /192.168.1.6 … from /10.100.2.x）
                // 直接抛到界面上既看不懂、又泄露内网地址。这里只给结论和处理办法，
                // 原始原因留在日志里给开发看。
                text = friendlyFailure(link.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Start),
            )
        }

        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("返回工作台") }
        TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("断开连接（保留配对）") }
        if (computers.isNotEmpty()) {
            TextButton(
                onClick = { confirmForgetCurrent = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("解绑当前电脑") }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = "连接信息保存在手机上。下次可直接选择已配对的电脑。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (confirmForgetCurrent) {
        ConfirmDialog(
            title = "解绑当前电脑？",
            body = "这台手机上的凭据会被删除，再连接需要从电脑重新取得配对码。",
            confirmLabel = "解绑",
            onConfirm = { confirmForgetCurrent = false; onForget() },
            onDismiss = { confirmForgetCurrent = false },
        )
    }
}

/** One line of the computer list: who it is, how it is reached, and two actions. */
@Composable
private fun ComputerRow(
    computer: PairedComputer,
    active: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onForget: () -> Unit,
) {
    // Asked, not assumed. Unpairing throws away the credential this phone was given, and getting
    // it back means fetching a fresh code from the computer — a stray tap next to 切换 is not
    // consent, and this app already asks before killing a process.
    var confirmForget by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(
                width = 1.dp,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = computer.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = buildString {
                    append(if (computer.relay) "中转" else "直连")
                    append(" · ")
                    append(computer.url)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (active) {
            Text(
                text = if (busy) "连接中" else "当前",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(onClick = onSelect) { Text("切换") }
        }
        TextButton(onClick = { confirmForget = true }) { Text("解绑") }
    }

    if (confirmForget) {
        ConfirmDialog(
            title = "解绑这台电脑？",
            body = "将删除这台电脑的连接凭据。再次连接需要重新配对。",
            confirmLabel = "解绑",
            onConfirm = {
                confirmForget = false
                onForget()
            },
            onDismiss = { confirmForget = false },
        )
    }
}

/**
 * 把底层连接异常翻译成一句人话，并去掉内网地址。
 *
 * P3：之前界面直接把 `failed to connect to /192.168.1.6 (port 7420) from /10.100.2.x`
 * 抛给用户——中文界面里夹一段英文技术异常，还顺带把手机内网 IP 显示出来了。
 * 原始原因留在 logcat 里给开发排查，这里只给结论和下一步该做什么。
 */
private fun friendlyFailure(reason: String): String {
    val lower = reason.lowercase()
    return when {
        "failed to connect" in lower || "connection refused" in lower ||
            "timeout" in lower || "unreachable" in lower ->
            "连不上这台电脑。请确认：电脑端的 TermDesk 正在运行、地址和端口没写错、手机与电脑在同一网络。"
        "unauthorized" in lower || "401" in lower || "token" in lower ->
            "配对码不对或已失效。请回到电脑的配对页重新取一次配对码。"
        else ->
            "连接失败，请稍后再试。"
    }
}

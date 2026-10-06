package dev.termdesk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf(initialToken) }
    val connecting = link is LinkState.Connecting

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
            text = "每台电脑各配一次；换电脑不用重新配对",
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
            text = if (computers.isEmpty()) "用电脑上 /pair 页面的二维码配对" else "再加一台电脑",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim() },
            label = { Text("节点地址") },
            placeholder = { Text("wss://你的节点域名") },
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
            label = { Text("一次性配对码") },
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
            enabled = !connecting && url.isNotBlank() && token.isNotBlank(),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            if (connecting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("连接")
            }
        }

        if (link is LinkState.Failed) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = link.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Start),
            )
        }

        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("返回工作台 / 离线查看") }
        TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("暂时断开（保留绑定）") }
        if (computers.isNotEmpty()) {
            TextButton(
                onClick = onForget,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("解绑当前电脑") }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = "扫码配对时不需要手填地址。凭据加密保存在这台手机上，每台电脑一份；解绑其中一台不影响其他电脑。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        TextButton(onClick = onForget) { Text("解绑") }
    }
}

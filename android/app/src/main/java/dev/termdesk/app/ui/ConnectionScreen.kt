package dev.termdesk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/**
 * Pairing screen. Deliberately plain: enter the agent address and the token
 * printed by `node src/server.js --show-token` on the PC.
 */
@Composable
fun ConnectionScreen(
    link: LinkState,
    initialUrl: String,
    initialToken: String,
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
            text = "一次绑定设备，之后自动连接 VPS 节点",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

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
            label = { Text("一次性绑定码 / 已保存的设备凭据") },
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
        TextButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) { Text("忘记此设备绑定") }
        Spacer(Modifier.height(28.dp))
        Text(
            text = "仅首次绑定需要节点地址和短时绑定码。设备凭据加密保存；断开连接不会取消绑定。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

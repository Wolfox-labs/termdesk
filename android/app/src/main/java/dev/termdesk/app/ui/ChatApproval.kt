package dev.termdesk.app.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.ChatApproval
import kotlinx.coroutines.delay


/**
 * The engine is blocked until this is answered.
 *
 * The countdown is real, not decorative: the agent gives the question a deadline
 * and settles it on its own when it passes, which is what makes a remote
 * approval safe — a phone in a pocket must not freeze a conversation forever.
 * Dismissing the dialog leaves the question pending, so it comes back instead of
 * silently deciding anything.
 */
@Composable
internal fun ApprovalDialog(
    approval: ChatApproval,
    onAnswer: (String, String) -> Unit,
    /** Hide the dialog without answering. The question stays pending; the bar behind it is the way back. */
    onDefer: () -> Unit,
) {
    var now by remember(approval.requestId) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(approval.requestId) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val seconds = (approval.remainingMs(now) / 1000).toInt()

    AlertDialog(
        // Back / outside tap now do what the copy has always promised. The question is not
        // decided by this — it stays pending on the PC — so the worst case is that the person
        // comes back to it, which is exactly what "稍后再说" says.
        onDismissRequest = onDefer,
        title = { Text(approval.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (approval.detail.isNotBlank()) {
                    Text(
                        approval.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    )
                }
                // Fixed-shape lines on purpose. A countdown whose text re-wraps
                // every second is a dialog whose buttons move while the user is
                // reaching for them, and a mis-tap here decides whether the
                // kernel may run something. mm:ss never grows past the wrap.
                if (seconds > 0) {
                    Text(
                        "${approvalEngineLabel(approval.engine)} · 剩余 ${countdownText(seconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "超时按「${approval.fallbackText}」处理；关掉这个框只是稍后再说",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                approval.options.forEach { option ->
                    Button(
                        onClick = { onAnswer(approval.requestId, option.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when (option.style) {
                                "danger" -> MaterialTheme.colorScheme.error
                                "primary" -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surface
                            },
                            contentColor = when (option.style) {
                                "danger" -> Color.White
                                "primary" -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        ),
                    ) {
                        // 引擎给的选项 label 可能是空的，于是按钮上什么都没有——真机上就是
                        // 两个没有文字的可点区域，读屏也读不出来。给个兜底文案。
                        Text(
                            option.label.ifBlank {
                                when (option.style) {
                                    "danger" -> "拒绝"
                                    "primary" -> "允许"
                                    else -> "选择"
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        // A visible way out, so the sentence above it is not a promise about a gesture nobody
        // can guess.
        dismissButton = {
            TextButton(onClick = onDefer) { Text("稍后处理") }
        },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

/** mm:ss, so the line never changes width enough to move the buttons. */
internal fun countdownText(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}

/** The kernel's name as the settings list spells it. */
internal fun approvalEngineLabel(engine: String?): String = when (engine) {
    "codex" -> "Codex"
    "dsh" -> "DeepSeek Harness"
    "opencode" -> "OpenCode"
    "mimo" -> "MiMo Code"
    else -> engine?.takeIf { it.isNotBlank() } ?: "内核"
}

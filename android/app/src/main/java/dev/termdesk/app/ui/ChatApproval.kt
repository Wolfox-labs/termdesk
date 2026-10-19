package dev.termdesk.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.termdesk.app.data.ChatDraft
import dev.termdesk.app.data.ChatDraftStore
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import dev.termdesk.app.data.ChatApproval
import dev.termdesk.app.data.ChatEvent
import dev.termdesk.app.data.ChatCommands
import dev.termdesk.app.data.ChatModels
import dev.termdesk.app.data.SessionGroups
import dev.termdesk.app.data.UploadedFile
import dev.termdesk.app.data.CodexConfig
import dev.termdesk.app.data.KernelInfo
import dev.termdesk.app.data.KernelRun
import dev.termdesk.app.data.ChatInfo
import dev.termdesk.app.data.ChatTerminal
import dev.termdesk.app.data.TerminalView
import dev.termdesk.app.data.SessionDetail
import dev.termdesk.app.data.SessionInfo
import dev.termdesk.app.data.SessionSort
import dev.termdesk.app.data.WorkspaceInfo
import dev.termdesk.app.ui.theme.Semantic
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                        Text(option.label)
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


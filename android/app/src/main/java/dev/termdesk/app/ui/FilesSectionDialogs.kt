package dev.termdesk.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/** Small text prompt shared by rename and create. */
@Composable
fun NameInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val clean = value.trim()
    val nameError = when {
        clean.isEmpty() -> null
        clean == "." || clean == ".." -> "名称不能是 . 或 .."
        clean.any { it == '/' || it == '\\' || it.code < 32 } -> "名称不能包含路径分隔符或控制字符"
        else -> null
    }
    val valid = clean.isNotEmpty() && nameError == null

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("名称") },
                isError = nameError != null,
                supportingText = { nameError?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (valid) onConfirm(clean) },
                enabled = valid,
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

/** Type an absolute path to jump straight there. */
@Composable
internal fun PathEditDialog(
    draft: String,
    onDraftChange: (String) -> Unit,
    onGo: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("前往路径") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    label = { Text("绝对路径", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "输入当前设备上的目录路径，需位于允许访问的目录内。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = draft.isNotBlank(), onClick = onGo) { Text("前往") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

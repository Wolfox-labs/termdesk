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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.CodexConfig
import dev.termdesk.app.data.CodexProviderTemplate
import dev.termdesk.app.ui.theme.Semantic
import dev.termdesk.app.ui.theme.ThemeMode

/**
 * Codex provider settings.
 *
 * This screen edits the machine's real ~/.codex configuration, so it is built
 * to be explicit about consequences: the exact target path is shown, applying
 * requires confirmation, and a restore path is always visible.
 */
@Composable
fun CodexSettingsSection(
    config: CodexConfig?,
    templates: List<CodexProviderTemplate>,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onLoad: () -> Unit,
    onApply: (String, String, String?, String?, Long?) -> Unit,
    onRestore: (String?) -> Unit,
) {
    // Load once when the screen is first shown.
    LaunchedEffect(Unit) { onLoad() }

    var providerId by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var effort by remember { mutableStateOf("") }
    var contextWindow by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<String?>(null) }

    // Adopt the template defaults once they arrive.
    LaunchedEffect(templates, config) {
        if (providerId.isEmpty() && templates.isNotEmpty()) {
            val t = templates.first()
            providerId = t.id
            model = config?.model?.takeIf { it in t.models } ?: t.models.firstOrNull().orEmpty()
            effort = config?.reasoningEffort?.takeIf { it in t.reasoningLevels } ?: t.defaultReasoning
            contextWindow = (config?.models?.firstOrNull()?.contextWindow ?: t.contextWindow).toString()
        }
    }

    val template = templates.find { it.id == providerId }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // --- appearance (always available, independent of Codex state) ---
        Card {
            Text("外观", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "One Monokai，深浅两套。深色为经典编辑器底色，浅色是暖纸感。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeMode.entries.forEach { mode ->
                    ChoiceChip(
                        label = when (mode) {
                            ThemeMode.Dark -> "深色"
                            ThemeMode.Light -> "浅色"
                            ThemeMode.System -> "跟随系统"
                        },
                        selected = mode == themeMode,
                        onClick = { onThemeModeChange(mode) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // A live swatch row so the choice is visible before leaving the screen.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    MaterialTheme.colorScheme.background,
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.primary,
                    Semantic.current.success,
                    Semantic.current.warning,
                    MaterialTheme.colorScheme.error,
                ).forEach { c ->
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(c),
                    )
                }
            }
        }

        if (config == null) {
            Text(
                "正在读取 Codex 配置…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        if (!config.exists) {
            Text(
                "未找到 ${config.configPath}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "请先在电脑上运行一次 Codex 桌面版或 CLI，再回到这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        // --- current state ---
        Card {
            Text("当前配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            InfoRow("模型", config.model ?: "—")
            InfoRow("服务商", config.modelProvider ?: "—")
            InfoRow("推理强度", config.reasoningEffort ?: "—")
            config.models.firstOrNull { it.slug == config.model }?.let { m ->
                InfoRow("上下文", m.contextWindow?.let { formatTokens(it) } ?: "—")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                config.configPath,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // --- model catalog ---
        if (config.models.isNotEmpty()) {
            Card {
                Text("已声明的模型", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                config.models.forEach { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.slug, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                buildString {
                                    append(m.contextWindow?.let { formatTokens(it) } ?: "—")
                                    if (m.vision) append(" · 支持图片")
                                    if (m.reasoningLevels.isNotEmpty()) {
                                        append(" · ${m.reasoningLevels.joinToString("/")}")
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        config.modelsError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        // --- edit form ---
        Card {
            Text("修改配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "会先备份现有配置，只改动下列字段，其余内容原样保留。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // provider
            FieldLabel("服务商")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                templates.forEach { t ->
                    ChoiceChip(
                        label = t.name,
                        selected = t.id == providerId,
                        onClick = {
                            providerId = t.id
                            model = t.models.firstOrNull().orEmpty()
                            effort = t.defaultReasoning
                            contextWindow = t.contextWindow.toString()
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            FieldLabel("模型")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (template?.models ?: emptyList()).forEach { m ->
                    ChoiceChip(label = m, selected = m == model, onClick = { model = m })
                }
            }

            Spacer(Modifier.height(12.dp))
            FieldLabel("推理强度")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (template?.reasoningLevels ?: emptyList()).forEach { e ->
                    ChoiceChip(label = e, selected = e == effort, onClick = { effort = e })
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = contextWindow,
                onValueChange = { contextWindow = it.filter { c -> c.isDigit() } },
                label = { Text("上下文窗口 (tokens)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                supportingText = {
                    val n = contextWindow.toLongOrNull()
                    Text(
                        if (n != null) formatTokens(n) else "留空则沿用服务商默认值",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                visualTransformation = if (showKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            imageVector = if (showKey) Icons.Outlined.VisibilityOff
                            else Icons.Outlined.Visibility,
                            contentDescription = if (showKey) "隐藏" else "显示",
                            modifier = Modifier.size(19.dp),
                        )
                    }
                },
                supportingText = {
                    val existing = config.providers.find { it.id == providerId }?.hasToken == true
                    Text(
                        if (existing && apiKey.isBlank()) "已保存密钥，留空则保持不变"
                        else "以 ${template?.keyPrefix ?: "sk-"} 开头，仅写入这台电脑",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { confirmApply = true },
                enabled = providerId.isNotBlank() && model.isNotBlank(),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("应用到 Codex")
            }
        }

        // --- backups ---
        if (config.backups.isNotEmpty()) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "配置备份",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${config.backups.size} 份",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                config.backups.take(5).forEach { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            name.removePrefix("config-").removeSuffix(".toml"),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { confirmRestore = name }) {
                            Icon(
                                Icons.Outlined.Restore,
                                contentDescription = "恢复此备份",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmApply) {
        ConfirmDialog(
            title = "应用到 Codex",
            body = buildString {
                append("将把 $model 写入 Codex 配置。\n\n")
                append("目标文件：${config?.configPath}\n\n")
                append("写入前会自动备份现有配置；其余设置（MCP、项目信任级别等）保持不变。")
            },
            confirmLabel = "确认写入",
            onConfirm = {
                onApply(providerId, model, apiKey.ifBlank { null }, effort, contextWindow.toLongOrNull())
                apiKey = ""
                confirmApply = false
            },
            onDismiss = { confirmApply = false },
        )
    }

    confirmRestore?.let { name ->
        ConfirmDialog(
            title = "恢复备份",
            body = "将用 ${name.removePrefix("config-").removeSuffix(".toml")} 覆盖当前配置。\n\n当前配置也会先被备份，可以再恢复回来。",
            confirmLabel = "恢复",
            onConfirm = {
                onRestore(name)
                confirmRestore = null
            },
            onDismiss = { confirmRestore = null },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatTokens(n: Long): String = when {
    n >= 1_048_576 -> "${n / 1_048_576}M tokens"
    n >= 1024 -> "${n / 1024}K tokens"
    else -> "$n tokens"
}

package dev.termdesk.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
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
import dev.termdesk.app.data.EngineInfo
import dev.termdesk.app.ui.theme.Semantic
import dev.termdesk.app.ui.theme.ThemeMode

/**
 * Settings: kernel (target), appearance, and the machine's real Codex config.
 *
 * Settings is a list of controls, not an essay: every row states one thing and
 * does one thing, long content is folded behind the row that owns it, and the
 * only prose is a single line saying what the kernel choice means.
 */
@Composable
fun SettingsSection(
    config: CodexConfig?,
    templates: List<CodexProviderTemplate>,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    engines: List<EngineInfo>,
    defaultEngine: String?,
    onSetDefaultEngine: (String?) -> Unit,
    onRefreshEngines: () -> Unit,
    onLoad: () -> Unit,
    onApply: (String, String, String?, String?, Long?) -> Unit,
    onRestore: (String?) -> Unit,
) {
    LaunchedEffect(Unit) { onLoad() }

    var kernelOpen by remember { mutableStateOf(false) }
    var modelsOpen by remember { mutableStateOf(false) }
    var providerId by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var effort by remember { mutableStateOf("") }
    var contextWindow by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<String?>(null) }

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
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // --- kernel / target -------------------------------------------------
        Card {
            SettingRow(
                label = "内核",
                value = when {
                    !kernelOpen && defaultEngine != null ->
                        "远程 · ${engines.find { it.id == defaultEngine }?.displayName ?: defaultEngine}"
                    kernelOpen -> "远程 · 这台电脑"
                    else -> "远程 · 这台电脑"
                },
                expanded = kernelOpen,
                onClick = { kernelOpen = !kernelOpen },
            )
            AnimatedVisibility(visible = kernelOpen) {
                Column(Modifier.padding(top = 6.dp)) {
                    engines.sortedWith(
                        compareBy(
                            { mapOf("native" to 0, "acp" to 1, "shim" to 2)[it.tier] ?: 3 },
                            { if (it.available) 0 else 1 },
                            { it.displayName },
                        ),
                    ).forEach { engine ->
                        EngineRow(
                            engine = engine,
                            isDefault = engine.id == defaultEngine,
                            onSelect = { onSetDefaultEngine(engine.id) },
                        )
                        Spacer(Modifier.height(5.dp))
                    }
                    if (engines.isEmpty()) {
                        Text(
                            "正在检测…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "本机 · 手机沙盒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "未接入",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "远程内核的引擎跑在电脑上；新对话默认用选中的那个。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 6.dp)) {
                        Text(
                            "重新检测",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onRefreshEngines)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        // --- appearance ------------------------------------------------------
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("外观", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Semantic.current.success),
                    )
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Semantic.current.warning),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
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
            Card {
                Text("Codex 配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "电脑上还没有 ${config.configPath}，先运行一次 Codex 再回来。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return@Column
        }

        // --- current Codex state --------------------------------------------
        Card {
            Text("Codex", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            InfoRow("模型", config.model ?: "—")
            InfoRow("服务商", config.modelProvider ?: "—")
            InfoRow("推理强度", config.reasoningEffort ?: "—")
            config.models.firstOrNull { it.slug == config.model }?.let { m ->
                InfoRow("上下文", m.contextWindow?.let { formatTokens(it) } ?: "—")
            }
            Spacer(Modifier.height(6.dp))
            SettingRow(
                label = "已声明的模型",
                value = "${config.models.size}",
                expanded = modelsOpen,
                onClick = { modelsOpen = !modelsOpen },
            )
            AnimatedVisibility(visible = modelsOpen) {
                Column(Modifier.padding(top = 4.dp)) {
                    config.models.forEach { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                m.slug,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                buildString {
                                    append(m.contextWindow?.let { formatTokens(it) } ?: "—")
                                    if (m.reasoningLevels.isNotEmpty()) {
                                        append(" · ${m.reasoningLevels.joinToString("/")}")
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                config.configPath,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        config.modelsError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        // --- edit ------------------------------------------------------------
        Card {
            Text("修改", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            FieldLabel("服务商")
            ChipStrip(
                items = templates.map { it.id to it.name },
                selectedId = providerId,
                onSelect = {
                    providerId = it
                    val t = templates.find { t2 -> t2.id == it }
                    model = t?.models?.firstOrNull().orEmpty()
                    effort = t?.defaultReasoning.orEmpty()
                    contextWindow = t?.contextWindow?.toString().orEmpty()
                },
            )

            Spacer(Modifier.height(10.dp))
            FieldLabel("模型")
            ChipStrip(
                items = (template?.models ?: emptyList()).map { it to it },
                selectedId = model,
                onSelect = { model = it },
            )

            Spacer(Modifier.height(10.dp))
            FieldLabel("推理强度")
            ChipStrip(
                items = (template?.reasoningLevels ?: emptyList()).map { it to it },
                selectedId = effort,
                onSelect = { effort = it },
            )

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = contextWindow,
                onValueChange = { contextWindow = it.filter { c -> c.isDigit() } },
                label = { Text("上下文窗口", style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                supportingText = {
                    val n = contextWindow.toLongOrNull()
                    Text(
                        if (n != null) formatTokens(n) else "留空沿用默认",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key", style = MaterialTheme.typography.labelSmall) },
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
                        if (existing && apiKey.isBlank()) "已保存，留空保持不变" else "仅写入这台电脑",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { confirmApply = true },
                enabled = providerId.isNotBlank() && model.isNotBlank(),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("应用")
            }
        }

        if (config.backups.isNotEmpty()) {
            Card {
                Text("备份", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
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
            body = "将把 $model 写入 ${config?.configPath}。\n\n写入前自动备份，其余设置保持不变。",
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
            body = "将用 ${name.removePrefix("config-").removeSuffix(".toml")} 覆盖当前配置。\n\n当前配置也会先被备份。",
            confirmLabel = "恢复",
            onConfirm = {
                onRestore(name)
                confirmRestore = null
            },
            onDismiss = { confirmRestore = null },
        )
    }
}

/** One tappable row: name on the left, current value and a chevron on the right. */
@Composable
private fun SettingRow(
    label: String,
    value: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp
            else Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** One engine the PC reported, with the reason it is or is not pickable. */
@Composable
private fun EngineRow(
    engine: EngineInfo,
    isDefault: Boolean,
    onSelect: () -> Unit,
) {
    val status = when {
        !engine.available -> "未安装"
        engine.tier == "acp" -> "ACP·未接入"
        engine.tier == "shim" -> "CLI·需 shim"
        else -> "可用"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isDefault) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .then(if (engine.selectable) Modifier.clickable(onClick = onSelect) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            engine.displayName,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isDefault) FontWeight.SemiBold else FontWeight.Normal,
            color = if (engine.selectable) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (isDefault) "默认" else status,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                isDefault -> MaterialTheme.colorScheme.primary
                engine.selectable -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.error
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(5.dp))
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
private fun ChipStrip(
    items: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { (id, label) ->
            val selected = id == selectedId
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                    )
                    .clickable { onSelect(id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
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

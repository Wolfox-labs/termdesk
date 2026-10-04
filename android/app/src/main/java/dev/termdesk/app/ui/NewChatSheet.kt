package dev.termdesk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.CodexConfig
import dev.termdesk.app.data.EngineInfo
import dev.termdesk.app.data.WorkspaceInfo

/**
 * New agent conversation picker.
 *
 * Creating a chat is an explicit choice of three things: kernel (engine),
 * model (follows the kernel), and working directory. Nothing here is guessed
 * on the user's behalf except pre-filling the cwd the caller suggested — the
 * kernel must be tapped, or the confirm button stays disabled.
 *
 * This sheet is the only path to [AppViewModel.createChat]. UI entry points
 * (ChatSection's "新建对话" etc.) must open this sheet via `onCreateChat(suggestedCwd)`
 * rather than creating a chat directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatSheet(
    engines: List<EngineInfo>,
    workspaces: List<WorkspaceInfo>,
    codexConfig: CodexConfig?,
    defaultEngine: String? = null,
    suggestedCwd: String?,
    defaultCwd: String,
    onCreateChat: (
        cwd: String?,
        engine: String,
        provider: String?,
        model: String?,
        title: String?,
    ) -> Unit,
    onCreateDirectory: (parentPath: String, name: String) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
) {
    val kernels = remember(engines) { kernelChoices(engines) }

    // The kernel is the target the phone drives, and Settings holds the user's
    // default. So the sheet pre-selects it (falling back to the first wired
    // adapter) — the user only touches it when they mean to switch targets.
    val preferredEngine = defaultEngine?.takeIf { id -> kernels.any { it.selectable && it.id == id } }
        ?: kernels.firstOrNull { it.selectable }?.id
    var engineId by remember(kernels, preferredEngine) { mutableStateOf<String?>(preferredEngine) }

    // Model/provider follow the kernel. Codex can pick from its catalog;
    // dsh routes by itself and only shows a default-route caption.
    var providerId by remember { mutableStateOf(codexConfig?.modelProvider) }
    var modelSlug by remember { mutableStateOf(codexConfig?.model) }

    val cwdOptions = remember(suggestedCwd, defaultCwd, workspaces) {
        buildList {
            if (!suggestedCwd.isNullOrBlank()) add(suggestedCwd)
            if (defaultCwd.isNotBlank()) add(defaultCwd)
            workspaces.forEach { if (it.cwd.isNotBlank()) add(it.cwd) }
        }.distinct()
    }
    var cwdText by remember {
        mutableStateOf(suggestedCwd?.takeIf { it.isNotBlank() } ?: defaultCwd)
    }
    var titleText by remember { mutableStateOf("") }

    // Codex config may arrive after the sheet opens; fill model defaults then.
    LaunchedEffect(codexConfig, engineId) {
        if (engineId == "codex") {
            if (providerId == null) {
                providerId = codexConfig?.modelProvider
                    ?: codexConfig?.providers?.firstOrNull()?.id
            }
            if (modelSlug == null) {
                modelSlug = codexConfig?.model
                    ?: codexConfig?.models?.firstOrNull()?.slug
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        ),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(
                "新建对话",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "选择内核、模型与工作目录后开始。这是统一的 agent 对话，不再区分「会话 / 任务」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(14.dp))
            FieldLabel("内核")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                kernels.forEach { engine ->
                    KernelRow(
                        engine = engine,
                        selected = engine.id == engineId,
                        onClick = {
                            engineId = engine.id
                            // Model follows the kernel: codex reuses its catalog
                            // defaults, dsh keeps provider/model unset (default route).
                            if (engine.id == "codex") {
                                if (providerId == null) providerId = codexConfig?.modelProvider
                                    ?: codexConfig?.providers?.firstOrNull()?.id
                                if (modelSlug == null) modelSlug = codexConfig?.model
                                    ?: codexConfig?.models?.firstOrNull()?.slug
                            } else {
                                providerId = null
                                modelSlug = null
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.size(14.dp))
            FieldLabel("模型")
            when (engineId) {
                "codex" -> {
                    val providers = codexConfig?.providers.orEmpty()
                    val models = codexConfig?.models.orEmpty()
                    if (providers.isNotEmpty()) {
                        ChipRow(
                            items = providers.map { it.id to (it.name ?: it.id) },
                            selectedId = providerId,
                            onSelect = { providerId = it },
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    if (models.isNotEmpty()) {
                        ChipRow(
                            items = models.map { it.slug to it.displayName },
                            selectedId = modelSlug,
                            onSelect = { modelSlug = it },
                        )
                    } else {
                        OutlinedTextField(
                            value = modelSlug.orEmpty(),
                            onValueChange = { modelSlug = it.ifBlank { null } },
                            label = { Text("模型 slug（可留空走 Codex 默认）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = buildString {
                            append("来自 Codex 配置")
                            codexConfig?.model?.let { append(" · 当前默认 $it") }
                            if (codexConfig == null) append("未加载，可留空走内核默认")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                "dsh" -> {
                    Text(
                        "默认路由 · dsh 运行时按自身配置选择 provider / model",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                null -> {
                    Text(
                        "先选择内核，再确认模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text(
                        "跟随内核默认 provider / model。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.size(14.dp))
            FieldLabel("工作目录")
            if (cwdOptions.isNotEmpty()) {
                ChipRow(
                    items = cwdOptions.map { it to it },
                    selectedId = cwdOptions.firstOrNull { it == cwdText },
                    onSelect = { cwdText = it },
                )
                Spacer(Modifier.size(8.dp))
            }
            OutlinedTextField(
                value = cwdText,
                onValueChange = { cwdText = it },
                label = { Text("路径（可手填）") },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )

            // Working directories are derived from sessions on the PC, so a
            // brand-new folder has to be created explicitly (same fs.mkdir as
            // the file browser) before it can host a conversation.
            Spacer(Modifier.size(10.dp))
            var newDirParent by remember(cwdOptions, cwdText) {
                mutableStateOf(
                    cwdOptions.firstOrNull { cwdText.startsWith(it) } ?: cwdOptions.firstOrNull() ?: defaultCwd,
                )
            }
            var newDirName by remember { mutableStateOf("") }
            Text(
                "新建工作目录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(6.dp))
            ChipRow(
                items = cwdOptions.map { it to workspaceShortLabel(it) },
                selectedId = newDirParent,
                onSelect = { newDirParent = it },
            )
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newDirName,
                    onValueChange = { newDirName = it },
                    label = { Text("目录名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = newDirName.isNotBlank() && newDirParent.isNotBlank(),
                    onClick = {
                        val name = newDirName.trim().trimEnd('\\', '/')
                        if (name.isEmpty() || name.contains('\\') || name.contains('/')) return@TextButton
                        onCreateDirectory(newDirParent, name)
                        val joined = joinWorkPath(newDirParent, name)
                        cwdText = joined
                        newDirName = ""
                    },
                ) {
                    Icon(
                        Icons.Outlined.CreateNewFolder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("创建")
                }
            }

            Spacer(Modifier.size(12.dp))
            FieldLabel("标题（可选）")
            OutlinedTextField(
                value = titleText,
                onValueChange = { titleText = it },
                label = { Text("留空则由首条消息生成") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.size(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                val engine = engineId
                val cwdReady = cwdText.isNotBlank()
                Button(
                    onClick = {
                        if (engine == null || !cwdReady) return@Button
                        onCreateChat(
                            cwdText.trim(),
                            engine,
                            providerId?.takeIf { it.isNotBlank() },
                            modelSlug?.takeIf { it.isNotBlank() },
                            titleText.trim().takeIf { it.isNotBlank() },
                        )
                    },
                    enabled = engine != null && cwdReady,
                ) {
                    Text("创建对话")
                }
            }
        }
    }
}

/**
 * Kernels offered in the sheet.
 *
 * The product surface is at least `codex` and `dsh`. When `ai.engines` has not
 * answered yet those two are still shown (unverified) so the user can never
 * land in a picker with nothing to choose.
 */
private fun workspaceShortLabel(path: String): String {
    val trimmed = path.trim().trimEnd('\\', '/')
    val idx = trimmed.lastIndexOfAny(charArrayOf('\\', '/'))
    return if (idx >= 0 && idx < trimmed.length - 1) trimmed.substring(idx + 1) else trimmed.ifBlank { path }
}

private fun joinWorkPath(parent: String, name: String): String {
    val p = parent.trim().trimEnd('\\', '/')
    return when {
        p.isEmpty() -> name
        p.endsWith(':') -> p + "\\" + name
        else -> p + "\\" + name
    }
}

/**
 * Order the PC's discovery for the picker: wired adapters first, then kernels
 * that speak ACP, then CLI-shaped ones — and available before missing.
 *
 * The list comes from the PC, so a kernel that is installed but not integrated
 * yet shows up as such instead of being hidden or silently offered.
 */
private fun kernelChoices(engines: List<EngineInfo>): List<EngineInfo> {
    if (engines.isEmpty()) {
        return listOf("codex" to "Codex", "dsh" to "DeepSeek Harness").map { (id, label) ->
            EngineInfo(id = id, available = true, path = "", multiTurn = true, progress = true, label = label)
        }
    }
    val rank = mapOf("native" to 0, "acp" to 1, "shim" to 2)
    return engines.sortedWith(
        compareBy({ rank[it.tier] ?: 3 }, { if (it.available) 0 else 1 }, { it.displayName }),
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun KernelRow(
    engine: EngineInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val enabled = engine.selectable
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    enabled -> MaterialTheme.colorScheme.surface
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                engine.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = when {
                    selected -> MaterialTheme.colorScheme.primary
                    enabled -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = when (engine.tier) {
                    "acp" -> "ACP"
                    "shim" -> "CLI"
                    else -> "已接入"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
            if (!engine.available) {
                Spacer(Modifier.size(6.dp))
                Text("未安装", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1)
            }
        }
        Spacer(Modifier.size(2.dp))
        Text(
            text = engine.detail.ifBlank {
                buildString {
                    if (engine.multiTurn) append("多轮连续") else append("单轮")
                    if (engine.progress) append(" · 有进度")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChipRow(
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
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                    )
                    .clickable { onSelect(id) }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            )
        }
    }
}

package dev.termdesk.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.CodexConfig
import dev.termdesk.app.data.EngineInfo
import dev.termdesk.app.data.WorkspaceInfo

/**
 * New agent conversation picker.
 *
 * Three decisions matter — kernel, model, directory — and everything else is
 * optional, so the sheet is one short column with a pinned action at the bottom:
 * nothing the user needs is ever below the fold. The kernel list starts folded
 * into a single row (it is long here: every kernel the PC found is listed) and
 * only opens when the user taps it.
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
        effort: String?,
    ) -> Unit,
    onCreateDirectory: (parentPath: String, name: String) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
) {
    val kernels = remember(engines) { kernelChoices(engines) }

    // Pre-selected from Settings; the user only opens this list to switch.
    val preferredEngine = defaultEngine?.takeIf { id -> kernels.any { it.selectable && it.id == id } }
        ?: kernels.firstOrNull { it.selectable }?.id
    var engineId by remember(kernels, preferredEngine) { mutableStateOf<String?>(preferredEngine) }
    var kernelExpanded by remember { mutableStateOf(false) }

    var providerId by remember { mutableStateOf(codexConfig?.modelProvider) }
    var modelSlug by remember { mutableStateOf(codexConfig?.model) }
    var effortSlug by remember { mutableStateOf<String?>(null) }

    // Only real absolute paths are offered. The PC derives workspaces from
    // recorded sessions, and some kernels record their own session name in that
    // field ("dsh", a decoded "Hearts of Iron" title); offering those as working
    // directories would create a chat in a directory that does not exist.
    val cwdOptions = remember(suggestedCwd, defaultCwd, workspaces) {
        buildList {
            if (!suggestedCwd.isNullOrBlank()) add(suggestedCwd)
            if (defaultCwd.isNotBlank()) add(defaultCwd)
            workspaces.forEach { if (it.cwd.isNotBlank()) add(it.cwd) }
        }.filter { looksLikePath(it) }.distinct()
    }
    var cwdText by remember {
        mutableStateOf(suggestedCwd?.takeIf { it.isNotBlank() } ?: defaultCwd)
    }
    var titleText by remember { mutableStateOf("") }
    var newDirOpen by remember { mutableStateOf(false) }

    val selectedEngine = kernels.find { it.id == engineId }
    val models = if (engineId == "codex") codexConfig?.models.orEmpty() else emptyList()
    val levels = models.firstOrNull { it.slug == modelSlug }?.reasoningLevels.orEmpty()

    // Codex config may arrive after the sheet opens; fill model defaults then.
    LaunchedEffect(codexConfig, engineId) {
        if (engineId == "codex") {
            if (providerId == null) {
                providerId = codexConfig?.modelProvider ?: codexConfig?.providers?.firstOrNull()?.id
            }
            if (modelSlug == null) {
                modelSlug = codexConfig?.model ?: codexConfig?.models?.firstOrNull()?.slug
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
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
            Text(
                "新建对话",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                KernelSelector(
                    kernels = kernels,
                    selectedId = engineId,
                    expanded = kernelExpanded,
                    onToggle = { kernelExpanded = !kernelExpanded },
                    onSelect = { engine ->
                        engineId = engine.id
                        kernelExpanded = false
                        effortSlug = null
                        if (engine.id == "codex") {
                            if (providerId == null) {
                                providerId = codexConfig?.modelProvider
                                    ?: codexConfig?.providers?.firstOrNull()?.id
                            }
                            if (modelSlug == null) {
                                modelSlug = codexConfig?.model
                                    ?: codexConfig?.models?.firstOrNull()?.slug
                            }
                        } else {
                            providerId = null
                            modelSlug = null
                        }
                    },
                )

                selectedEngine?.let { engine ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = engine.detail.ifBlank {
                            if (engine.multiTurn) "多轮连续" else "单轮"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (models.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    LabeledChips(
                        label = "模型",
                        items = models.map { it.slug },
                        selected = modelSlug,
                        onSelect = {
                            modelSlug = it
                            effortSlug = null
                        },
                    )
                }
                if (levels.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LabeledChips(
                        label = "思考强度",
                        items = levels,
                        selected = effortSlug ?: levels.firstOrNull { it.equals("high", true) },
                        onSelect = { effortSlug = it },
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "工作目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (cwdOptions.isNotEmpty()) {
                    ChipStrip(
                        items = cwdOptions.map { it to workspaceShortLabel(it) },
                        selectedId = cwdText,
                        onSelect = { cwdText = it },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                OutlinedTextField(
                    value = cwdText,
                    onValueChange = { cwdText = it },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { newDirOpen = !newDirOpen }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.CreateNewFolder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "新建工作目录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (newDirOpen) Icons.Outlined.KeyboardArrowUp
                        else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                AnimatedVisibility(visible = newDirOpen) {
                    NewDirectoryRow(
                        options = cwdOptions,
                        defaultParent = defaultCwd,
                        current = cwdText,
                        onCreate = { parent, name ->
                            onCreateDirectory(parent, name)
                            cwdText = joinWorkPath(parent, name)
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("标题（可留空）", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
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
                            effortSlug?.takeIf { it.isNotBlank() },
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

/** Kernel as one row; the long list only appears when asked for. */
@Composable
private fun KernelSelector(
    kernels: List<EngineInfo>,
    selectedId: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (EngineInfo) -> Unit,
) {
    val selected = kernels.find { it.id == selectedId }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("内核", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(76.dp))
            Text(
                text = selected?.displayName ?: "未选择",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (selected != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp
                else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "收起内核列表" else "展开内核列表",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                kernels.forEach { engine ->
                    val isSelected = engine.id == selectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .then(
                                if (engine.selectable) Modifier.clickable { onSelect(engine) }
                                else Modifier,
                            )
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            engine.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (engine.selectable) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (engine.selectable) "可用" else "暂不可用",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (engine.selectable) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun NewDirectoryRow(
    options: List<String>,
    defaultParent: String,
    current: String,
    onCreate: (String, String) -> Unit,
) {
    var parent by remember(options, current) {
        mutableStateOf(options.firstOrNull { current.startsWith(it) } ?: options.firstOrNull() ?: defaultParent)
    }
    var name by remember { mutableStateOf("") }
    Column(Modifier.padding(top = 4.dp)) {
        if (options.isNotEmpty()) {
            ChipStrip(
                items = options.map { it to workspaceShortLabel(it) },
                selectedId = parent,
                onSelect = { parent = it },
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("目录名", style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                enabled = name.isNotBlank() && parent.isNotBlank(),
                onClick = {
                    val clean = name.trim().trimEnd('\\', '/')
                    if (clean.isEmpty() || clean.contains('\\') || clean.contains('/')) return@TextButton
                    onCreate(parent, clean)
                    name = ""
                },
            ) { Text("创建") }
        }
    }
}

@Composable
private fun LabeledChips(
    label: String,
    items: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        ChipStrip(
            items = items.map { it to it },
            selectedId = selected,
            onSelect = onSelect,
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
        modifier = Modifier.horizontalScroll(rememberScrollState()),
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

/** A Windows drive path or a POSIX absolute path — not a session name. */
private fun looksLikePath(value: String): Boolean {
    val v = value.trim()
    if (v.length < 2) return false
    return Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(v) || v.startsWith("/") || v.startsWith("\\\\")
}

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

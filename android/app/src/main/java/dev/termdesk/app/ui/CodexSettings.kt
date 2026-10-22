package dev.termdesk.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.CodexApplyRequest
import dev.termdesk.app.data.CodexConfig
import dev.termdesk.app.data.CodexProviderTemplate
import dev.termdesk.app.data.CodexTargetState
import dev.termdesk.app.data.PairedComputer
import dev.termdesk.app.data.KernelInfo
import dev.termdesk.app.data.LocalKernelState
import dev.termdesk.app.data.LocalKernelStage
import dev.termdesk.app.data.Storage
import dev.termdesk.app.data.StorageEntry
import dev.termdesk.app.data.StorageUse
import dev.termdesk.app.ui.theme.Palette
import dev.termdesk.app.ui.theme.Semantic
import dev.termdesk.app.ui.theme.ThemeMode

@Composable
fun SettingsSection(
    codex: CodexTargetState,
    targets: List<PairedComputer>,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    layout: UiLayout,
    onLayoutChange: (UiLayout) -> Unit,
    palette: Palette,
    onPaletteChange: (Palette) -> Unit,
    engines: List<KernelInfo>,
    defaultEngine: String?,
    onSetDefaultEngine: (String?) -> Unit,
    kernelTarget: String,
    onSetKernelTarget: (String) -> Unit,
    onRefreshEngines: () -> Unit,
    storage: StorageUse?,
    onLoadStorage: () -> Unit,
    onClearStorage: (StorageEntry) -> Unit,
    localKernel: LocalKernelState,
    onLoadLocalKernel: () -> Unit,
    onInstallLocalKernel: () -> Unit,
    onRemoveLocalKernel: () -> Unit,
    onLoad: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onSelectTarget: (String) -> Unit,
    onApply: (CodexApplyRequest) -> Unit,
    onRestore: (String?) -> Unit,
) {
    val config = codex.config
    val templates = codex.templates

    LaunchedEffect(Unit) {
        onLoadStorage()
        onLoadLocalKernel()
    }

    var kernelOpen by remember { mutableStateOf(false) }
    var modelsOpen by remember { mutableStateOf(false) }
    var providerId by remember { mutableStateOf("") }
    var providerKey by remember { mutableStateOf("") }
    var providerName by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var wireApi by remember { mutableStateOf("responses") }
    var modelSlugs by remember { mutableStateOf("") }
    var effortLevels by remember { mutableStateOf("low, high, max") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var effort by remember { mutableStateOf("") }
    var contextWindow by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var clearKey by remember { mutableStateOf(false) }
    var confirmApply by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<String?>(null) }

    /**
     * Settings is a list of destinations, not a wall of controls: each row opens
     * one page, and back walks up one level (page -> settings root -> out of
     * Settings) instead of leaving the whole section from wherever you stand.
     */
    var page by remember { mutableStateOf<String?>(null) }
    val pageScroll = remember(page) { ScrollState(0) }
    BackHandler(enabled = page != null) { page = null }

    // The Codex page owns a short-lived connection to the computer it edits, so
    // this can be opened without moving the app's working target.
    LaunchedEffect(page) {
        if (page == "codex") onOpen() else onClose()
    }
    DisposableEffect(Unit) {
        onDispose { onClose() }
    }

    var lastTarget by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(templates, config, codex.targetId) {
        val changed = lastTarget != codex.targetId
        lastTarget = codex.targetId

        // What is on disk wins over a template when it is a provider this build
        // does not know: that is the "edit the real current value" case.
        val c = config
        val onDisk = c?.modelProvider
        if (c != null && onDisk != null && templates.none { it.id == onDisk }) {
            val provider = c.providers.find { it.id == onDisk }
            providerId = "custom"
            providerKey = onDisk
            providerName = provider?.name ?: onDisk
            baseUrl = provider?.baseUrl.orEmpty()
            wireApi = provider?.wireApi ?: "responses"
            val models = c.models
            modelSlugs = models.joinToString(", ") { it.slug }.ifBlank { c.model.orEmpty() }
            model = c.model ?: models.firstOrNull()?.slug.orEmpty()
            val levels = models.firstOrNull { it.slug == model }?.reasoningLevels.orEmpty()
                .ifEmpty { listOf("low", "medium", "high") }
            effortLevels = levels.joinToString(", ")
            effort = c.reasoningEffort ?: levels.first()
            contextWindow = (models.firstOrNull { it.slug == model }?.contextWindow ?: 200000).toString()
            return@LaunchedEffect
        }

        if (!changed && providerId.isNotBlank()) return@LaunchedEffect
        val t = templates.firstOrNull() ?: return@LaunchedEffect
        providerId = t.id
        providerKey = t.id
        providerName = t.name
        baseUrl = t.baseUrl
        wireApi = t.wireApi
        modelSlugs = t.models.joinToString(", ")
        effortLevels = t.reasoningLevels.joinToString(", ")
        model = config?.model?.takeIf { it in t.models } ?: t.models.firstOrNull().orEmpty()
        effort = config?.reasoningEffort?.takeIf { it in t.reasoningLevels } ?: t.defaultReasoning
        contextWindow = (config?.models?.firstOrNull { it.slug == model }?.contextWindow ?: t.contextWindow).toString()
    }

    val template = templates.find { it.id == providerId }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (page != null) {
            SubPageHeader(
                title = when (page) {
                    "kernel" -> "运行设备与助手"
                    "appearance" -> "外观"
                    "storage" -> "存储"
                    else -> "电脑 Agent 配置"
                },
                onBack = { page = null },
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(pageScroll).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        val kernelSummary = if (kernelTarget == "local") "这台手机"
        else "远程电脑 · " + (defaultEngine?.let { id -> engines.find { it.id == id }?.displayName ?: id } ?: "未选择助手")
        val themeLabel = when (themeMode) {
            ThemeMode.Dark -> "深色"
            ThemeMode.Light -> "浅色"
            ThemeMode.System -> "跟随系统"
        }
        val storageLabel = storage?.let { (if (it.truncated) "≥ " else "") + Storage.format(it.totalBytes) } ?: "—"
        if (page == null) {
            SettingsRootRow("运行设备与助手", kernelSummary) { page = "kernel" }
            SettingsRootRow("外观", "$themeLabel · ${palette.label}") { page = "appearance" }
            SettingsRootRow("存储", storageLabel) { page = "storage" }
            // Hidden until phone Agent settings have their own contract. The
            // existing Codex editor writes a computer's config and is not that feature.
            Spacer(Modifier.height(12.dp))
            Card {
                val context = androidx.compose.ui.platform.LocalContext.current
                val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
                Text("TermDesk", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("$version · 演示版", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        when (page) {
            null -> Unit
            "kernel" -> {
                KernelTargetRow(kernelTarget, onSetKernelTarget)
                if (kernelTarget == "local") {
                    LocalKernelCard(localKernel, onInstallLocalKernel, onRemoveLocalKernel)
                } else {
        // --- kernel / target -------------------------------------------------
        Card {
            SettingRow(
                label = "助手",
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
                }
                if (kernelTarget != "local") LocalKernelCard(localKernel, onInstallLocalKernel, onRemoveLocalKernel)
            }
            "appearance" -> {
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

            // 配色与布局是两条独立的维度：想「经典布局 + 数媒配色」也成立，所以不合成四选一。
            Spacer(Modifier.height(12.dp))
            Text(
                text = "配色",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Palette.entries.forEach { option ->
                    ChoiceChip(
                        label = option.label,
                        selected = option == palette,
                        onClick = { onPaletteChange(option) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = palette.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = "布局",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UiLayout.entries.forEach { option ->
                    ChoiceChip(
                        label = option.label,
                        selected = option == layout,
                        onClick = { onLayoutChange(option) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "只影响侧边面板从哪一侧滑进来；页面结构不变。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
            }
            "storage" -> {
        // --- on-phone storage -------------------------------------------------
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("存储", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    storage?.let { (if (it.truncated) "≥ " else "") + Storage.format(it.totalBytes) } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            val entries = storage?.entries.orEmpty()
            if (storage?.truncated == true) {
                Text(
                    "统计超时，实际占用可能更大",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (entries.isEmpty()) {
                Text(
                    "正在统计…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.label, style = MaterialTheme.typography.bodySmall)
                        Text(
                            entry.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Storage.format(entry.bytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    if (entry.removable && entry.bytes > 0) {
                        TextButton(onClick = { onClearStorage(entry) }) {
                            Text("清理", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            }
        }
            }
            else -> {
        Card {
            Text("目标电脑", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            InfoRow("电脑", codex.targetName.ifBlank { "未选择" })
            InfoRow("绑定 ID", codex.targetId?.take(8)?.let { "$it…" } ?: "—")
            if (codex.targetUrl.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "地址",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        codex.targetUrl,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            InfoRow(
                "状态",
                when {
                    codex.loading -> "读取中…"
                    codex.online == true -> "已连接"
                    codex.error != null -> "未连接"
                    else -> "未连接"
                },
            )
            InfoRow("配置作用域", "这台电脑的 Codex 配置")
            InfoRow("助手", "Codex")
            Text(
                "保存后对新会话生效；正在运行的会话不受影响。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (targets.size > 1) {
                Spacer(Modifier.height(8.dp))
                FieldLabel("选择电脑")
                ChipStrip(
                    items = targets.map { it.id to it.name },
                    selectedId = codex.targetId ?: "",
                    onSelect = onSelectTarget,
                )
            }
            if (kernelTarget == "local") {
                Spacer(Modifier.height(6.dp))
                Text(
                    "当前工作位置是本机手机；这里配置的仍是所选电脑，不会写入手机沙盒。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            codex.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            codex.result?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (targets.isEmpty()) {
            Card {
                Text("还没有配对的电脑", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "先在“设备管理”里配对一台电脑，再回来配置它的 Codex。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        if (config == null) {
            Card {
                Text(
                    if (codex.loading) "正在读取 ${codex.targetName} 的 Codex 配置…"
                    else "还没有读到 ${codex.targetName} 的 Codex 配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!codex.loading) {
                    TextButton(onClick = onLoad) { Text("重试", style = MaterialTheme.typography.labelMedium) }
                }
            }
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
                items = templates.map { it.id to it.name } + ("custom" to "自定义"),
                selectedId = if (template != null) providerId else "custom",
                onSelect = { picked ->
                    clearKey = false
                    if (picked == "custom") {
                        providerId = "custom"
                        providerKey = "custom"
                        providerName = ""
                        baseUrl = ""
                        wireApi = "responses"
                        modelSlugs = ""
                        model = ""
                        effortLevels = "low, high, max"
                        effort = "high"
                        contextWindow = "200000"
                    } else {
                        val t = templates.find { it.id == picked }
                        providerId = picked
                        providerKey = picked
                        providerName = t?.name.orEmpty()
                        baseUrl = t?.baseUrl.orEmpty()
                        wireApi = t?.wireApi ?: "responses"
                        modelSlugs = t?.models?.joinToString(", ").orEmpty()
                        effortLevels = t?.reasoningLevels?.joinToString(", ").orEmpty()
                        model = t?.models?.firstOrNull().orEmpty()
                        effort = t?.defaultReasoning.orEmpty()
                        contextWindow = t?.contextWindow?.toString().orEmpty()
                    }
                },
            )

            if (template == null) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = providerKey,
                    onValueChange = { providerKey = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' } },
                    label = { Text("服务商标识（写进配置）", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    supportingText = {
                        Text(
                            "字母、数字、_ 或 -，同一台电脑上唯一",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = providerName,
                onValueChange = { providerName = it },
                label = { Text("名称", style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("API 地址", style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                supportingText = {
                    Text(
                        if (baseUrl.startsWith("http")) "写入 config.toml 的 base_url"
                        else "需要以 http:// 或 https:// 开头",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            FieldLabel("接口类型")
            ChipStrip(
                items = listOf("responses" to "Responses", "chat" to "Chat Completions"),
                selectedId = wireApi,
                onSelect = { wireApi = it },
            )

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = modelSlugs,
                onValueChange = { text ->
                    modelSlugs = text
                    val first = text.split(",").map { it.trim() }.firstOrNull { it.isNotEmpty() }
                    if (model.isBlank() || text.split(",").map { it.trim() }.none { it == model }) {
                        model = first.orEmpty()
                    }
                },
                label = { Text("模型标识（逗号分隔）", style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
            val slugs = modelSlugs.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (slugs.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FieldLabel("当前模型")
                ChipStrip(slugs.map { it to it }, selectedId = model, onSelect = { model = it })
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = effortLevels,
                onValueChange = { text ->
                    effortLevels = text
                    val first = text.split(",").map { it.trim() }.firstOrNull { it.isNotEmpty() }
                    if (effort.isBlank() || text.split(",").map { it.trim() }.none { it == effort }) {
                        effort = first.orEmpty()
                    }
                },
                label = { Text("推理强度（逗号分隔）", style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
            val levels = effortLevels.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (levels.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FieldLabel("当前推理强度")
                ChipStrip(levels.map { it to it }, selectedId = effort, onSelect = { effort = it })
            }

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
                onValueChange = {
                    apiKey = it
                    if (it.isNotBlank()) clearKey = false
                },
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
                    val existing = config.providers.find { it.id == providerKey }?.hasToken == true
                    Text(
                        when {
                            clearKey -> "保存后会删除这台电脑上已保存的密钥"
                            existing && apiKey.isBlank() -> "已保存，留空保持不变"
                            else -> "只写入这台电脑"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )

            val existingToken = config.providers.find { it.id == providerKey }?.hasToken == true
            if (existingToken) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = clearKey,
                        onCheckedChange = { checked ->
                            clearKey = checked
                            if (checked) apiKey = ""
                        },
                    )
                    Text(
                        "删除这台电脑上已保存的密钥",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { confirmApply = true },
                enabled = providerKey.isNotBlank() && providerName.isNotBlank() &&
                    baseUrl.startsWith("http") && slugs.isNotEmpty() &&
                    model.isNotBlank() && levels.isNotEmpty(),
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
        }
    }

    }

    if (confirmApply) {
        ConfirmDialog(
            title = "应用到 ${codex.targetName.ifBlank { "这台电脑" }}",
            body = buildString {
                append("将写入 ${config?.configPath}：\n\n")
                append("$providerName · $model\n$baseUrl\n")
                append("推理强度 $effort")
                contextWindow.toLongOrNull()?.let { append(" · 上下文 ${formatTokens(it)}") }
                if (clearKey) append("\n\n会删除这台电脑上已保存的 API Key。")
                if (apiKey.isNotBlank()) append("\n\n会写入新的 API Key。")
                append("\n\n写入前自动备份，其余设置保持不变。")
            },
            confirmLabel = "确认写入",
            onConfirm = {
                onApply(
                    CodexApplyRequest(
                        providerId = providerKey,
                        providerName = providerName.trim(),
                        baseUrl = baseUrl.trim(),
                        wireApi = wireApi,
                        models = modelSlugs.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        model = model,
                        apiKey = apiKey.ifBlank { null },
                        clearApiKey = clearKey,
                        reasoningEffort = effort,
                        reasoningLevels = effortLevels.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        contextWindow = contextWindow.toLongOrNull(),
                    ),
                )
                apiKey = ""
                clearKey = false
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

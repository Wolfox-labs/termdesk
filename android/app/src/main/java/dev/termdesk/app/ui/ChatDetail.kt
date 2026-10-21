package dev.termdesk.app.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termdesk.app.data.ChatEvent
import dev.termdesk.app.data.ChatModels
import dev.termdesk.app.data.CodexConfig
import dev.termdesk.app.data.ChatInfo
import dev.termdesk.app.data.SessionDetail


/** Folded-away facts about the open conversation, plus what the next turn runs on. */
@Composable
internal fun ChatDetailPanel(
    chat: ChatInfo,
    codexConfig: CodexConfig?,
    models: ChatModels?,
    onConfigure: (String, String?, String?) -> Unit,
    onSetMode: (String, String) -> Unit,
    onRequestModels: () -> Unit,
    /** Null when there is nowhere to open it (a preview, a test). */
    onOpenFiles: ((String) -> Unit)? = null,
) {
    val catalog = if (chat.engine == "codex") codexConfig?.models.orEmpty() else emptyList()
    // Two sources of truth, one picker: Codex declares its models in the config
    // catalog, every other kernel answers through chat.models (its own session
    // declaration). The thread's own model may be in neither (it can be set
    // outside TermDesk), so it is always offered - otherwise nothing looks chosen.
    val picker = remember(catalog, models, chat.model, chat.engine) {
        val declared = models?.models.orEmpty().map { it.id to it.label }
        val fromCatalog = catalog.map { it.slug to (it.displayName.ifBlank { it.slug }) }
        val pairs = if (chat.engine == "codex") fromCatalog else declared
        val all = if (chat.model.isNotBlank() && pairs.none { it.first == chat.model }) {
            listOf(chat.model to chat.model) + pairs
        } else pairs
        // 1556 models are real, so the picker is searchable and never a chip row.
        all.distinctBy { it.first }
    }
    val modelIds = picker.map { it.first }
    val modeIds = models?.modes.orEmpty().map { it.id }
    val labelOf = { id: String -> picker.firstOrNull { it.first == id }?.second ?: id }
    var pickerOpen by remember { mutableStateOf(false) }
    val declaredCurrent = models?.current?.takeIf { it.isNotBlank() }
    val currentLabel = when {
        chat.model.isNotBlank() -> labelOf(chat.model)
        declaredCurrent != null -> labelOf(declaredCurrent)
        else -> "默认"
    }
    val levels = remember(catalog, chat.model) {
        catalog.firstOrNull { it.slug == chat.model }?.reasoningLevels
            ?: catalog.flatMap { it.reasoningLevels }.distinct()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(chat.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            KernelBadge(chat.engine)
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildString {
                    append(chat.model.ifBlank { "默认模型" })
                    append(" · 思考 ")
                    append(chat.effort.ifBlank { "默认" })
                    when {
                        chat.isRunning -> append(" · 回复中")
                        chat.ready -> append(" · 就绪")
                        chat.isFailed -> append(" · 失败")
                        else -> append(" · 未启动")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        // The working directory is the way into the files: everything the turn
        // produced is in here, and making the person find the same folder again by
        // hand is the whole reason the two screens felt unrelated.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .then(
                    if (onOpenFiles != null) {
                        Modifier.clickable { onOpenFiles(chat.cwd) }
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 3.dp),
        ) {
            if (onOpenFiles != null) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    // The action, not the icon: a screen reader should not stop at
                    // "folder" and leave the person to guess what a tap does.
                    contentDescription = "在文件里打开 ${chat.cwd}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                chat.cwd,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // A handful of models stay chips. Anything longer is a searchable picker:
        // one ACP kernel declares 1556 of them, and a chip row is not a way to
        // choose among those.
        if (modelIds.isNotEmpty() && modelIds.size <= CHIP_LIMIT) {
            Spacer(Modifier.height(8.dp))
            InlineChips(
                label = "模型",
                items = modelIds,
                selected = chat.model,
                labelOf = labelOf,
                onSelect = { onConfigure(chat.id, it, chat.effort.ifBlank { null }) },
            )
        } else if (modelIds.size > CHIP_LIMIT) {
            Spacer(Modifier.height(4.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(44.dp),
                )
                TextButton(
                    onClick = { pickerOpen = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        currentLabel,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                }
                Text(
                    modelIds.size.toString() + " 个模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (modeIds.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            InlineChips(
                label = "权限",
                items = modeIds,
                selected = models?.currentMode.orEmpty(),
                labelOf = { id -> models?.modeLabelOf(id) ?: id },
                onSelect = { onSetMode(chat.id, it) },
            )
        }
        if (levels.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            InlineChips(
                label = "思考",
                items = levels,
                selected = chat.effort,
                onSelect = { onConfigure(chat.id, chat.model.ifBlank { null }, it) },
            )
        }
        if (modelIds.isEmpty() && levels.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            val note = models?.note
            Text(
                when {
                    models == null && chat.engine != "codex" -> "正在读取模型列表…"
                    !note.isNullOrBlank() -> note
                    chat.engine == "codex" -> "模型列表需在 Codex 中配置。"
                    else -> "此助手未提供模型列表。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (pickerOpen) {
            ChoicePickerDialog(
                title = "选择模型 · " + chat.engine,
                options = picker,
                selected = chat.model.ifBlank { declaredCurrent.orEmpty() },
                onDismiss = { pickerOpen = false },
                onPick = { id ->
                    pickerOpen = false
                    onConfigure(chat.id, id, chat.effort.ifBlank { null })
                },
            )
        }
    }
}

@Composable
internal fun RecordedDetailPanel(session: SessionDetail) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(session.title?.takeIf { it.isNotBlank() } ?: session.id, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            KernelBadge(session.engine)
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildString {
                    append("${session.totalEvents} 条")
                    if (session.truncated) append("（已截断）")
                    // The PC's verdict, not a guess from the engine name.
                    append(if (session.canResume) " · 可继续对话" else " · 只读")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        session.cwd?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Above this many choices a chip row stops being a choice and becomes a scroll. */
private const val CHIP_LIMIT = 12

/**
 * 「什么都能选」的 MIME 通配符。
 *
 * 单独提成常量不只是为了整洁：这个字面量里出现了星号紧跟斜杠的形状，而 **Kotlin 的块注释
 * 是可以嵌套的**——同样的形状一旦出现在文档注释内部，就会又开一层注释，于是外层注释一直
 * 开到文件末尾，把后面整片代码都吃掉（这个坑真踩过一次，报错位置和真正的原因差了上千行）。
 * 收在一处，就不用每次写文档注释时提心吊胆。
 */
internal const val MIME_ANY = "*/*"

/** Searchable model picker: an ACP kernel can declare more models than a screen holds. */

@Composable
internal fun ChoicePickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(options, query) {
        if (query.isBlank()) options
        else options.filter { (id, name) -> id.contains(query, true) || name.contains(query, true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("搜索") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    filtered.size.toString() + " / " + options.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(filtered, key = { it.first }) { (id, name) ->
                        val isSelected = id == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(id) }
                                .padding(vertical = 8.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (name != id) {
                                    Text(
                                        id,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (isSelected) {
                                Text(
                                    "当前",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** Recorded history, listed like conversations because that is what it is. */
@Composable
internal fun RecordedTranscript(
    session: SessionDetail,
    onOpenFile: ((String) -> Unit)? = null,
    onPreviewFile: ((String, String) -> Unit)? = null,
) {
    if (session.events.isEmpty()) {
        Text(
            "这条会话没有可显示的记录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    // Fresh state per session so switching records always opens at the end.
    val listState = key(session.engine, session.id) { rememberLazyListState() }
    LaunchedEffect(session.engine, session.id) {
        listState.scrollToBottomNow()
    }
    // Mapped and folded once per session. The seq matters here: a folded run is keyed by its
    // first event's seq, and recorded events arrive without one — every row would share the
    // same key, which is how a list recycles the wrong line.
    val rows = remember(session.engine, session.id, session.events) {
        foldTranscript(
            session.events.mapIndexed { index, event ->
                ChatEvent(
                    seq = index + 1,
                    at = 0L,
                    kind = event.kind,
                    role = event.role,
                    text = event.text,
                    name = event.name,
                    state = event.state,
                    exitCode = event.exitCode,
                    // Carried through, not dropped: it is what labels an injected block as a
                    // goal, a job list or a skill catalogue instead of a bare "上下文".
                    sourceKind = event.sourceKind,
                    streaming = false,
                    files = event.files,
                )
            },
        )
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 10.dp, end = 10.dp, top = 10.dp, bottom = 14.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is TranscriptRow.One -> ChatEventRow(row.event, onOpenFile, onPreviewFile)
                is TranscriptRow.Run -> ProcessRunLine(row.events, onOpenFile, onPreviewFile)
            }
        }
    }
}

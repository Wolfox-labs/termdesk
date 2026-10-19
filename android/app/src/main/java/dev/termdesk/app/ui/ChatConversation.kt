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

@Composable
/** Label + horizontally scrollable choices, one line each. */
internal fun InlineChips(
    label: String,
    items: List<String>,
    selected: String,
    labelOf: (String) -> String = { it },
    onSelect: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(44.dp),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEach { item ->
                val isSelected = item == selected
                Text(
                    text = labelOf(item),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                        )
                        .clickable { onSelect(item) }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
    }
}



/** The conversation itself: transcript above, composer pinned below. */
@Composable
internal fun Conversation(
    chat: ChatInfo,
    events: List<ChatEvent>,
    sending: Boolean,
    models: ChatModels?,
    commands: ChatCommands?,
    lastUpload: UploadedFile?,
    draftScope: String,
    onClaimChat: (String, Boolean) -> Unit,
    onUploadFile: (android.net.Uri, String) -> Unit,
    onClearUpload: () -> Unit,
    onSend: (String, String) -> Unit,
    onCancel: (String) -> Unit,
    onConfigure: (String, String?, String?) -> Unit,
    onSetMode: (String, String) -> Unit,
    onClose: (String) -> Unit,
    connected: Boolean,
    pendingSends: Int = 0,
    pendingDropped: Int = 0,
    /** Open a produced file's folder in the file section (see `DeliverableLine`). */
    onOpenFile: ((String) -> Unit)? = null,
) {
    // What this conversation can run as, both of them the kernel's own words:
    // the model list and the permission / agent modes it declared.
    val modelOptions = models?.models.orEmpty().map { it.id to it.label }
    val modeOptions = models?.modes.orEmpty().map { it.id to it.label }
    // The chat summary carries the mode the PC has actually applied, so it wins
    // over the mode the list was fetched with (a change since then is real).
    val chosenMode = chat.mode.ifBlank { models?.currentMode.orEmpty() }.ifBlank { null }
    val chosenModelLabel = when {
        chat.model.isNotBlank() -> models?.labelOf(chat.model) ?: chat.model
        models?.current != null -> models.labelOf(models.current!!)
        else -> "默认"
    }
    val chosenModeLabel = chosenMode?.let { id -> modeOptions.firstOrNull { it.first == id }?.second ?: id } ?: "默认"
    var modelPickerOpen by remember(chat.id) { mutableStateOf(false) }
    var modePickerOpen by remember(chat.id) { mutableStateOf(false) }
    // The file picked with `+`, once the PC has actually got it. The prompt then
    // carries its path, which every kernel can read - no kernel-specific image
    // block is invented here.
    val context = LocalContext.current
    val draftStore = remember(context) { ChatDraftStore(context.getSharedPreferences("termdesk.chat-drafts", Context.MODE_PRIVATE)) }
    val savedDraft = remember(draftScope, chat.id) { draftStore.read(draftScope, chat.id) }
    var draft by remember(draftScope, chat.id) { mutableStateOf(savedDraft.text) }
    var attachment by remember(draftScope, chat.id) { mutableStateOf(savedDraft.attachment) }
    fun updateDraft(value: String) {
        draft = value
        draftStore.write(draftScope, chat.id, ChatDraft(draft, attachment))
    }
    fun updateAttachment(value: UploadedFile?) {
        attachment = value
        draftStore.write(draftScope, chat.id, ChatDraft(draft, attachment))
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onUploadFile(uri, chat.cwd)
    }
    LaunchedEffect(lastUpload?.path) {
        val done = lastUpload ?: return@LaunchedEffect
        if (done.path.startsWith(chat.cwd.trimEnd('\\', '/'))) {
            updateAttachment(done)
            onClearUpload()
        }
    }
    // Fresh state per chat: opening or switching a conversation always lands at
    // its end, never at a leftover scroll offset from the previous one.
    val listState = key(chat.id) { rememberLazyListState() }
    // Follow the stream only while the user is already at (or has returned to)
    // the bottom. Scrolling up hands control back to the reader immediately.
    var stickToBottom by remember(chat.id) { mutableStateOf(true) }
    var programmaticScroll by remember(chat.id) { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && !programmaticScroll) {
                    stickToBottom = listState.isNearBottom()
                }
            }
    }

    val lastSeq = events.lastOrNull()?.seq ?: 0
    val lastLen = events.lastOrNull()?.text?.length ?: 0

    // Folded once per event list: a turn's plumbing (tool calls, results, reasoning) becomes one
    // line per run instead of a screenful, and it is folded here rather than inside the list
    // because a `LazyListScope` is not a place where state can be remembered.
    val rows = remember(events) { foldTranscript(events) }

    // Open / switch: snap to the last item's bottom (not merely its top).
    LaunchedEffect(chat.id) {
        programmaticScroll = true
        listState.scrollToBottomNow()
        programmaticScroll = false
        stickToBottom = true
    }

    // Stream follow: only when the reader is still parked at the end.
    LaunchedEffect(chat.id, lastSeq, lastLen, events.size) {
        if (events.isEmpty() || !stickToBottom) return@LaunchedEffect
        programmaticScroll = true
        listState.animateScrollToBottom()
        programmaticScroll = false
    }

    Column(Modifier.fillMaxSize()) {
        if (chat.ownership.isBusy) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("由 "+(chat.ownership.owner?.deviceName ?: "另一台设备")+" 控制", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { onClaimChat(chat.id, true) }, enabled = connected && !chat.isRunning) { Text(if (chat.isRunning) "生成中" else "接管") }
            }
        }
        Box(Modifier.weight(1f)) {
            if (events.isEmpty()) {
                Text(
                    "发送消息开始对话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is TranscriptRow.One -> ChatEventRow(row.event, onOpenFile)
                            is TranscriptRow.Run -> ProcessRunLine(row.events, onOpenFile)
                        }
                    }
                }
            }
        }

        run {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Attachment first: a long model name used to push it off the
                // right edge, where a scrolled row hid it entirely.
                ComposerChip("＋", "附件", withChevron = false) { filePicker.launch(arrayOf("*/*")) }
                if (modelOptions.isNotEmpty()) {
                    // The model name is the long one, so it is the chip that gives
                    // way: the attachment and permission entries stay on screen.
                    ComposerChip("模型", chosenModelLabel, modifier = Modifier.weight(1f)) { modelPickerOpen = true }
                }
                if (modeOptions.isNotEmpty()) {
                    ComposerChip("权限", chosenModeLabel) { modePickerOpen = true }
                }
            }
        }

        // The file that is about to be sent, and how to take it back off.
        attachment?.let { file ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "附件 " + file.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { updateAttachment(null) }, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "移除附件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        // `/` opens the conversation's own command panel. It lists what TermDesk
        // can actually do to THIS conversation - not a pretend copy of a kernel's
        // menu - and everything in it does something real when tapped.
        if (draft.startsWith("/") && !draft.contains(' ') && !draft.contains('\n')) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // The panel now carries the kernel's own commands as well as the
                    // four local ones, and with the keyboard up the lower rows were
                    // simply cut off. Cap it and let it scroll instead of hiding
                    // entries the user was told exist.
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                SlashRow("模型", if (modelOptions.isEmpty()) "此助手未提供模型列表" else chosenModelLabel, modelOptions.isNotEmpty()) {
                    updateDraft("")
                    modelPickerOpen = true
                }
                SlashRow("权限模式", if (modeOptions.isEmpty()) "此助手不支持切换权限模式" else chosenModeLabel, modeOptions.isNotEmpty()) {
                    updateDraft("")
                    modePickerOpen = true
                }
                SlashRow("附件", "上传至当前设备，随消息发送", true) {
                    updateDraft("")
                    filePicker.launch(arrayOf("*/*"))
                }
                SlashRow("关闭会话", "结束这个对话", true) {
                    updateDraft("")
                    onClose(chat.id)
                }
                // Commands the PC's kernel actually declares (DSH: /compact,
                // /export, /goal, /permission, ...). Tapping one puts the line in
                // the box instead of running it: compacting a conversation by
                // accident is not undoable, so the last step stays the user's.
                val declared = commands
                if (declared != null && declared.commands.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    for (command in declared.commands) {
                        val hint = command.hint?.takeIf { it.isNotBlank() }
                            ?: command.description.takeIf { it.isNotBlank() }
                            ?: "由 " + chat.engine + " 执行"
                        SlashRow(command.name, hint, true) {
                            updateDraft("/" + command.name + " ")
                        }
                    }
                } else if (!declared?.note.isNullOrBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    SlashRow("命令", declared?.note ?: "", false) {}
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Composer(
            draft = draft,
            onDraftChange = { updateDraft(it) },
            sending = sending,
            running = chat.isRunning,
            // No `connected` here. With the link down this used to grey the arrow out and swallow
            // the tap, which is precisely the state the offline queue exists for: the message is
            // held on the phone, the "待发 N 条 · 连上就发" line explains it, and it goes out when
            // the link returns. A dead button taught the opposite.
            canSend = !chat.ownership.isBusy && (chat.ready || chat.isRunning || chat.status != "stopped"),
            pendingCount = pendingSends,
            droppedCount = pendingDropped,
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    // The attachment travels as a path in the prompt: every kernel
                    // can read a file, and the transcript then shows what was sent.
                    val full = attachment?.let { "[附件] ${it.name} → ${it.path}\n$text" } ?: text
                    onSend(chat.id, full)
                    updateDraft("")
                    updateAttachment(null)
                }
            },
            onCancel = { onCancel(chat.id) },
        )

        if (modelPickerOpen) {
            ChoicePickerDialog(
                title = "选择模型 · " + chat.engine,
                options = modelOptions,
                selected = chat.model,
                onDismiss = { modelPickerOpen = false },
                onPick = { id ->
                    modelPickerOpen = false
                    onConfigure(chat.id, id, chat.effort.ifBlank { null })
                },
            )
        }
        if (modePickerOpen) {
            ChoicePickerDialog(
                title = "权限模式 · " + chat.engine,
                options = modeOptions,
                selected = chosenMode.orEmpty(),
                onDismiss = { modePickerOpen = false },
                onPick = { id ->
                    modePickerOpen = false
                    onSetMode(chat.id, id)
                },
            )
        }
    }
}



/** One line in the `/` panel: what it is, what it currently is, and what it does. */
@Composable
internal fun SlashRow(label: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "/" + label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outline,
            // A fixed 96dp column broke names longer than it ("permission" came
            // out as "/", "permissio", "n"). The label takes the width it needs
            // and the hint gets the rest, so a long command stays one line.
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 96.dp).padding(end = 10.dp),
        )
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

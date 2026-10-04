package dev.termdesk.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import dev.termdesk.app.data.DirectoryListing
import dev.termdesk.app.data.FileEntry
import dev.termdesk.app.data.SearchResults
import dev.termdesk.app.data.TransferState

/** Action pending user confirmation. */
private sealed interface PendingFileAction {
    data class Delete(val entry: FileEntry) : PendingFileAction
    data class Rename(val entry: FileEntry) : PendingFileAction
}

@Composable
fun FilesSection(
    listing: DirectoryListing?,
    loading: Boolean,
    transfer: TransferState?,
    initialPath: String,
    onNavigate: (String) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onDownload: (FileEntry) -> Unit,
    onUpload: (Uri, String) -> Unit,
    onCreate: (String, String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    search: SearchResults?,
    searching: Boolean,
    onSearch: (String, String) -> Unit,
    onClearSearch: () -> Unit,
) {
    var pending by remember { mutableStateOf<PendingFileAction?>(null) }
    var showPathEdit by remember { mutableStateOf(false) }
    var pathDraft by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // Inside the browser, back means "up one level" until the starting
    // directory is reached; only then does it leave the section. Anything
    // else would turn a directory tree into a flat place you cannot climb.
    val browsePath = listing?.path.orEmpty()
    BackHandler(
        enabled = browsePath.isNotBlank() && browsePath != initialPath,
    ) {
        listing?.parent?.takeIf { it.isNotBlank() }?.let(onNavigate)
    }
    var showNewFolder by remember { mutableStateOf(false) }
    var showNewFile by remember { mutableStateOf(false) }

    // Kick off the first listing once we know where to start.
    val currentPath = listing?.path ?: initialPath
    if (listing == null && !loading && initialPath.isNotBlank()) {
        remember(initialPath) { onNavigate(initialPath) }
    }

    if (showPathEdit) {
        PathEditDialog(
            draft = pathDraft,
            onDraftChange = { pathDraft = it },
            onGo = {
                val target = pathDraft.trim()
                if (target.isNotEmpty()) {
                    onNavigate(target)
                    onClearSearch()
                }
                showPathEdit = false
            },
            onDismiss = { showPathEdit = false },
        )
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && currentPath.isNotBlank()) onUpload(uri, currentPath)
    }

    Column(Modifier.fillMaxSize()) {
        // The path is what you read most, so it gets two lines and its own tap
        // target (it opens an edit dialog) instead of being a truncated label
        // squeezed between five icons.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 2.dp, end = 2.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { listing?.parent?.let(onNavigate) }) {
                Icon(
                    Icons.Outlined.ArrowBack,
                    contentDescription = "上级目录",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = currentPath.ifBlank { "选择目录" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        pathDraft = currentPath
                        showPathEdit = true
                    }
                    .padding(horizontal = 6.dp, vertical = 7.dp),
            )
            if (loading || searching) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(15.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = {
                pathDraft = currentPath
                showPathEdit = true
            }) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "编辑路径",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = {
                searchOpen = !searchOpen
                if (!searchOpen) {
                    query = ""
                    onClearSearch()
                }
            }) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = "搜索文件",
                    tint = if (searchOpen) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        // Actions on their own row, so neither they nor the path are cramped.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onNavigate(currentPath) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("刷新", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("上传", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { showNewFolder = true }) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("新建", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (searchOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text("按名称搜索当前目录及其子目录", style = MaterialTheme.typography.labelSmall)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(currentPath, query) }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                TextButton(
                    enabled = query.isNotBlank(),
                    onClick = { onSearch(currentPath, query) },
                ) { Text("搜索") }
            }
        }

        // Transfer progress
        transfer?.let { t ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    "${t.label} ${t.fileName}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                if (t.fraction > 0f) {
                    LinearProgressIndicator(
                        progress = { t.fraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        search?.let { results ->
            SearchResultsList(
                results = results,
                onOpen = { entry ->
                    if (entry.isDir) {
                        onNavigate(entry.path)
                        onClearSearch()
                    } else {
                        onOpenFile(entry)
                    }
                },
                onClose = { onClearSearch() },
            )
            return@Column
        }

        val items = listing?.items.orEmpty()
        if (items.isEmpty() && !loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "目录为空",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.path }) { entry ->
                FileRow(
                    entry = entry,
                    onOpen = {
                        if (entry.isDir) onNavigate(entry.path) else onOpenFile(entry)
                    },
                    onDownload = { onDownload(entry) },
                    onRename = { pending = PendingFileAction.Rename(entry) },
                    onDelete = { pending = PendingFileAction.Delete(entry) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
        }
    }

    when (val action = pending) {
        is PendingFileAction.Delete -> ConfirmDialog(
            title = "删除",
            body = "确定要删除 ${action.entry.name} 吗？此操作不可撤销。",
            confirmLabel = "删除",
            onConfirm = {
                onDelete(action.entry.path)
                pending = null
            },
            onDismiss = { pending = null },
        )
        is PendingFileAction.Rename -> NameInputDialog(
            title = "重命名",
            initial = action.entry.name,
            confirmLabel = "重命名",
            onConfirm = { newName ->
                onRename(action.entry.path, newName)
                pending = null
            },
            onDismiss = { pending = null },
        )
        null -> Unit
    }

    if (showNewFolder) {
        NameInputDialog(
            title = "新建文件夹",
            initial = "",
            confirmLabel = "创建",
            onConfirm = { name ->
                onCreate(currentPath, name, true)
                showNewFolder = false
            },
            onDismiss = { showNewFolder = false },
        )
    }

    if (showNewFile) {
        NameInputDialog(
            title = "新建文件",
            initial = "",
            confirmLabel = "创建",
            onConfirm = { name ->
                onCreate(currentPath, name, false)
                showNewFile = false
            },
            onDismiss = { showNewFile = false },
        )
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 2.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.isDir) Icons.Outlined.Folder else Icons.Outlined.Description,
            contentDescription = null,
            tint = if (entry.isDir) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (entry.isDir) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDir) {
                Text(
                    formatBytes(entry.sizeBytes),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!entry.isDir) {
            IconButton(onClick = onDownload) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = "下载到手机",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(onClick = onRename) {
            Icon(
                Icons.Outlined.DriveFileRenameOutline,
                contentDescription = "重命名",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

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

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("名称") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

/** Results of a name search, with the directory each hit lives in. */
@Composable
private fun SearchResultsList(
    results: SearchResults,
    onOpen: (FileEntry) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildString {
                    append("\u201c${results.query}\u201d 找到 ${results.items.size} 项")
                    if (results.truncated) append(" · 已达上限，缩小关键词更准")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text("收起") }
        }
        if (results.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "没有匹配的文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(results.items, key = { it.path }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(entry) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (entry.isDir) Icons.Outlined.Folder
                        else Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = entry.path.substringBeforeLast('\\').substringBeforeLast('/'),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
        }
    }
}

/** Type an absolute path to jump straight there. */
@Composable
private fun PathEditDialog(
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
                    "在电脑上打开这个目录。路径必须位于允许的根目录内。",
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

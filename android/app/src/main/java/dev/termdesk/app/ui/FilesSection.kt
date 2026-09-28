package dev.termdesk.app.ui

import android.net.Uri
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.termdesk.app.data.DirectoryListing
import dev.termdesk.app.data.FileEntry
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
) {
    var pending by remember { mutableStateOf<PendingFileAction?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showNewFile by remember { mutableStateOf(false) }

    // Kick off the first listing once we know where to start.
    val currentPath = listing?.path ?: initialPath
    if (listing == null && !loading && initialPath.isNotBlank()) {
        remember(initialPath) { onNavigate(initialPath) }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && currentPath.isNotBlank()) onUpload(uri, currentPath)
    }

    Column(Modifier.fillMaxSize()) {
        // Path breadcrumb + actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
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
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(15.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
            }
            IconButton(onClick = { onNavigate(currentPath) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新", modifier = Modifier.size(19.dp))
            }
            IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Outlined.Upload, contentDescription = "上传", modifier = Modifier.size(19.dp))
            }
            IconButton(onClick = { showNewFolder = true }) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建文件夹", modifier = Modifier.size(19.dp))
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

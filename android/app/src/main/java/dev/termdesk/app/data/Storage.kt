package dev.termdesk.app.data

import android.content.Context
import java.io.File
import java.nio.file.Files

/** One area of the app's own storage, with what it holds and who owns it. */
data class StorageEntry(
    val label: String,
    val path: String,
    val bytes: Long,
    val removable: Boolean,
    val note: String,
)

data class StorageUse(
    val entries: List<StorageEntry>,
    val removableBytes: Long,
) {
    val totalBytes: Long get() = entries.sumOf { it.bytes }
}

/**
 * What TermDesk stores on the phone, and why.
 *
 * Kept as a real measurement rather than a hope: "the app is using 280 MB" is
 * either a cache doing its job or a leak, and the only way to tell them apart is
 * to size each area and know which one the code still uses.
 */
object Storage {

    /**
     * The local kernel's userland, unpacked into the app's private files.
     *
     * This is NOT leftover data: it is a complete Termux bootstrap rebuilt under
     * this app's own package name, so the hard-coded `/data/data/.../files/usr`
     * prefix points at TermDesk instead of Termux. It carries bash, apt/dpkg,
     * python3, node and git (8302 archive entries, 1431 of them symlinks).
     *
     * Stated plainly because it was mislabelled before: nothing reads it yet —
     * the client-side install and launch flow is still to be written — but it is
     * the sandbox itself, and deleting it means re-downloading ~54 MB and
     * re-extracting, not just clearing a cache.
     */
    private val LOCAL_KERNEL = listOf("usr", "home", "profileInstalled")

    /** The label the storage screen shows for it; also how removal finds it. */
    private const val LOCAL_KERNEL_LABEL = "本地内核沙盒（Termux）"

    fun inspect(context: Context): StorageUse {
        val files = context.filesDir
        val entries = mutableListOf<StorageEntry>()

        val sandboxBytes = LOCAL_KERNEL.sumOf { size(File(files, it)) }
        if (sandboxBytes > 0) {
            entries += StorageEntry(
                label = LOCAL_KERNEL_LABEL,
                path = files.absolutePath,
                bytes = sandboxBytes,
                removable = true,
                note = "本地内核的 Linux 环境（bash / apt / python3 / node / git）。删除后需要重新安装，不只是清缓存",
            )
        }

        entries += StorageEntry(
            label = "历史缓存",
            path = File(files, "history-cache").absolutePath,
            bytes = size(File(files, "history-cache")),
            removable = true,
            note = "离线可读的会话快照，超出预算会自动清理最早的一份",
        )

        entries += StorageEntry(
            label = "临时预览",
            path = File(context.cacheDir, "preview").absolutePath,
            bytes = size(File(context.cacheDir, "preview")),
            removable = true,
            note = "查看图片/PDF 时临时落盘，看完即删",
        )

        entries += StorageEntry(
            label = "已下载文件",
            path = File(context.getExternalFilesDir(null) ?: files, "downloads").absolutePath,
            bytes = size(File(context.getExternalFilesDir(null) ?: files, "downloads")),
            removable = true,
            note = "你手动保存到手机的文件",
        )

        return StorageUse(entries, entries.filter { it.removable }.sumOf { it.bytes })
    }

    /** Remove an area by its reported path. */
    fun clear(entry: StorageEntry): Long {
        val target = File(entry.path)
        val freed = size(target)
        if (entry.label == LOCAL_KERNEL_LABEL) {
            LOCAL_KERNEL.forEach { deleteRecursively(File(target, it)) }
        } else {
            deleteRecursively(target)
        }
        return freed
    }

    /**
     * Size of a tree, without following symlinks.
     *
     * A Termux payload is full of them (`bin -> usr/bin`), and following the
     * links counted the same files several times: the screen said 997 MB for a
     * tree that is really 261 MB, which is worse than saying nothing.
     */
    fun size(file: File): Long = try {
        val path = file.toPath()
        when {
            Files.isSymbolicLink(path) -> 0L
            Files.isRegularFile(path) -> Files.size(path)
            Files.isDirectory(path) -> {
                // Not Stream.toList(): that is a Java 16 addition and Android's
                // core library does not have it, so it throws at runtime.
                var total = 0L
                Files.newDirectoryStream(path).use { entries ->
                    for (child in entries) total += size(child.toFile())
                }
                total
            }
            else -> 0L
        }
    } catch (_: Exception) {
        0L
    }

    private fun deleteRecursively(file: File) {
        try {
            val path = file.toPath()
            // Never walk through a link: it points elsewhere, and the target is
            // not ours to delete.
            if (!Files.isSymbolicLink(path) && Files.isDirectory(path)) {
                Files.newDirectoryStream(path).use { entries ->
                    for (child in entries) deleteRecursively(child.toFile())
                }
            }
            file.delete()
        } catch (_: Exception) {
            // Best effort: a locked file is not a reason to fail the whole clear.
        }
    }

    fun format(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
}

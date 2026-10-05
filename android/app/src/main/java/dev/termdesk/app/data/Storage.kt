package dev.termdesk.app.data

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

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
    /**
     * True when a walk ran out of budget before it finished, so the numbers are
     * lower bounds rather than measurements. Shown as ">= 1.90 GB" instead of
     * quietly presenting an unfinished count as a fact.
     */
    val truncated: Boolean = false,
) {
    val totalBytes: Long get() = entries.sumOf { it.bytes }
}

/** Size of one tree, plus whether the walk actually finished. */
data class Measurement(val bytes: Long, val complete: Boolean)

/**
 * What TermDesk stores on the phone, and why.
 *
 * Kept as a real measurement rather than a hope: "the app is using 280 MB" is
 * either a cache doing its job or a leak, and the only way to tell them apart is
 * to size each area and know which one the code still uses.
 *
 * Three rules, each learned the hard way:
 *
 *  - It is I/O, never UI work. The local sandbox is 72,405 files; walking it
 *    inside composition froze Settings and then crashed the app (the ANR trace
 *    ended in the recursion below). Callers hand it a background thread.
 *  - It is bounded. A walk stops at a deadline or an entry cap and says so,
 *    so no directory the user can create can hang the phone for good. The first
 *    version of this cap was 60,000 entries, which silently reported 443 MB for
 *    a 1.9 GB tree - a bound is only honest if it is labelled.
 *  - It does not follow symlinks. A Termux payload is full of links
 *    (`bin -> usr/bin`); following them counted the same files several times and
 *    the screen said 997 MB for a tree that is really 261 MB, which is worse
 *    than saying nothing.
 */
object Storage {

    /**
     * The local kernel's userland, unpacked into the app's private files.
     *
     * This is NOT leftover data: it is a complete Termux bootstrap rebuilt under
     * this app's own package name, so the hard-coded `/data/data/.../files/usr`
     * prefix points at TermDesk instead of Termux. It carries bash, apt/dpkg,
     * python3, node and git - 72,405 entries, 5,630 of them symlinks, about
     * 1.9 GB on disk. Nothing launches it yet - the install-and-run flow on the
     * phone side is still to be written - but it is the sandbox itself, and
     * deleting it means downloading and extracting again, not clearing a cache.
     */
    private val LOCAL_KERNEL = listOf("usr", "home", "profileInstalled")

    /** The label the storage screen shows for it; also how removal finds it. */
    private const val LOCAL_KERNEL_LABEL = "本地内核沙盒（Termux）"

    /** The sandbox is the one big tree, and it is worth finishing the count. */
    private const val BIG_BUDGET_MS = 6000L
    private const val BIG_MAX_ENTRIES = 400_000

    /** The other areas are a handful of files; more than this is a runaway. */
    private const val SMALL_BUDGET_MS = 1500L
    private const val SMALL_MAX_ENTRIES = 60_000

    fun inspect(context: Context): StorageUse {
        val files = context.filesDir
        val entries = mutableListOf<StorageEntry>()
        var truncated = false

        var sandboxBytes = 0L
        var sandboxComplete = true
        LOCAL_KERNEL.forEach { name ->
            val m = measure(File(files, name), BIG_BUDGET_MS, BIG_MAX_ENTRIES)
            sandboxBytes += m.bytes
            sandboxComplete = sandboxComplete && m.complete
        }
        truncated = truncated || !sandboxComplete
        if (sandboxBytes > 0) {
            entries += StorageEntry(
                label = LOCAL_KERNEL_LABEL,
                path = files.absolutePath,
                bytes = sandboxBytes,
                removable = true,
                note = "本地内核的 Linux 环境（bash / apt / python3 / node / git）。删除后需要重新安装，不只是清缓存",
            )
        }

        val areas = listOf(
            Triple("历史缓存", File(files, "history-cache"), "离线可读的会话快照，超出预算会自动清理最早的一份"),
            Triple("临时预览", File(context.cacheDir, "preview"), "查看图片/PDF 时临时落盘，看完即删"),
            Triple(
                "已下载文件",
                File(context.getExternalFilesDir(null) ?: files, "downloads"),
                "你手动保存到手机的文件",
            ),
        )
        areas.forEach { (label, dir, note) ->
            val m = measure(dir, SMALL_BUDGET_MS, SMALL_MAX_ENTRIES)
            truncated = truncated || !m.complete
            entries += StorageEntry(
                label = label,
                path = dir.absolutePath,
                bytes = m.bytes,
                removable = true,
                note = note,
            )
        }

        return StorageUse(entries, entries.filter { it.removable }.sumOf { it.bytes }, truncated)
    }

    /** Remove an area by its reported path. Blocking I/O: call off the UI thread. */
    fun clear(entry: StorageEntry): Long {
        val target = File(entry.path)
        val freed = measure(target).bytes
        if (entry.label == LOCAL_KERNEL_LABEL) {
            LOCAL_KERNEL.forEach { deleteRecursively(File(target, it)) }
        } else {
            deleteRecursively(target)
        }
        return freed
    }

    /**
     * Size of a tree, without following symlinks, on an explicit stack.
     *
     * One `readAttributes` per entry answers "link? file? directory? how big?"
     * in a single lstat; asking three separate questions cost three syscalls and
     * made a walk of 72,405 entries take long enough to matter.
     *
     * Iterative rather than recursive on purpose: the recursive version was the
     * frame that took the app down when a deep tree met the UI thread, and an
     * explicit stack also makes "stop at the budget" a single clean exit.
     */
    fun measure(
        file: File,
        budgetMs: Long = SMALL_BUDGET_MS,
        maxEntries: Int = SMALL_MAX_ENTRIES,
        now: () -> Long = System::currentTimeMillis,
    ): Measurement {
        val deadline = now() + budgetMs
        var total = 0L
        var visited = 0
        var complete = true
        val pending = ArrayDeque<Path>()
        pending.addLast(file.toPath())

        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            visited++
            if (visited > maxEntries || now() > deadline) {
                complete = false
                break
            }
            try {
                val attrs = Files.readAttributes(
                    current,
                    BasicFileAttributes::class.java,
                    // A link points elsewhere; its target is not ours to count.
                    LinkOption.NOFOLLOW_LINKS,
                )
                when {
                    attrs.isSymbolicLink -> Unit
                    attrs.isRegularFile -> total += attrs.size()
                    attrs.isDirectory ->
                        Files.newDirectoryStream(current).use { children ->
                            for (child in children) pending.addLast(child)
                        }
                    else -> Unit
                }
            } catch (_: Exception) {
                // Unreadable entry: skip it rather than fail the whole count.
            }
        }
        return Measurement(total, complete)
    }

    /**
     * Size of the sandbox tree, with the budget a tree that big needs.
     *
     * Calling measure() with the small-area defaults bounded the walk at 1.5 s
     * and then reported 646 MB for a tree that is larger - a lower bound served
     * as a fact, which is the same mistake the storage screen already fixed.
     */
    fun measureSandbox(file: File): Measurement =
        measure(file, BIG_BUDGET_MS, BIG_MAX_ENTRIES)

    /** Convenience for callers that only want a number. */
    fun size(file: File): Long = measure(file).bytes

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

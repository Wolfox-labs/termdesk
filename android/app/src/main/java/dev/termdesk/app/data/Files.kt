package dev.termdesk.app.data

/** One entry in a directory listing. */
data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long,
    val mtime: String?,
    /**
     * How the phone should show this file: dir | text | image | pdf | docx | other.
     * Decided by the PC from the extension, so the client does not guess.
     */
    val kind: String = "other",
) {
    val isPreviewable: Boolean get() = !isDir && kind != "other"

    companion object {
        /**
         * 对话里刚产出的那个文件。
         *
         * 和目录列表不同：这里手上只有一条路径和一个文件名，没有 PC 判定的 kind。
         * 为了让「点一下就看到效果」不先等一次往返，kind 在本地按扩展名推断；
         * 目录列表里的每个文件仍然由 PC 决定 kind，两者取值保持同一套。
         */
        fun produced(path: String, name: String): FileEntry = FileEntry(
            name = name.ifBlank { path.substringAfterLast('\\').substringAfterLast('/') },
            path = path,
            isDir = false,
            sizeBytes = 0L,
            mtime = null,
            kind = previewKindOf(name),
        )
    }
}

/** 本机按扩展名推断的查看方式，取值与 PC 的 kind 一致。 */
internal fun previewKindOf(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif" -> "image"
        "pdf" -> "pdf"
        "docx" -> "docx"
        "html", "htm" -> "html"
        "txt", "md", "log", "json", "xml", "csv", "yml", "yaml",
        "kt", "kts", "java", "js", "ts", "py", "sh", "css", "sql", "toml", "ini" -> "text"
        else -> "other"
    }

/** A directory listing plus where we are and how to go up. */
data class DirectoryListing(
    val path: String,
    val parent: String,
    val items: List<FileEntry>,
)

/** One file-name search across a directory tree on the PC. */
data class SearchResults(
    val path: String,
    val query: String,
    val items: List<FileEntry>,
    /** True when a cap (count, depth, time) stopped the walk early. */
    val truncated: Boolean,
    val scannedDirs: Int,
)

/** A text file opened for editing. */
data class TextFile(
    val path: String,
    val text: String,
    val sizeBytes: Long,
    val mtime: String? = null,
)

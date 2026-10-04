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
)

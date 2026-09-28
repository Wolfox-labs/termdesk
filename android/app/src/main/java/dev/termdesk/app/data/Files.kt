package dev.termdesk.app.data

/** One entry in a directory listing. */
data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long,
    val mtime: String?,
)

/** A directory listing plus where we are and how to go up. */
data class DirectoryListing(
    val path: String,
    val parent: String,
    val items: List<FileEntry>,
)

/** A text file opened for editing. */
data class TextFile(
    val path: String,
    val text: String,
    val sizeBytes: Long,
)

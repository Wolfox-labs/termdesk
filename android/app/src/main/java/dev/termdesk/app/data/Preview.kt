package dev.termdesk.app.data

import java.io.File

/**
 * A file the phone is showing in its viewer.
 *
 * Only [kind] is authoritative: the PC decides it from the name, so an
 * extension the phone has never heard of still lands in the right viewer. What
 * travels depends on the kind — text for documents, bytes for pictures and PDFs
 * — because the PC keeps the file and the phone only ever gets what it draws.
 */
data class FilePreview(
    /** image | pdf | docx | other */
    val kind: String,
    val name: String,
    val path: String,
    val loading: Boolean = false,
    /** docx: the text the PC extracted. */
    val text: String? = null,
    /** image / pdf: the bytes fetched into the app cache. */
    val localFile: File? = null,
    val sizeBytes: Long = 0L,
    /** Set when the file could not be shown; shown instead of an empty viewer. */
    val message: String? = null,
) {
    val title: String get() = name.ifBlank { path.substringAfterLast('\\').substringAfterLast('/') }
}

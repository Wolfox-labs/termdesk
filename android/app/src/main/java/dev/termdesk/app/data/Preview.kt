package dev.termdesk.app.data

import java.io.File

/** Transport used to get a preview into the phone's viewer. */
internal enum class PreviewTransport {
    TEXT_FILE,
    DOCX_TEXT,
    HTTP_DOWNLOAD,
    INFORMATION,
}

/** Keep file classification and preview transport aligned. HTML is downloaded for WebView. */
internal fun previewTransport(kind: String): PreviewTransport = when (kind) {
    "text" -> PreviewTransport.TEXT_FILE
    "docx" -> PreviewTransport.DOCX_TEXT
    "html", "image", "pdf" -> PreviewTransport.HTTP_DOWNLOAD
    else -> PreviewTransport.INFORMATION
}

/**
 * A file the phone is showing in its viewer.
 *
 * Only [kind] is authoritative: the PC decides it from the name, so an
 * extension the phone has never heard of still lands in the right viewer. What
 * travels depends on the kind — text for documents, bytes for pictures and PDFs
 * — because the PC keeps the file and the phone only ever gets what it draws.
 */
data class FilePreview(
    /** html | image | pdf | docx | other */
    val kind: String,
    val name: String,
    val path: String,
    val loading: Boolean = false,
    /** docx: extracted text; html: downloaded HTML source for WebView. */
    val text: String? = null,
    /** Downloaded preview bytes, retained in the app cache. */
    val localFile: File? = null,
    val sizeBytes: Long = 0L,
    /** Set when the file could not be shown; shown instead of an empty viewer. */
    val message: String? = null,
) {
    val title: String get() = name.ifBlank { path.substringAfterLast('\\').substringAfterLast('/') }
}

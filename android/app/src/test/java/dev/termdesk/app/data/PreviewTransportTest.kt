package dev.termdesk.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewTransportTest {
    @Test
    fun `html uses the authenticated download path while docx uses extraction`() {
        assertEquals(PreviewTransport.HTTP_DOWNLOAD, previewTransport("html"))
        assertEquals(PreviewTransport.HTTP_DOWNLOAD, previewTransport("image"))
        assertEquals(PreviewTransport.DOCX_TEXT, previewTransport("docx"))
        assertEquals(PreviewTransport.TEXT_FILE, previewTransport("text"))
    }
}

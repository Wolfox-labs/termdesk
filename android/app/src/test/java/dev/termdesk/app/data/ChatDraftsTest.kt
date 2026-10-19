package dev.termdesk.app.data

import org.junit.Assert.*
import org.junit.Test

class ChatDraftsTest {
    @Test fun `draft text and uploaded attachment survive storage`() {
        val draft = ChatDraft("未发送的草稿\n第二行", UploadedFile("图片.png", "/home/图片.png", 123))
        assertEquals(draft, ChatDraft.fromJson(draft.toJson()))
    }
    @Test fun `invalid or absent stored data does not break the composer`() {
        assertEquals(ChatDraft(), ChatDraft.fromJson("broken"))
        assertEquals(ChatDraft(), ChatDraft.fromJson(null))
    }
    @Test fun `devices and conversations have separate drafts`() {
        assertNotEquals(ChatDraft.key("local", "c"), ChatDraft.key("remote:pc1", "c"))
        assertNotEquals(ChatDraft.key("remote:pc1", "c"), ChatDraft.key("remote:pc2", "c"))
        assertNotEquals(ChatDraft.key("local", "c"), ChatDraft.key("local", "other"))
    }
}

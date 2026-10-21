package dev.termdesk.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PhoneChatEventsTest {
    @Test fun `phone poster and note survive a new store and full or empty server snapshots`() {
        val root = Files.createTempDirectory("phone-chat-events").toFile()
        try {
            PhoneChatEvents(root).apply {
                append("pc-a", "dsh:native", "处理完成")
                append("pc-a", "dsh:native", "海报完成", "/private/poster.png")
            }
            val restored = PhoneChatEvents(root).read("pc-a", "dsh:native")
            assertEquals(2, restored.size)
            assertEquals("/private/poster.png", restored.last().poster)
            val server = reconcileChatEvents(JSONObject().put("events", JSONArray().put(
                JSONObject().put("seq", 1).put("kind", "message").put("text", "changed server answer"),
            )))
            assertEquals(listOf("changed server answer", "处理完成", "海报完成"),
                withPhoneChatEvents(server, restored).map { it.text })
            assertEquals(restored, withPhoneChatEvents(emptyList(), restored))
        } finally { root.deleteRecursively() }
    }

    @Test fun `computer and chat identities isolate phone history and migrate to a native session`() {
        val root = Files.createTempDirectory("phone-chat-scope").toFile()
        try {
            val store = PhoneChatEvents(root)
            store.append("pc-a", "dsh:wrapper", "draft artifact")
            store.migrate("pc-a", "dsh:wrapper", "dsh:native")
            assertTrue(store.read("pc-a", "dsh:wrapper").isEmpty())
            assertEquals("draft artifact", PhoneChatEvents(root).read("pc-a", "dsh:native").single().text)
            assertTrue(store.read("pc-b", "dsh:native").isEmpty())
            assertTrue(store.read("local", "dsh:native").isEmpty())
            assertTrue(store.read("pc-a", "dsh:other").isEmpty())
            val first = parseChatInfo(JSONObject().put("id", "wrapper").put("engine", "dsh"))!!
            assertEquals("dsh:wrapper", phoneChatIdentity(first))
            assertEquals("dsh:native", phoneChatIdentity(first.copy(sessionId = "native")))
        } finally { root.deleteRecursively() }
    }
}

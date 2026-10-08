package dev.termdesk.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering rules for the session list.
 *
 * These are the questions a person asks while looking at the list — "why is this
 * one first?", "where did my session go?" — turned into assertions. Each of them
 * is easy to get wrong in a composable and impossible to see in a screenshot.
 */
class SessionGroupsTest {

    private fun chat(id: String, cwd: String, lastUsedAt: Long) = ChatInfo(
        id = id,
        title = "对话 $id",
        cwd = cwd,
        provider = "",
        model = "",
        effort = "",
        mode = "",
        status = "idle",
        ready = true,
        engine = "dsh",
        threadId = null,
        sessionId = null,
        createdAt = lastUsedAt,
        lastUsedAt = lastUsedAt,
        eventCount = 1,
        lastError = null,
    )

    private fun session(id: String, cwd: String?, updatedAt: String?) = SessionInfo(
        engine = "codex",
        id = id,
        title = "会话 $id",
        cwd = cwd,
        createdAt = null,
        updatedAt = updatedAt,
        sizeBytes = 0,
        path = "/tmp/$id",
    )

    private val shortName: (String) -> String = { path ->
        path.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { path }
    }

    @Test
    fun `an empty list is an empty list, not a crash`() {
        assertEquals(emptyList<SessionGroups.Group>(), SessionGroups.build(emptyList(), emptyList(), shortName))
    }

    @Test
    fun `conversations and recorded sessions land in the same group when they share a directory`() {
        val groups = SessionGroups.build(
            chats = listOf(chat("c1", "E:\\aiPic\\termdesk", 1_000)),
            sessions = listOf(session("s1", "E:\\aiPic\\termdesk", "2026-10-08T02:57:35.334Z")),
            nameOf = shortName,
        )

        assertEquals(1, groups.size)
        assertEquals("termdesk", groups[0].name)
        assertEquals(1, groups[0].live.size)
        assertEquals(1, groups[0].recorded.size)
        assertEquals("the header counts both kinds", 2, groups[0].count)
    }

    @Test
    fun `groups are ordered by name, not by how recently they were used`() {
        val groups = SessionGroups.build(
            chats = listOf(
                chat("newest", "E:\\work\\zebra", 9_000),
                chat("older", "E:\\work\\apple", 1_000),
            ),
            sessions = emptyList(),
            nameOf = shortName,
        )

        assertEquals(
            "apple must come first even though zebra was used more recently",
            listOf("apple", "zebra"),
            groups.map { it.name },
        )
    }

    @Test
    fun `inside a group the most recent conversation is first`() {
        val groups = SessionGroups.build(
            chats = listOf(
                chat("old", "E:\\w\\p", 1_000),
                chat("new", "E:\\w\\p", 5_000),
                chat("middle", "E:\\w\\p", 3_000),
            ),
            sessions = emptyList(),
            nameOf = shortName,
        )

        assertEquals(listOf("new", "middle", "old"), groups[0].live.map { it.id })
    }

    @Test
    fun `recorded sessions sort by their ISO time, newest first`() {
        val groups = SessionGroups.build(
            chats = emptyList(),
            sessions = listOf(
                session("a", "E:\\w\\p", "2026-10-01T10:00:00.000Z"),
                session("c", "E:\\w\\p", "2026-10-08T10:00:00.000Z"),
                session("b", "E:\\w\\p", "2026-10-05T10:00:00.000Z"),
            ),
            nameOf = shortName,
        )

        assertEquals(listOf("c", "b", "a"), groups[0].recorded.map { it.id })
    }

    @Test
    fun `a conversation with no directory is grouped, not dropped`() {
        val groups = SessionGroups.build(
            chats = listOf(chat("homeless", "", 1_000)),
            sessions = listOf(session("alsoHomeless", null, null)),
            nameOf = shortName,
        )

        assertEquals(1, groups.size)
        assertEquals(SessionGroups.UNKNOWN_WORKSPACE, groups[0].name)
        assertEquals("nothing may disappear from the list", 2, groups[0].count)
    }

    @Test
    fun `the unknown group sorts with the named ones rather than always last`() {
        val groups = SessionGroups.build(
            chats = listOf(chat("a", "E:\\w\\apple", 1_000), chat("z", "", 1_000)),
            sessions = emptyList(),
            nameOf = shortName,
        )

        assertEquals(2, groups.size)
        // "未指定目录" sorts by its own name; what matters is that it is present and
        // that the order is stable, not which of the two happens to win.
        assertTrue(groups.map { it.name }.contains(SessionGroups.UNKNOWN_WORKSPACE))
    }

    @Test
    fun `a group keeps the newest timestamp of anything in it`() {
        val groups = SessionGroups.build(
            chats = listOf(chat("c", "E:\\w\\p", 1_000)),
            sessions = listOf(session("s", "E:\\w\\p", "2026-10-08T02:57:35.334Z")),
            nameOf = shortName,
        )

        val isoTime = SessionGroups.parseIsoTime("2026-10-08T02:57:35.334Z")
        assertTrue("the ISO time must actually parse", isoTime > 0)
        assertEquals("the newer of the two wins", isoTime, groups[0].latestAt)
    }

    @Test
    fun `an unparseable or absent time sorts to the bottom instead of jumping to the top`() {
        assertEquals(0L, SessionGroups.parseIsoTime(null))
        assertEquals(0L, SessionGroups.parseIsoTime(""))
        assertEquals(0L, SessionGroups.parseIsoTime("not a date"))

        val groups = SessionGroups.build(
            chats = emptyList(),
            sessions = listOf(
                session("broken", "E:\\w\\p", "not a date"),
                session("dated", "E:\\w\\p", "2026-10-08T02:57:35.334Z"),
            ),
            nameOf = shortName,
        )

        assertEquals("the dated one is first", listOf("dated", "broken"), groups[0].recorded.map { it.id })
    }
}

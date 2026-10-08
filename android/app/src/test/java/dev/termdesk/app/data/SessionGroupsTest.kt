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

    private fun chat(
        id: String,
        cwd: String,
        lastUsedAt: Long,
        engine: String = "dsh",
        title: String = "对话 $id",
    ) = ChatInfo(
        id = id,
        title = title,
        cwd = cwd,
        provider = "",
        model = "",
        effort = "",
        mode = "",
        status = "idle",
        ready = true,
        engine = engine,
        threadId = null,
        sessionId = null,
        createdAt = lastUsedAt,
        lastUsedAt = lastUsedAt,
        eventCount = 1,
        lastError = null,
    )

    private fun session(
        id: String,
        cwd: String?,
        updatedAt: String?,
        engine: String = "codex",
        title: String? = "会话 $id",
    ) = SessionInfo(
        engine = engine,
        id = id,
        title = title,
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

    // ---- kernel scope: kernel -> workspace -> conversation --------------------

    @Test
    fun `only the kernels with conversations are offered`() {
        // The machine had 313 recorded sessions across every kernel at once, so the flat
        // list made "find my OpenCode conversation" mean scanning Codex and MiMo too.
        // A kernel with nothing to show must not be offered at all: that is a button
        // which lands on an empty screen.
        val scope = SessionGroups.scopeOf(
            chats = listOf(chat("c1", "E:\\w\\a", 1_000, engine = "dsh")),
            sessions = listOf(
                session("s1", "E:\\w\\a", "2026-10-08T02:00:00.000Z", engine = "codex"),
                session("s2", "E:\\w\\b", "2026-10-08T03:00:00.000Z", engine = "codex"),
            ),
            selected = null,
        )

        assertEquals(listOf("codex", "dsh"), scope.engines)
        assertTrue("two kernels is a real choice", scope.isMeaningful)
        assertTrue("nothing is selected means everything is shown", scope.selected == null)
    }

    @Test
    fun `one kernel is not a choice, so no switcher is drawn`() {
        val scope = SessionGroups.scopeOf(
            chats = emptyList(),
            sessions = listOf(session("s1", "E:\\w", null, engine = "codex")),
            selected = null,
        )

        assertEquals(listOf("codex"), scope.engines)
        assertTrue("a one-item switcher is noise", !scope.isMeaningful)
    }

    @Test
    fun `a kernel is counted once however many conversations it has`() {
        val scope = SessionGroups.scopeOf(
            chats = listOf(chat("c1", "E:\\w", 1, "codex"), chat("c2", "E:\\w", 2, "codex")),
            sessions = listOf(session("s1", "E:\\w", null, "codex"), session("s2", "E:\\w", null, "dsh")),
            selected = null,
        )

        assertEquals(listOf("codex", "dsh"), scope.engines)
    }

    @Test
    fun `narrowing to a kernel keeps only that kernel's conversations`() {
        val chats = listOf(chat("c1", "E:\\w", 1, "codex"), chat("c2", "E:\\w", 2, "dsh"))
        val sessions = listOf(session("s1", "E:\\w", null, "codex"), session("s2", "E:\\w", null, "mimo"))

        val (codexChats, codexSessions) = SessionGroups.only(chats, sessions, "codex")

        assertEquals(listOf("c1"), codexChats.map { it.id })
        assertEquals(listOf("s1"), codexSessions.map { it.id })
    }

    @Test
    fun `a selection that no longer exists shows everything instead of nothing`() {
        // The stored choice can outlive what it pointed at: the kernel's last session was
        // deleted, or a re-pairing changed the engine list. Showing an empty screen for a
        // filter the person cannot see is the worst outcome available here.
        val scope = SessionGroups.scopeOf(
            chats = listOf(chat("c1", "E:\\w", 1, "codex")),
            sessions = emptyList(),
            selected = "mimo",
        )

        assertEquals("the stale choice is dropped", null, scope.selected)

        val (chats, sessions) = SessionGroups.only(
            listOf(chat("c1", "E:\\w", 1, "codex")),
            listOf(session("s1", "E:\\w", null, "codex")),
            scope.selected,
        )
        assertEquals(1, chats.size)
        assertEquals(1, sessions.size)
    }

    @Test
    fun `a blank or absent selection is not a filter`() {
        val chats = listOf(chat("c1", "E:\\w", 1, "codex"))
        val sessions = listOf(session("s1", "E:\\w", null, "dsh"))

        for (selection in listOf(null, "", "   ")) {
            val (c, s) = SessionGroups.only(chats, sessions, selection)
            assertEquals("selection=$selection must not hide anything", 1, c.size)
            assertEquals("selection=$selection must not hide anything", 1, s.size)
        }
    }

    @Test
    fun `an engine with no name is ignored rather than offered as a blank row`() {
        val scope = SessionGroups.scopeOf(
            chats = listOf(chat("c1", "E:\\w", 1, "")),
            sessions = listOf(session("s1", "E:\\w", null, "  "), session("s2", "E:\\w", null, "codex")),
            selected = null,
        )

        assertEquals(listOf("codex"), scope.engines)
    }

    @Test
    fun `the switcher is ordered by name so it does not move between refreshes`() {
        val scope = SessionGroups.scopeOf(
            chats = emptyList(),
            sessions = listOf(
                session("s1", "E:\\w", null, "opencode"),
                session("s2", "E:\\w", null, "Codex"),
                session("s3", "E:\\w", null, "mimo"),
            ),
            selected = null,
        )

        assertEquals(listOf("Codex", "mimo", "opencode"), scope.engines)
    }

    @Test
    fun `the grouping still applies inside one kernel`() {
        // The hierarchy is kernel -> workspace -> conversation, so narrowing to a kernel
        // must leave the workspace grouping intact rather than flattening it.
        val (chats, sessions) = SessionGroups.only(
            chats = emptyList(),
            sessions = listOf(
                session("s1", "E:\\w\\alpha", "2026-10-08T02:00:00.000Z", "codex"),
                session("s2", "E:\\w\\beta", "2026-10-08T03:00:00.000Z", "codex"),
                session("s3", "E:\\w\\alpha", "2026-10-08T04:00:00.000Z", "dsh"),
            ),
            engine = "codex",
        )

        val groups = SessionGroups.build(chats, sessions, shortName)

        assertEquals(listOf("alpha", "beta"), groups.map { it.name })
        assertEquals(1, groups[0].count)
        assertEquals(1, groups[1].count)
    }

    // ---- the order switch: the rows move, the groups do not --------------------

    @Test
    fun `name order sorts the rows A to Z, ignoring case`() {
        val chats = listOf(
            chat("c1", "E:\\w\\p", 3_000, title = "zebra"),
            chat("c2", "E:\\w\\p", 1_000, title = "Apple"),
            chat("c3", "E:\\w\\p", 2_000, title = "mango"),
        )

        assertEquals(
            "the default is what the list has always done",
            listOf("c1", "c3", "c2"),
            SessionGroups.build(chats, emptyList(), shortName, SessionSort.Recent)[0].live.map { it.id },
        )
        assertEquals(
            "A/a must not sort into two separate runs",
            listOf("c2", "c3", "c1"),
            SessionGroups.build(chats, emptyList(), shortName, SessionSort.Name)[0].live.map { it.id },
        )
    }

    @Test
    fun `the switch does not reorder the groups themselves`() {
        // The product decision was "groups by name", and the toggle answers a different
        // question. If this ever changes, the newest conversation stops being the top row
        // after tapping 最近 — which is a decision, not a side effect, so it is pinned here.
        val chats = listOf(
            chat("newest", "E:\\work\\zebra", 9_000),
            chat("older", "E:\\work\\apple", 1_000),
        )

        for (mode in SessionSort.entries) {
            assertEquals(
                "groups stay A to Z in $mode too",
                listOf("apple", "zebra"),
                SessionGroups.build(chats, emptyList(), shortName, mode).map { it.name },
            )
        }
    }

    @Test
    fun `recorded sessions sort by title too, not by their timestamp`() {
        val sessions = listOf(
            session("old-but-z", "E:\\w\\p", "2026-10-01T10:00:00.000Z", title = "Zebra"),
            session("new-but-a", "E:\\w\\p", "2026-10-08T10:00:00.000Z", title = "apple"),
        )

        assertEquals(
            listOf("new-but-a", "old-but-z"),
            SessionGroups.build(emptyList(), sessions, shortName, SessionSort.Name)[0].recorded.map { it.id },
        )
    }

    @Test
    fun `a nameless conversation goes last in name order, not first`() {
        // Unknown is not "before A". Putting the rows nobody can recognise at the top
        // would push the ones they can off the first screen.
        val sessions = listOf(
            session("nameless", "E:\\w\\p", null, title = null),
            session("blank", "E:\\w\\p", null, title = "   "),
            session("named", "E:\\w\\p", null, title = "aaa"),
        )

        assertEquals(
            listOf("named", "blank", "nameless"),
            SessionGroups.build(emptyList(), sessions, shortName, SessionSort.Name)[0].recorded.map { it.id },
        )
    }

    @Test
    fun `two conversations with the same title keep a stable order`() {
        val chats = listOf(
            chat("c2", "E:\\w\\p", 1_000, title = "same"),
            chat("c1", "E:\\w\\p", 2_000, title = "same"),
        )

        assertEquals(
            "the id breaks the tie, so a redraw cannot swap two identical rows",
            listOf("c1", "c2"),
            SessionGroups.build(chats, emptyList(), shortName, SessionSort.Name)[0].live.map { it.id },
        )
    }

    @Test
    fun `the stored order is an id, and one this build does not know degrades safely`() {
        assertEquals(SessionSort.Recent, SessionSort.Default)
        assertEquals(SessionSort.Name, SessionSort.of("name"))
        assertEquals(SessionSort.Recent, SessionSort.of("recent"))
        assertEquals(SessionSort.Name, SessionSort.of("  name  "))
        assertEquals(SessionSort.Recent, SessionSort.of(null))
        assertEquals(SessionSort.Recent, SessionSort.of(""))
        assertEquals(
            "a preference written by a later build must not take the list down with it",
            SessionSort.Recent,
            SessionSort.of("by-size"),
        )
    }
}

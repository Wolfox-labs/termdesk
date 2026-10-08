package dev.termdesk.app.data

/**
 * The session list's shape: conversations grouped by the directory they work in.
 *
 * Why a module of its own: this list is drawn from two different sources — live
 * conversations the agent is holding right now, and sessions the kernels recorded
 * on disk — and the two use different clocks (`lastUsedAt` in epoch milliseconds,
 * `updatedAt` as an ISO string). Deciding how they sort against each other inside
 * a composable is how "why is this one at the top?" becomes unanswerable, and how
 * a group quietly drops out when one of its fields is missing.
 *
 * So the ordering rules live here, as functions, and `SessionGroupsTest` pins
 * them.
 *
 * The rules the product asked for:
 *
 *   - conversations are grouped by workspace, and a group can be collapsed;
 *   - inside a group, most recently used first;
 *   - groups are ordered by name, A→Z (the person scans for a project by name,
 *     not by when they last touched it);
 *   - the header shows the directory's short name, because the full path is a
 *     constant prefix nobody reads.
 *
 * A conversation with no working directory is not dropped: it lands in a group of
 * its own rather than disappearing, because "my session vanished" is worse than
 * "this one has no directory".
 */
object SessionGroups {

    /** The name a conversation with no directory is filed under. */
    const val UNKNOWN_WORKSPACE = "未指定目录"

    /**
     * One workspace's worth of the list.
     *
     * [key] is the full path (or [UNKNOWN_WORKSPACE]) and is what a collapsed set
     * stores; [name] is what the header shows.
     */
    data class Group(
        val key: String,
        val name: String,
        val live: List<ChatInfo>,
        val recorded: List<SessionInfo>,
        /** How many rows this group holds, which is what the header counts. */
        val count: Int = live.size + recorded.size,
        /** Newest of anything in the group, for keeping the newest group in view. */
        val latestAt: Long = 0L,
    )

    /**
     * Group live conversations and recorded sessions by their working directory.
     *
     * @param chats conversations the agent holds now (any order)
     * @param sessions sessions recorded on disk (any order)
     * @param nameOf how a directory path is shortened for the header; injected so
     *   grouping does not depend on the UI module that owns the real one.
     */
    fun build(
        chats: List<ChatInfo>,
        sessions: List<SessionInfo>,
        nameOf: (String) -> String,
    ): List<Group> {
        val byKey = LinkedHashMap<String, Pair<MutableList<ChatInfo>, MutableList<SessionInfo>>>()

        fun bucket(raw: String?): Pair<MutableList<ChatInfo>, MutableList<SessionInfo>> {
            val key = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: UNKNOWN_WORKSPACE
            return byKey.getOrPut(key) { mutableListOf<ChatInfo>() to mutableListOf<SessionInfo>() }
        }

        for (chat in chats) bucket(chat.cwd).first.add(chat)
        for (session in sessions) bucket(session.cwd).second.add(session)

        return byKey.map { (key, lists) ->
            val (live, recorded) = lists
            val liveSorted = live.sortedByDescending { it.lastUsedAt }
            val recordedSorted = recorded.sortedByDescending { parseIsoTime(it.updatedAt) }
            Group(
                key = key,
                name = if (key == UNKNOWN_WORKSPACE) UNKNOWN_WORKSPACE else nameOf(key),
                live = liveSorted,
                recorded = recordedSorted,
                latestAt = maxOf(
                    liveSorted.firstOrNull()?.lastUsedAt ?: 0L,
                    recordedSorted.firstOrNull()?.let { parseIsoTime(it.updatedAt) } ?: 0L,
                ),
            )
        }.sortedWith(compareBy({ it.name.lowercase() }, { it.key }))
    }

    /**
     * An ISO-8601 timestamp as epoch milliseconds, or 0 when there is nothing
     * usable.
     *
     * Deliberately the same patterns the rest of the app tries: `javax.xml.bind`
     * is not on Android, and a hand-rolled parse that silently returned 0 for a
     * format the display code handles would sort a group to the bottom for a
     * reason nobody could see.
     */
    fun parseIsoTime(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                    .apply { isLenient = true }
                    .parse(iso)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0L
    }

    /**
     * The kernels that actually have something to show, and the list narrowed to one.
     *
     * Why this exists: the hierarchy the product asked for is kernel → workspace →
     * conversation, because the flat list was unusable. The machine had 313 recorded
     * sessions across every kernel at once, so "find my OpenCode conversation" meant
     * scanning Codex and MiMo history too.
     *
     * The scope is a KERNEL and not a workspace row because that is where a kernel's
     * conversations actually differ: two kernels sharing a directory have unrelated
     * sessions, and the phone cannot run one kernel's session on another.
     *
     * Both answers are pure and pinned by `SessionGroupsTest`:
     *
     *   - [engines] lists only kernels with conversations IN THE LISTS GIVEN. Offering a
     *     kernel that would show an empty screen is the "button that cannot work" this
     *     project keeps having to remove.
     *   - [only] treats an unknown or absent selection as "everything", so a stored
     *     choice that no longer exists (the kernel's last session was deleted) shows the
     *     whole list instead of nothing at all.
     */
    data class EngineScope(
        val engines: List<String>,
        val selected: String?,
    ) {
        /** True when there is a real choice to make. One kernel is not a choice. */
        val isMeaningful: Boolean get() = engines.size > 1
    }

    fun scopeOf(
        chats: List<ChatInfo>,
        sessions: List<SessionInfo>,
        selected: String?,
    ): EngineScope {
        val engines = buildList {
            for (chat in chats) add(chat.engine)
            for (session in sessions) add(session.engine)
        }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(compareBy({ it.lowercase() }, { it }))
        val chosen = selected?.trim()?.takeIf { it.isNotEmpty() && engines.contains(it) }
        return EngineScope(engines = engines, selected = chosen)
    }

    /** The conversations belonging to [engine], or everything when it is null. */
    fun only(chats: List<ChatInfo>, sessions: List<SessionInfo>, engine: String?): Pair<List<ChatInfo>, List<SessionInfo>> {
        if (engine.isNullOrBlank()) return chats to sessions
        return chats.filter { it.engine == engine } to sessions.filter { it.engine == engine }
    }
}

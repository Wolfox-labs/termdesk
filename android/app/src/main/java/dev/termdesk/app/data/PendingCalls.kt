package dev.termdesk.app.data

/**
 * Which request an answer belongs to.
 *
 * Why this exists: a reply used to carry no request identity, so the phone matched
 * answers to questions by HOPE. `fs.list A` then `fs.list B` over a slow link can
 * come back B then A, and the screen then showed A's contents under B's heading —
 * wrong, plausible-looking, and impossible for the person to explain. The same
 * silence covered a lost request: the view simply stayed on the previous directory
 * for ever.
 *
 * The rules, kept here so they are testable rather than spread through a frame
 * handler:
 *
 *   - every request gets an id the PHONE mints, sent as `callId`; the agent echoes
 *     it. The phone is the side that knows what it is waiting for.
 *   - an answer with an unknown id is dropped, not applied. An older agent that
 *     echoes nothing cannot be trusted to answer the CURRENT question, and guessing
 *     is what produced the wrong-directory bug.
 *   - an answer that arrives after the request was given up on is also dropped: it
 *     would overwrite whatever the person has since asked for.
 *   - giving up is explicit. [expire] returns the ids that waited too long so the
 *     screen can say "没有回应" instead of looking like a slow network for ever.
 *
 * Deliberately not a generic promise library: there is no threading here, just a
 * table of outstanding ids and a clock the caller supplies, which is what makes the
 * timeout testable without sleeping.
 */
class PendingCalls(
    /** How long an unanswered request stays worth waiting for. */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private data class Entry(val kind: String, val startedAt: Long)

    private val entries = LinkedHashMap<String, Entry>()

    /** Mint an id for a request. Prefix keeps it recognisable in logs. */
    fun nextId(kind: String, now: Long = System.currentTimeMillis()): String =
        "$kind-${now.toString(36)}-${counter++}"

    /** Record that a request is outstanding. */
    fun track(id: String, kind: String, now: Long = System.currentTimeMillis()) {
        entries[id] = Entry(kind, now)
    }

    /**
     * Record a request that REPLACES any outstanding one of the same kind.
     *
     * This is the fix for the wrong-directory bug, and it is a different rule from
     * [track] on purpose. Some answers land in a single slot on screen — one listing,
     * one open file — so when a second question of that kind is asked, the first
     * one's answer is no longer wanted: navigating A then B must not let A's late
     * reply paint over B. Other kinds (a search, a file read that opens its own
     * viewer) can legitimately have several in flight, and those use [track].
     */
    fun trackLatest(id: String, kind: String, now: Long = System.currentTimeMillis()) {
        entries.entries.removeIf { it.value.kind == kind }
        entries[id] = Entry(kind, now)
    }

    /**
     * Claim the answer for [id].
     *
     * True only if that request is still outstanding. False means the answer must
     * be ignored: either it belongs to a request that was already answered or given
     * up on, or it belongs to an agent that does not echo ids at all.
     */
    fun claim(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        return entries.remove(id) != null
    }

    /** Whether anything is still outstanding, for a spinner that does not lie. */
    fun isWaiting(): Boolean = entries.isNotEmpty()

    /**
     * Drop requests that waited too long, returning their kinds.
     *
     * Returned rather than silently dropped: a timeout the person cannot see is
     * indistinguishable from a screen that has stopped working.
     */
    fun expire(now: Long = System.currentTimeMillis()): List<String> {
        // Collected in one pass: the table is small, but the deadline comparison is
        // the same for every entry and computing it twice invites the two results
        // to disagree.
        val dead = entries.filterValues { now - it.startedAt > timeoutMs }
        if (dead.isEmpty()) return emptyList()
        dead.keys.forEach { entries.remove(it) }
        return dead.values.map { it.kind }.distinct()
    }

    /** Forget everything: used when the link drops, where every answer is now moot. */
    fun clear() = entries.clear()

    /** For tests and for the thinking-in-progress case. */
    fun size(): Int = entries.size

    companion object {
        /**
         * Long enough for a slow link to a big directory, short enough that the
         * person is still looking at the screen when the answer would have arrived.
         */
        const val DEFAULT_TIMEOUT_MS = 20_000L

        private var counter = 0
    }
}

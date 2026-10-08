package dev.termdesk.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the person sent while the link was down.
 *
 * Why: a message typed on a train and sent during a tunnel was simply dropped —
 * `sendFrame` reported "操作未发送" and the words were gone. The product asked for
 * the opposite (断线时输入不丢，恢复后按序补发，界面显示"待发 N 条").
 *
 * What is queued and what is not is the important decision here. Only things the
 * PERSON authored are queued:
 *
 *   - a chat message is queued, because it is unrepeatable: nobody can retype a
 *     thought they no longer have;
 *   - a read (a listing, a status, a scan) is not queued, because it is stale the
 *     moment it lands and the screen asks again by itself;
 *   - anything that changes the PC is not queued silently — deleting a file or
 *     killing a process during an outage must fail out loud rather than happen
 *     minutes later with nobody watching.
 *
 * That is why this class has no `post(frame)` that accepts anything: the call site
 * decides, and the honest default for everything else stays what it is now.
 *
 * Persistence is a callback rather than a file path so the ordering and delivery
 * rules can be tested without Android. `AgentClient` hands it a file under
 * `filesDir`, which is what makes a queued message survive a restart.
 */
class PendingSends(
    private val now: () -> Long = { System.currentTimeMillis() },
    /** At most this many are kept; the oldest go first when it overflows. */
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
    /** How long an unsent message is still worth sending. */
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val onChanged: (List<Item>) -> Unit = {},
) {

    /** One message waiting for a link. */
    data class Item(
        val id: String,
        val chatId: String,
        val text: String,
        val queuedAt: Long,
    )

    private val items = ArrayDeque<Item>()

    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()

    /** A snapshot in send order, for the UI and for replay. */
    fun snapshot(): List<Item> = items.toList()

    /**
     * Queue one message. Returns the item so the caller can show it immediately.
     *
     * `id` is supplied by the caller because it is also the `requestId` that goes
     * on the wire: the agent echoes it back, and that echo — not a timer — is what
     * says the message actually arrived.
     */
    fun enqueue(id: String, chatId: String, text: String): Item {
        val item = Item(id = id, chatId = chatId, text = text, queuedAt = now())
        items.addLast(item)
        while (items.size > maxItems) items.removeFirst()
        publish()
        return item
    }

    /** The next message to send, or null when there is nothing to send. */
    fun peek(): Item? = items.firstOrNull()

    /**
     * The agent confirmed this id arrived; drop it.
     *
     * Called with the `requestId` the agent echoed, so an old confirmation cannot
     * clear a newer message that happens to be at the front.
     */
    fun acknowledge(id: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        items.removeAt(index)
        publish()
        return true
    }

    /**
     * Drop what is no longer worth sending.
     *
     * A message queued before a flight and replayed after landing would land in a
     * conversation whose turn is long over, so it expires instead. Returns how
     * many were dropped, because the screen has to say that something was lost
     * rather than quietly sending three of five.
     */
    fun pruneExpired(): Int {
        val cutoff = now() - ttlMs
        val before = items.size
        items.removeAll { it.queuedAt < cutoff }
        val dropped = before - items.size
        if (dropped > 0) publish()
        return dropped
    }

    /** Forget everything (the person chose to, or the pairing changed). */
    fun clear() {
        if (items.isEmpty()) return
        items.clear()
        publish()
    }

    /** Replace the queue with what was on disk, dropping anything expired. */
    fun restore(saved: List<Item>) {
        items.clear()
        val cutoff = now() - ttlMs
        for (item in saved) {
            if (item.queuedAt < cutoff) continue
            if (item.chatId.isBlank() || item.text.isBlank()) continue
            items.addLast(item)
        }
        while (items.size > maxItems) items.removeFirst()
        publish()
    }

    private fun publish() = onChanged(items.toList())

    companion object {
        /**
         * A queue is for "the link dropped", not for "I am offline for a week":
         * past this many messages the person is better served by being told the
         * link is down than by a silent backlog.
         */
        const val DEFAULT_MAX_ITEMS = 20

        /** Beyond this, a message is more likely to confuse than to help. */
        const val DEFAULT_TTL_MS = 30 * 60 * 1000L

        /** The shape written to disk; kept next to the parser that reads it. */
        fun toJson(items: List<Item>): String {
            val array = JSONArray()
            for (item in items) {
                array.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("chatId", item.chatId)
                        .put("text", item.text)
                        .put("queuedAt", item.queuedAt),
                )
            }
            return JSONObject().put("items", array).toString()
        }

        fun fromJson(raw: String?): List<Item> {
            if (raw.isNullOrBlank()) return emptyList()
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
            val array = root.optJSONArray("items") ?: return emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                out.add(
                    Item(
                        id = id,
                        chatId = o.optString("chatId"),
                        text = o.optString("text"),
                        queuedAt = o.optLong("queuedAt", 0L),
                    ),
                )
            }
            return out
        }
    }
}

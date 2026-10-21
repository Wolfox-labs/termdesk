package dev.termdesk.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What survives a dropped link, and what deliberately does not.
 *
 * The rule being pinned: only what the PERSON wrote is queued. A read is stale by
 * the time it lands, and anything that changes the PC must fail out loud rather
 * than happen minutes later with nobody watching.
 */
class PendingSendsTest {

    private var clock = 1_000_000L
    private val changes = mutableListOf<List<PendingSends.Item>>()

    private fun queue(max: Int = PendingSends.DEFAULT_MAX_ITEMS, ttl: Long = PendingSends.DEFAULT_TTL_MS) =
        PendingSends(now = { clock }, maxItems = max, ttlMs = ttl, onChanged = { changes += it })

    @Test
    fun `a queued message comes back in the order it was written`() {
        val q = queue()
        q.enqueue("r1", "c1", "first")
        q.enqueue("r2", "c1", "second")
        q.enqueue("r3", "c1", "third")

        assertEquals(listOf("first", "second", "third"), q.snapshot().map { it.text })
        assertEquals("the oldest is the next to send", "r1", q.peek()?.id)
        assertEquals(3, q.size)
    }

    @Test
    fun `an acknowledged message leaves the queue, and only that one`() {
        val q = queue()
        q.enqueue("r1", "c1", "first")
        q.enqueue("r2", "c1", "second")

        assertTrue(q.acknowledge("r1"))
        assertEquals(listOf("r2"), q.snapshot().map { it.id })
        assertFalse("a second acknowledgement of the same id is not an error, it is a no-op", q.acknowledge("r1"))
        assertFalse("an id that was never queued changes nothing", q.acknowledge("nope"))
    }

    @Test
    fun `an old confirmation cannot clear a newer message at the front`() {
        // The failure this prevents: acknowledging by position instead of by id
        // would drop whatever happens to be first now, which is a different message.
        val q = queue()
        q.enqueue("r1", "c1", "first")
        q.acknowledge("r1")
        q.enqueue("r2", "c1", "second")

        assertFalse(q.acknowledge("r1"))
        assertEquals(listOf("r2"), q.snapshot().map { it.id })
    }

    @Test
    fun `the queue is bounded, and it is the oldest that go`() {
        val q = queue(max = 3)
        for (i in 1..5) q.enqueue("r$i", "c1", "m$i")

        assertEquals(3, q.size)
        assertEquals(
            "the newest survive: the person's last words matter most",
            listOf("m3", "m4", "m5"),
            q.snapshot().map { it.text },
        )
    }

    @Test
    fun `a message queued long ago expires instead of landing in a finished conversation`() {
        val q = queue(ttl = 60_000)
        q.enqueue("old", "c1", "before the flight")
        clock += 120_000
        q.enqueue("new", "c1", "after landing")

        assertEquals("one was dropped, and the caller is told", 1, q.pruneExpired())
        assertEquals(listOf("new"), q.snapshot().map { it.id })
        assertEquals("nothing else expires the second time", 0, q.pruneExpired())
    }

    @Test
    fun `pruning an empty queue is not an event`() {
        val q = queue()
        assertEquals(0, q.pruneExpired())
        assertTrue("and it does not notify the UI about nothing", changes.isEmpty())
    }

    @Test
    fun `clearing empties it once, and clearing an empty queue says nothing`() {
        val q = queue()
        q.enqueue("r1", "c1", "first")
        q.clear()
        assertTrue(q.isEmpty)
        val notifications = changes.size
        q.clear()
        assertEquals("a second clear is not a change", notifications, changes.size)
    }

    @Test
    fun `the queue survives a restart, and expired messages do not come back`() {
        val q = queue(ttl = 60_000)
        q.enqueue("keep", "c1", "still worth sending")
        clock += 10_000
        q.enqueue("stale", "c1", "too old to send")
        val saved = q.snapshot()

        // A restart, later: the whole saved list is offered back to a fresh queue.
        clock += 120_000
        val reopened = queue(ttl = 60_000)
        reopened.restore(saved)

        assertTrue("everything saved was older than the TTL by now", reopened.isEmpty)
    }

    @Test
    fun `a restart keeps what is still fresh, in order`() {
        val q = queue(ttl = PendingSends.DEFAULT_TTL_MS)
        q.enqueue("r1", "c1", "first")
        q.enqueue("r2", "c1", "second")

        val reopened = queue(ttl = PendingSends.DEFAULT_TTL_MS)
        reopened.restore(q.snapshot())

        assertEquals(listOf("r1", "r2"), reopened.snapshot().map { it.id })
    }

    @Test
    fun `a restored item without a chat or text is dropped rather than replayed`() {
        // A half-written file must not become a message sent to nowhere.
        val q = queue()
        q.restore(
            listOf(
                PendingSends.Item("ok", "c1", "hello", clock),
                PendingSends.Item("noChat", "", "hello", clock),
                PendingSends.Item("noText", "c1", "", clock),
            ),
        )

        assertEquals(listOf("ok"), q.snapshot().map { it.id })
    }

    @Test
    fun `the queue round-trips through json, which is what makes it survive a restart`() {
        val q = queue()
        q.enqueue("r1", "c1", "一条中文消息")
        q.enqueue("r2", "c2", "second")

        val restored = PendingSends.fromJson(PendingSends.toJson(q.snapshot()))

        assertEquals(2, restored.size)
        assertEquals("一条中文消息", restored[0].text)
        assertEquals("c2", restored[1].chatId)
        assertEquals(q.snapshot()[0].queuedAt, restored[0].queuedAt)
    }

    @Test
    fun `unreadable saved state is an empty queue, not a crash`() {
        assertEquals(emptyList<PendingSends.Item>(), PendingSends.fromJson(null))
        assertEquals(emptyList<PendingSends.Item>(), PendingSends.fromJson(""))
        assertEquals(emptyList<PendingSends.Item>(), PendingSends.fromJson("not json"))
        assertEquals(emptyList<PendingSends.Item>(), PendingSends.fromJson("{}"))
        assertEquals(emptyList<PendingSends.Item>(), PendingSends.fromJson("""{"items":"nope"}"""))
    }

    @Test
    fun `an empty queue has nothing to peek at`() {
        assertNull(queue().peek())
    }

    /**
     * 这条钉的是一个真机上抓到的缺陷：队列**只写不读**。
     *
     * 原来"读回来"写在调用方的 `init` 块里，而那个块写在属性前面——那一刻队列对象
     * 还是 null，异常被 `runCatching` 吞掉，于是文件里的消息永远发不出去、也永远不清
     *（30 分钟 TTL 从不执行）。现在读盘是队列**构造**的一部分：只要构造了，存档就一定会
     * 被读、被过滤、并立刻回写一次。
     */
    @Test
    fun `constructing the queue reads the archive back and rewrites it once`() {
        val archive = listOf(
            PendingSends.Item("stale", "c1", "too old to send", clock - PendingSends.DEFAULT_TTL_MS - 1_000),
            PendingSends.Item("fresh", "c1", "still worth sending", clock - 1_000),
        )
        val q = PendingSends(now = { clock }, load = { archive }, onChanged = { changes += it })

        assertEquals("过期的丢掉，新鲜的留下", listOf("fresh"), q.snapshot().map { it.id })
        assertEquals(
            "读回来的结果必须回写一次——不然过期项会永远躺在文件里",
            1,
            changes.size,
        )
        assertEquals(listOf("fresh"), changes.last().map { it.id })
    }

    @Test
    fun `no archive at all is an empty queue that says nothing`() {
        val q = PendingSends(now = { clock }, load = { null }, onChanged = { changes += it })

        assertTrue(q.isEmpty)
        assertTrue("没有存档就不是一次变更", changes.isEmpty())
    }

    @Test
    fun `a restored message keeps the chat it was written to`() {
        // 恢复出来的每一条都要带着自己的 chatId：重放时正是靠它找到那个会话。
        val archive = listOf(
            PendingSends.Item("a", "c-one", "first", clock),
            PendingSends.Item("b", "c-two", "second", clock),
        )
        val q = PendingSends(now = { clock }, load = { archive }, onChanged = {})

        assertEquals(listOf("c-one", "c-two"), q.snapshot().map { it.chatId })
    }
}

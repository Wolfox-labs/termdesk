package dev.termdesk.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which answer belongs to which question.
 *
 * The bug these prevent was observed by reasoning about the code, not by a device:
 * `fs.list A` then `fs.list B` over a slow link can come back in the other order,
 * and with no request identity the screen showed A's contents under B's name.
 */
class PendingCallsTest {

    private val t0 = 1_000_000L

    @Test
    fun `ids are unique, so two requests cannot be confused`() {
        val calls = PendingCalls()

        val ids = (1..50).map { calls.nextId("fs", t0) }

        assertEquals("every id must differ", ids.size, ids.toSet().size)
    }

    @Test
    fun `an id names the kind, so a log line says what was being asked`() {
        val calls = PendingCalls()

        assertTrue(calls.nextId("fs.list", t0).startsWith("fs.list-"))
    }

    @Test
    fun `the answer to the outstanding request is claimed`() {
        val calls = PendingCalls()
        val a = calls.nextId("fs.list", t0)
        calls.track(a, "fs.list", t0)

        assertTrue(calls.claim(a))
        assertEquals("claiming consumes it", 0, calls.size())
    }

    @Test
    fun `an answer can only be claimed once`() {
        val calls = PendingCalls()
        val a = calls.nextId("fs.list", t0)
        calls.track(a, "fs.list", t0)

        assertTrue(calls.claim(a))
        assertFalse("a duplicate reply must not be applied twice", calls.claim(a))
    }

    @Test
    fun `an earlier reply arriving late is refused, which is the wrong-directory bug`() {
        // Navigate into A, then quickly into B. B's listing comes back first, then
        // A's stale one. Applying A here is exactly what showed the wrong directory.
        val calls = PendingCalls()
        val a = calls.nextId("fs.list", t0)
        val b = calls.nextId("fs.list", t0)
        calls.trackLatest(a, "fs.list", t0)
        calls.trackLatest(b, "fs.list", t0)

        assertTrue("B is the live question", calls.claim(b))
        assertTrue(
            "A was superseded the moment B was asked, so its answer must be dropped",
            calls.claim(a).not(),
        )
    }

    @Test
    fun `asking again ends the wait rather than leaving the spinner on an abandoned request`() {
        val calls = PendingCalls()
        calls.trackLatest(calls.nextId("fs.list", t0), "fs.list", t0)
        calls.trackLatest(calls.nextId("fs.list", t0), "fs.list", t0)

        assertEquals("the abandoned request is not still outstanding", 1, calls.size())
    }

    @Test
    fun `two searches in flight do not cancel each other`() {
        // A search is not a single-valued slot: both answers are wanted, so this
        // must NOT behave like trackLatest.
        val calls = PendingCalls()
        val first = calls.nextId("fs.search", t0)
        val second = calls.nextId("fs.search", t0)
        calls.track(first, "fs.search", t0)
        calls.track(second, "fs.search", t0)

        assertEquals(2, calls.size())
        assertTrue(calls.claim(first))
        assertTrue(calls.claim(second))
    }

    @Test
    fun `superseding one kind leaves other kinds alone`() {
        val calls = PendingCalls()
        val read = calls.nextId("fs.read", t0)
        calls.track(read, "fs.read", t0)
        calls.trackLatest(calls.nextId("fs.list", t0), "fs.list", t0)
        calls.trackLatest(calls.nextId("fs.list", t0), "fs.list", t0)

        assertTrue("a listing question must not cancel a file being read", calls.claim(read))
    }

    @Test
    fun `an id nobody asked for is refused`() {
        val calls = PendingCalls()
        calls.track(calls.nextId("fs.list", t0), "fs.list", t0)

        assertFalse(calls.claim("fs.list-made-up"))
        assertFalse("an agent that echoes nothing must not be trusted", calls.claim(null))
        assertFalse(calls.claim(""))
    }

    @Test
    fun `waiting is visible, and ends when the answer lands`() {
        val calls = PendingCalls()
        val a = calls.nextId("fs.list", t0)

        assertFalse("nothing asked yet", calls.isWaiting())
        calls.track(a, "fs.list", t0)
        assertTrue(calls.isWaiting())
        calls.claim(a)
        assertFalse("a spinner that outlives the answer is a lie", calls.isWaiting())
    }

    @Test
    fun `a request that waited too long is reported, not silently forgotten`() {
        val calls = PendingCalls(timeoutMs = 5_000)
        calls.track(calls.nextId("fs.list", t0), "fs.list", t0)

        assertEquals("not yet", emptyList<String>(), calls.expire(t0 + 4_000))
        assertEquals(listOf("fs.list"), calls.expire(t0 + 6_000))
        assertFalse("and it stops being waited on", calls.isWaiting())
    }

    @Test
    fun `an expired request cannot be answered afterwards`() {
        val calls = PendingCalls(timeoutMs = 5_000)
        val a = calls.nextId("fs.list", t0)
        calls.track(a, "fs.list", t0)
        calls.expire(t0 + 6_000)

        assertFalse(
            "an answer that arrives after the person gave up would overwrite what they asked for next",
            calls.claim(a),
        )
    }

    @Test
    fun `several kinds expiring together are each reported once`() {
        val calls = PendingCalls(timeoutMs = 1_000)
        calls.track(calls.nextId("fs.list", t0), "fs.list", t0)
        calls.track(calls.nextId("fs.read", t0), "fs.read", t0)
        calls.track(calls.nextId("fs.list", t0), "fs.list", t0)

        val expired = calls.expire(t0 + 2_000)

        assertEquals(2, expired.size)
        assertTrue(expired.contains("fs.list"))
        assertTrue(expired.contains("fs.read"))
    }

    @Test
    fun `a dropped link forgets everything`() {
        val calls = PendingCalls()
        calls.track(calls.nextId("fs.list", t0), "fs.list", t0)
        calls.track(calls.nextId("fs.read", t0), "fs.read", t0)

        calls.clear()

        assertEquals("answers cannot survive the socket that would carry them", 0, calls.size())
        assertFalse(calls.isWaiting())
    }
}

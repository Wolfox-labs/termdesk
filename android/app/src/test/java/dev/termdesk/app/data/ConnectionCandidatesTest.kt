package dev.termdesk.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which address is tried, and which one is remembered.
 *
 * The bug these prevent is a phone that falls back to the LAN and then, on the
 * next reconnect, goes back to dialling the dead relay it was paired with — which
 * looks exactly like "the fallback does not work".
 */
class ConnectionCandidatesTest {

    private val relay = "wss://term.example/ws"
    private val lan = "ws://192.168.248.180:7420"
    private val tailscale = "ws://100.90.192.5:7420"
    private val loopback = "ws://127.0.0.1:7420"

    @Test
    fun `the paired address comes first, then the fallbacks in the order given`() {
        val list = ConnectionCandidates.list(relay, listOf(lan, tailscale, loopback))

        assertEquals(listOf(relay, lan, tailscale, loopback), list)
    }

    @Test
    fun `a repeated address is kept once`() {
        val list = ConnectionCandidates.list(relay, listOf(relay, lan, lan))

        assertEquals(listOf(relay, lan), list)
    }

    @Test
    fun `blank and non-websocket entries are dropped rather than dialled`() {
        // An empty url builds a request that fails with a message about the url
        // syntax, which tells the person nothing about their connection.
        val list = ConnectionCandidates.list(relay, listOf("", "   ", "http://nope", null))

        assertEquals(listOf(relay), list)
        assertEquals(emptyList<String>(), ConnectionCandidates.list(null, null))
        assertEquals(emptyList<String>(), ConnectionCandidates.list("", listOf("not-a-url")))
    }

    @Test
    fun `the address that worked is the one reused`() {
        val list = ConnectionCandidates.list(relay, listOf(lan, tailscale))

        assertEquals(
            "having fallen back to the LAN, it must stay on the LAN",
            lan,
            ConnectionCandidates.current(list, lan),
        )
    }

    @Test
    fun `with nothing remembered it starts at the paired address`() {
        val list = ConnectionCandidates.list(relay, listOf(lan))

        assertEquals(relay, ConnectionCandidates.current(list, null))
        assertNull(ConnectionCandidates.current(emptyList(), lan))
    }

    @Test
    fun `a remembered address that is no longer offered falls back to the first`() {
        // The list changes between connections: a re-pairing replaces the relay
        // address, and an index would then point at a different computer.
        val list = ConnectionCandidates.list("wss://new.example", listOf(lan))

        assertEquals("wss://new.example", ConnectionCandidates.current(list, relay))
    }

    @Test
    fun `a failure moves to the next address, and wraps around`() {
        val list = listOf(relay, lan, tailscale)

        assertEquals(lan, ConnectionCandidates.after(list, relay))
        assertEquals(tailscale, ConnectionCandidates.after(list, lan))
        assertEquals("wrapping keeps trying instead of parking on the last one", relay, ConnectionCandidates.after(list, tailscale))
    }

    @Test
    fun `one address means retrying that one, not rotating to nothing`() {
        val list = listOf(relay)

        assertEquals(relay, ConnectionCandidates.after(list, relay))
        assertEquals(relay, ConnectionCandidates.after(list, null))
        assertNull(ConnectionCandidates.after(emptyList(), relay))
    }

    @Test
    fun `an unknown failure address starts the rotation from the beginning`() {
        val list = listOf(relay, lan)

        assertEquals(relay, ConnectionCandidates.after(list, "ws://stranger:1"))
        assertEquals(relay, ConnectionCandidates.after(list, null))
    }

    @Test
    fun `the banner explains the switch, and stays quiet when there is nothing to switch to`() {
        val described = ConnectionCandidates.describe(listOf(relay, lan), relay)

        assertNotNull("having a fallback is worth saying", described)
        val sentence = described ?: ""
        assertTrue("the sentence has to name the address being tried", sentence.contains("192.168.248.180"))
        assertTrue("and the one it is leaving", sentence.contains("term.example"))
        assertNull(ConnectionCandidates.describe(listOf(relay), relay))
        assertNull(ConnectionCandidates.describe(emptyList(), null))
    }

    @Test
    fun `a host is shown without its scheme or path`() {
        assertEquals("term.example", ConnectionCandidates.hostOf(relay))
        assertEquals("192.168.248.180:7420", ConnectionCandidates.hostOf(lan))
        assertEquals("nothing", ConnectionCandidates.hostOf("nothing"))
    }
}

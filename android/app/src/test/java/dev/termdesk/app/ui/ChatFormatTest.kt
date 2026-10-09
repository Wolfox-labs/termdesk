package dev.termdesk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.TimeZone

/**
 * Reading a timestamp that came from the computer.
 *
 * This has one job and it got it wrong in a way that mattered: a trailing `Z` means UTC, but in
 * a `SimpleDateFormat` pattern `'Z'` is a quoted *literal*, so `2026-10-09T12:34:56Z` was parsed
 * as 12:34 in the phone's own timezone. Eight hours early here — which is how the conversation
 * the owner had open on the computer a minute earlier showed up in the phone's list as one from
 * this afternoon, and was overlooked.
 */
class ChatFormatTest {

    @Test
    fun `a trailing Z means UTC, not local time`() {
        val utc = parseIsoDate("2026-10-09T12:34:56Z")
        val explicit = parseIsoDate("2026-10-09T12:34:56+00:00")
        assertNotNull(utc)
        assertNotNull(explicit)
        assertEquals("Z and +00:00 are the same instant", explicit!!.time, utc!!.time)
    }

    @Test
    fun `a stamp with no zone is read as the writer's clock`() {
        // The engines write local ISO strings as well as UTC ones. The difference between a
        // zoned and an unzoned reading has to be exactly the machine's offset at that instant;
        // if `Z` were treated as a literal, that difference would be zero.
        val utc = parseIsoDate("2026-10-09T12:34:56Z")!!
        val naive = parseIsoDate("2026-10-09T12:34:56")!!
        val expected = TimeZone.getDefault().getOffset(utc.time).toLong()
        // Local 12:34 is `offset` earlier in absolute terms than 12:34 UTC (east of Greenwich).
        // Same type on both sides: Integer vs Long is "not equal" to JUnit even when the numbers
        // match, which is a test that fails for a reason nobody can see in the message.
        assertEquals("an unzoned stamp is local time", -expected, naive.time - utc.time)
    }

    @Test
    fun `milliseconds and offsets both survive`() {
        val withMillis = parseIsoDate("2026-10-09T12:34:56.789Z")
        val without = parseIsoDate("2026-10-09T12:34:56Z")
        assertEquals(789, withMillis!!.time - without!!.time)
    }
}

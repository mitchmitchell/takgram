package org.takgram.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases ported from the Python package's tests/test_core.py: type names, MGRS, splitter, queue. */
class PortedPythonTest {
    @Test
    fun describesTypes() {
        val cases = mapOf(
            "a-f-G-U-C" to "Friendly ground combat unit",
            "a-h-G-U-C-A" to "Hostile ground armor",
            "a-u-G" to "Unknown ground track",
            "a-n-A-C-F" to "Neutral air civil fixed-wing",
            "a-f-A-M-F-Q" to "Friendly air drone",
            "b-m-p-s-m" to "Spot marker",
            "b-a-o-tbl" to "Emergency: 911 alert",
            "b-r-f-h-c" to "CASEVAC request",
            "x-y-z" to "Object (x-y-z)",
        )
        for ((code, text) in cases) assertEquals(code, text, CotTypes.describe(code))
        assertEquals('h', CotTypes.frame("a-h-G"))
        assertEquals('u', CotTypes.frame("b-m-p-s-m"))
    }

    @Test
    fun mgrsKnownPointsAndPolarGap() {
        // Washington Monument, a commonly published MGRS reference: 18S UJ 2337 0651 (10 m precision).
        val g = Mgrs.of(38.8895, -77.0353)
        assertTrue(g, g.startsWith("18S UJ 23"))
        assertEquals(13, g.replace(" ", "").length)
        assertEquals("", Mgrs.of(88.0, 10.0))
        assertEquals("", Mgrs.of(-81.0, 10.0))
        // ATAK-CIV showed 14R PU 20913 49062 for 30.2672, -97.7430 on the emulator (1 m precision).
        assertEquals("14R PU 2091 4906", Mgrs.of(30.2672, -97.7430))
        // Southern hemisphere and an even zone: the Sydney Opera House is in 56H LH.
        val s = Mgrs.of(-33.8568, 151.2153)
        assertTrue(s, s.startsWith("56H LH 34"))
        // South-west Norway is forced into zone 32.
        assertTrue(Mgrs.of(60.39, 5.32).startsWith("32V "))
    }

    @Test
    fun streamSplitterHandlesFragmentsDeclarationsAndGarbage() {
        val a = "<?xml version=\"1.0\"?>\n<event uid=\"a\" type=\"a-f-G\"><point lat=\"1\" lon=\"1\"/></event>"
        val b = "<event uid=\"m-1\" type=\"a-h-G\"><point lat=\"2\" lon=\"2\"/><detail/></event>"
        val data = (a + "junk" + b).toByteArray()
        val s = StreamSplitter()
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < data.size) {
            val n = minOf(37, data.size - i)
            out += s.feed(data.copyOfRange(i, i + n))
            i += n
        }
        assertEquals(2, out.size)
        assertTrue(out.all { String(it).startsWith("<event") && String(it).endsWith("</event>") })
        assertEquals("m-1", CotParse.parse(out[1]).uid)

        val flood = StreamSplitter(maxBytes = 1000)
        assertTrue(flood.feed(ByteArray(5000) { 'x'.code.toByte() }).isEmpty())
        assertEquals(1, flood.feed(b.toByteArray()).size)
    }

    @Test
    fun queueCoalescesAndOrdersByPriority() {
        val q = OutQueue<String>(chatIntervalMs = 0, globalIntervalMs = 0)
        q.submit(1, "a", "a-old")
        q.submit(1, "b", "b")
        q.submit(1, "a", "a-new")
        q.submit(1, "urgent", "urgent", priority = 0)
        val ran = generateSequence { q.take(0)?.payload }.toList()
        assertEquals(listOf("urgent", "a-new", "b"), ran)
        assertEquals(0, q.size)
    }

    @Test
    fun queueRespectsIntervalsAndRetriesAfterRateLimit() {
        val q = OutQueue<String>(chatIntervalMs = 3_000, globalIntervalMs = 50)
        q.submit(1, null, "one")
        q.submit(1, null, "two")
        q.submit(2, null, "other chat")
        assertEquals("one", q.take(0)!!.payload)
        assertNull("global interval", q.take(10))
        assertEquals("other chat", q.take(60)!!.payload)
        assertNull("chat 1 must wait", q.take(2_000))
        assertEquals(3_000L, q.nextReadyMs(2_000))

        val second = q.take(3_000)!!
        q.rateLimited(second, 3_000, 10_000)
        assertNull(q.take(9_000))
        assertEquals("two", q.take(13_500)!!.payload)
        assertNull(q.nextReadyMs(20_000))
    }

    @Test
    fun queueKeepsANewerEditOverARetriedOneAndGivesUpAfterMaxTries() {
        val q = OutQueue<String>(chatIntervalMs = 0, globalIntervalMs = 0, maxTries = 3)
        q.submit(1, "m-1", "v1")
        val v1 = q.take(0)!!
        q.submit(1, "m-1", "v2")
        q.rateLimited(v1, 0, 1_000)
        assertEquals("v2", q.take(5_000)!!.payload)
        assertEquals(0, q.size)

        q.submit(1, "x", "flaky")
        assertTrue(q.failed(q.take(10_000)!!, 10_000))
        assertTrue(q.failed(q.take(100_000)!!, 100_000))
        assertFalse(q.failed(q.take(200_000)!!, 200_000))
        assertEquals(0, q.size)
    }
}

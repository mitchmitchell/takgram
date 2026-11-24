package org.takgram.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CotBuildTest {
    private val t0 = Instant.parse("2026-11-22T10:00:00Z")

    @Test
    fun markerHasExpectedShape() {
        val x = CotBuild.marker("m-1", "a-h-G-U-C-A", "ALPHA", 30.3, -97.7, "seen", now = t0)
        assertTrue(x.startsWith("<event version=\"2.0\" uid=\"m-1\" type=\"a-h-G-U-C-A\" how=\"h-g-i-g-o\""))
        assertTrue(x.contains("time=\"2026-11-22T10:00:00.000Z\""))
        assertTrue(x.contains("stale=\"2026-11-22T11:00:00.000Z\""))
        assertTrue(x.contains("<point lat=\"30.3000000\" lon=\"-97.7000000\" hae=\"9999999.0\""))
        assertTrue(x.contains("<contact callsign=\"ALPHA\"/><remarks>seen</remarks>"))
        assertTrue(x.endsWith("</detail></event>"))
    }

    @Test
    fun hostileTextIsEscapedNotInjected() {
        val x = CotBuild.marker("u\"><x", "a-f-G", "<b>&", 1.0, 2.0, "</remarks><detail>", now = t0)
        assertFalse(x.contains("<x"))
        assertFalse(x.contains("</remarks><detail>"))
        assertTrue(x.contains("uid=\"u&quot;&gt;&lt;x\""))
        assertTrue(x.contains("callsign=\"&lt;b&gt;&amp;\""))
    }

    @Test
    fun controlCharactersAreDropped() {
        assertEquals("ab\nc", CotBuild.escape("a\u0000b\nc\u0007"))
    }

    @Test
    fun rejectsOutOfRangeCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            CotBuild.marker("m", "a-f-G", "X", 95.0, 0.0, now = t0)
        }
    }

    @Test
    fun usesDotDecimalRegardlessOfLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val x = CotBuild.marker("m", "a-f-G", "X", 1.5, 2.25, now = t0)
            assertTrue(x.contains("lat=\"1.5000000\" lon=\"2.2500000\""))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}

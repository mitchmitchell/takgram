package org.takgram.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Parser cases ported from the Python package's tests/test_core.py, plus hostile-input cases. */
class CotParseTest {
    private val now = Instant.parse("2026-11-23T12:00:00Z")

    private val pli = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <event version="2.0" uid="ANDROID-1" type="a-f-G-U-C" how="m-g" time="2026-11-23T12:00:00.000Z"
          start="2026-11-23T12:00:00.000Z" stale="2026-11-23T12:05:00.000Z">
        <point lat="30.26715" lon="-97.74306" hae="149.2" ce="8.5" le="9999999.0"/>
        <detail><takv os="34" version="5.6.0.12" device="PIXEL" platform="ATAK-CIV"/>
        <contact endpoint="192.168.1.20:4242:tcp" callsign="ALPHA-2"/><uid Droid="ALPHA-2"/>
        <__group role="Team Lead" name="Cyan"/><status battery="88"/>
        <track course="45.0" speed="1.4"/></detail></event>""".toByteArray()

    private fun marker(remarks: String = "Seen &lt;moving&gt; north") =
        """<event version="2.0" uid="m-1" type="a-h-G-U-C-A" how="h-g-i-g-o" time="2026-11-23T12:00:00.000Z"
          start="2026-11-23T12:00:00.000Z" stale="2026-11-23T13:00:00.000Z">
        <point lat="30.3" lon="-97.7" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
        <detail><contact callsign="Tank 1"/><remarks>$remarks</remarks>
        <color argb="-65536"/><usericon iconsetpath="COT_MAPPING_2525B/a-h/a-h-G"/></detail></event>""".toByteArray()

    @Test
    fun parsesPositionReport() {
        val ev = CotParse.parse(pli, now)
        assertEquals("ANDROID-1", ev.uid)
        assertEquals("ALPHA-2", ev.callsign)
        assertEquals("Cyan", ev.team)
        assertEquals("Team Lead", ev.role)
        assertTrue(ev.isTakClient)
        assertEquals(30.26715, ev.lat, 1e-9)
        assertEquals(149.2, ev.hae!!, 1e-9)
        assertEquals(8.5, ev.ce!!, 1e-9)
        assertEquals(45.0, ev.course!!, 1e-9)
    }

    @Test
    fun parsesMarkerAndTreatsSentinelsAsUnknown() {
        val ev = CotParse.parse(marker(), now)
        assertEquals("m-1", ev.uid)
        assertNull(ev.hae)
        assertNull(ev.ce)
        assertEquals("Seen <moving> north", ev.remarks)
        assertEquals(-65536, ev.colorArgb)
        assertFalse(ev.isTakClient)
    }

    @Test
    fun rejectsBadInput() {
        val bad = listOf(
            "",
            "<notevent/>",
            "<event uid='a'/>",
            "<event uid=\"a\" type=\"a-f-G\"><point lat=\"95\" lon=\"0\"/></event>",
            "<event uid=\"a\" type=\"a-f-G\"><point lat=\"NaN\" lon=\"0\"/></event>",
            "<event uid=\"a\" type=\"a f\"><point lat=\"1\" lon=\"1\"/></event>",
            "<event uid=\"a\" type=\"a-f-G\"></event>",
            "<event uid=\"a\" type=\"a-f-G\"><point lat=\"1\" lon=\"1\"/>",
            "<!DOCTYPE x [<!ENTITY a \"aaaa\">]><event uid=\"&a;\" type=\"a-f-G\"><point lat=\"1\" lon=\"1\"/></event>",
            "<!doctype x SYSTEM \"http://example.invalid/x.dtd\"><event uid=\"a\" type=\"a-f-G\"><point lat=\"1\" lon=\"1\"/></event>",
        )
        for (b in bad) {
            assertThrows("should reject: $b", CotException::class.java) { CotParse.parse(b.toByteArray(), now) }
        }
    }

    @Test
    fun rejectsProtobufAndOversize() {
        assertThrows(CotException::class.java) { CotParse.parse(byteArrayOf(0xBF.toByte(), 1, 0xBF.toByte(), 0), now) }
        val big = marker("x".repeat(CotParse.MAX_EVENT_BYTES))
        assertThrows(CotException::class.java) { CotParse.parse(big, now) }
    }

    @Test
    fun capsAndCleansFields() {
        val ev = CotParse.parse(marker("a".repeat(5000)), now)
        assertEquals(CotParse.MAX_REMARKS, ev.remarks.length)
        val path = """<event uid="p" type="a-u-G"><point lat="1" lon="1"/><detail>
            <usericon iconsetpath="../../secret.png"/><__group name="Chartreuse" role="x"/></detail></event>"""
        val p = CotParse.parse(path.toByteArray(), now)
        assertEquals("", p.iconsetPath)
        assertEquals("", p.team)
    }

    @Test
    fun rebuiltEventParsesBackToTheSameFieldsAndCarriesNothingElse() {
        val original = CotParse.parse(pli, now)
        val rebuilt = CotBuild.fromEvent(original)
        assertFalse(rebuilt.contains("endpoint"))
        assertFalse(rebuilt.contains("takv"))
        assertFalse(rebuilt.contains("battery"))
        val again = CotParse.parse(rebuilt.toByteArray(), now)
        assertEquals(original.copy(isTakClient = true), again)
    }

    @Test
    fun hostileFieldValuesStayDataWhenRebuilt() {
        val xml = """<event uid="u&quot;&gt;&lt;event" type="a-u-G"><point lat="1" lon="1"/><detail>
            <contact callsign="&lt;/contact&gt;&lt;link/&gt;"/><remarks>&lt;/remarks&gt;&lt;detail&gt;</remarks></detail></event>"""
        val rebuilt = CotBuild.fromEvent(CotParse.parse(xml.toByteArray(), now))
        assertFalse(rebuilt.contains("<link"))
        assertEquals(1, Regex("<detail>").findAll(rebuilt).count())
        assertEquals("</contact><link/>", CotParse.parse(rebuilt.toByteArray(), now).callsign)
    }
}

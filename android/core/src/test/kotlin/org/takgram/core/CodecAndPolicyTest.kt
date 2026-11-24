package org.takgram.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64
import java.util.zip.Deflater

class CodecAndPolicyTest {
    private val t0 = Instant.parse("2026-11-23T12:00:00Z")

    @Test
    fun cot1RoundTrip() {
        val xml = CotBuild.marker("m-1", "a-h-G-U-C-A", "Tank 1", 30.3, -97.7, "Seen <moving> north", now = t0)
        val line = Cot1Codec.encode(xml)
        assertTrue(line.startsWith("#cot1 "))
        assertFalse(line.contains("=") || line.contains("+") || line.contains("/"))
        val message = "Tank 1\nHostile ground armor\n$line"
        assertEquals(xml, String(Cot1Codec.decode(Cot1Codec.find(message)!!), Charsets.UTF_8))
        assertTrue("fits a Telegram caption", message.length < 1024)
    }

    @Test
    fun cot1IsOnlyReadFromTheLastLine() {
        assertNull(Cot1Codec.find("#cot1 AAAA\nsome later text"))
        assertNull(Cot1Codec.find("just talking about #cot1 here"))
        assertEquals("AAAA", Cot1Codec.find("card\n#cot1 AAAA\n"))
    }

    @Test
    fun cot1RejectsGarbageAndBombs() {
        assertThrows(CotException::class.java) { Cot1Codec.decode("") }
        assertThrows(CotException::class.java) { Cot1Codec.decode("not base64 !!") }
        assertThrows(CotException::class.java) { Cot1Codec.decode("AAAAAAAAAAAAAAAA") }
        assertThrows(CotException::class.java) { Cot1Codec.decode("A".repeat(Cot1Codec.MAX_ENCODED_CHARS + 1)) }

        // 4 MB of zeros deflates to a few kilobytes: must be refused, not inflated.
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(ByteArray(4 * 1024 * 1024))
        deflater.finish()
        val buf = ByteArray(64 * 1024)
        val n = deflater.deflate(buf)
        deflater.end()
        val bomb = Base64.getUrlEncoder().withoutPadding().encodeToString(buf.copyOf(n))
        assertTrue(bomb.length <= Cot1Codec.MAX_ENCODED_CHARS)
        assertThrows(CotException::class.java) { Cot1Codec.decode(bomb) }
    }

    @Test
    fun identityCardRoundTripAndValidation() {
        val id = Identity("ANDROID-f2ea670fc71b500d", "ALPHA 2 & co", "Dark Blue")
        val text = IdentityCard.text(id)
        assertTrue(text.contains("Callsign: ALPHA 2 & co"))
        assertEquals(id, IdentityCard.parse(text))
        assertTrue(IdentityCard.contains(text))

        assertNull(IdentityCard.parse("hello"))
        assertNull(IdentityCard.parse("#takid1 cs=X team=Cyan"))
        assertNull(IdentityCard.parse("#takid1 uid=U cs= team=Cyan"))
        assertNull(IdentityCard.parse("#takid1 uid=%ZZ cs=X team=Cyan"))
        assertNull(IdentityCard.parse("#takid1 uid=" + "u".repeat(700) + " cs=X team=Cyan"))
        assertEquals("White", IdentityCard.parse("#takid1 uid=U cs=X team=Chartreuse")!!.team)
        assertEquals("X", IdentityCard.parse("#takid1 uid=U cs=X%0A%00 team=Cyan")!!.callsign)
    }

    @Test
    fun dedupKeepsOnlyNewerEvents() {
        val d = Dedup(capacity = 2)
        assertTrue(d.isNew("a", 100))
        assertFalse(d.isNew("a", 100))
        assertFalse(d.isNew("a", 50))
        assertTrue(d.isNew("a", 101))
        assertTrue(d.isNew("b", 1))
        assertTrue(d.isNew("c", 1)) // evicts the least recently used uid, "a"
        assertTrue(d.isNew("a", 1))
    }

    @Test
    fun sharePolicySendsOnMoveAndHeartbeat() {
        val p = SharePolicy(minIntervalMs = 15_000, minMoveM = 10.0, heartbeatMs = 60_000)
        assertTrue(p.shouldSend(0, 33.0, -97.0))
        assertFalse("moved but too soon", p.shouldSend(5_000, 33.001, -97.0))
        assertFalse("long enough but not moved", p.shouldSend(20_000, 33.00001, -97.0))
        assertTrue("moved 100 m after 20 s", p.shouldSend(20_000, 33.0009, -97.0))
        assertFalse(p.shouldSend(70_000, 33.0009, -97.0))
        assertTrue("heartbeat while still", p.shouldSend(80_000, 33.0009, -97.0))
        p.reset()
        assertTrue(p.shouldSend(80_001, 33.0009, -97.0))
    }

    @Test
    fun distanceIsAboutRight() {
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 1.0, 0.0), 100.0)
        assertEquals(0.0, Geo.distanceM(33.0, -97.0, 33.0, -97.0), 1e-6)
    }
}

package org.takgram.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The bridge against a fake Telegram: messages go in, CoT for ATAK comes out. */
class BridgeTest {
    private var clock = Instant.parse("2026-11-23T12:00:00Z")
    private val sent = ArrayList<String>()
    private val bridge = Bridge({ sent.add(it) }, { clock })

    private val ann = TgSender(1001, "Ann", isBot = false, isSelf = false)
    private val bob = TgSender(1002, "Bob", isBot = false, isSelf = false)
    private val me = TgSender(1, "Me", isBot = false, isSelf = true)
    private var nextId = 100L

    private fun msg(from: TgSender, content: TgContent, replyTo: Long = 0, edited: Boolean = false, id: Long = nextId++) =
        TgMessage(-500, id, from, content, replyTo, edited)

    private fun live(lat: Double = 33.0, lon: Double = -97.0, expires: Long = Bridge.FOREVER_S, heading: Int = 90) =
        TgContent.LiveLocation(lat, lon, 8.0, heading, expires)

    private fun parsed(i: Int = sent.lastIndex) = CotParse.parse(sent[i].toByteArray(), clock)

    private fun advance(seconds: Long) {
        clock = clock.plusSeconds(seconds)
    }

    @Test
    fun telegramOnlyUserAppearsAsAContact() {
        bridge.onMessage(msg(ann, live()))
        assertEquals(1, sent.size)
        val ev = parsed()
        assertEquals("TG-1001", ev.uid)
        assertEquals("TG-Ann", ev.callsign)
        assertEquals("White", ev.team)
        assertEquals("a-f-G-U-C", ev.type)
        assertEquals(33.0, ev.lat, 1e-9)
        assertEquals(8.0, ev.ce!!, 1e-9)
        assertEquals(90.0, ev.course!!, 1e-9)
        assertEquals(clock.plusSeconds(Bridge.POSITION_STALE_S), ev.stale)
    }

    @Test
    fun identityCardMakesTheTelegramPositionUseTheTakIdentity() {
        bridge.onMessage(msg(ann, live()))
        bridge.onMessage(msg(ann, TgContent.Text(IdentityCard.text(Identity("ANDROID-ann", "ALPHA-2", "Cyan")))))
        assertEquals("the card is not chat: it removes the old icon and re-sends the position", 3, sent.size)
        assertTrue(sent[1].contains("type=\"t-x-d-d\"") && sent[1].contains("<link uid=\"TG-1001\""))
        assertEquals("ANDROID-ann", parsed().uid)
        assertTrue(sent[2].contains("endpoint=\"*:-1:stcp\""))
        bridge.onMessage(msg(ann, TgContent.Text(IdentityCard.text(Identity("ANDROID-ann", "ALPHA-2", "Cyan")))))
        assertEquals("the same card again changes nothing", 3, sent.size)
        assertEquals("ALPHA-2", parsed().callsign)
        assertEquals("Cyan", parsed().team)
        bridge.onMessage(msg(ann, live(lat = 33.1), edited = true))
        assertEquals("ANDROID-ann", parsed().uid)
    }

    @Test
    fun aCardOnlyDescribesItsOwnSender() {
        bridge.onMessage(msg(bob, TgContent.Text(IdentityCard.text(Identity("ANDROID-ann", "ALPHA-2", "Cyan")))))
        bridge.onMessage(msg(ann, live()))
        assertEquals("TG-1001", parsed().uid)
        assertEquals("ANDROID-ann", bridge.identityOf(1002)!!.uid) // bob claimed it for himself only
    }

    @Test
    fun ownMessagesNeverComeBackIntoOwnAtak() {
        bridge.onMessage(msg(me, live()))
        bridge.onMessage(msg(me, TgContent.Text("hello")))
        val card = "x\n" + Cot1Codec.encode(CotBuild.marker("m-1", "a-h-G", "Tank", 30.0, -97.0, now = clock))
        bridge.onMessage(msg(me, TgContent.Text(card)))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun botsAndNonUserSendersAreIgnoredUnlessTrusted() {
        val bot = TgSender(7, "WeatherBot", isBot = true, isSelf = false)
        val card = "Storm cell\n" + Cot1Codec.encode(CotBuild.marker("wx-1", "a-u-G", "Storm", 30.0, -97.0, now = clock))
        bridge.onMessage(msg(bot, TgContent.Text(card)))
        bridge.onMessage(msg(bot, live()))
        bridge.onMessage(msg(TgSender(0, "Channel", isBot = false, isSelf = false), TgContent.Text("hi")))
        assertTrue(sent.isEmpty())
        bridge.trustedBots = setOf(7)
        bridge.onMessage(msg(bot, TgContent.Text(card)))
        assertEquals("wx-1", parsed().uid)
    }

    @Test
    fun textBecomesAllChatRoomsMessageOnce() {
        bridge.onMessage(msg(ann, TgContent.Text("Do you see me yet <b>"), id = 42))
        assertEquals(1, sent.size)
        val xml = sent[0]
        assertTrue(xml.contains("type=\"b-t-f\""))
        assertTrue(xml.contains("senderCallsign=\"TG-Ann\""))
        assertTrue(xml.contains("chatroom=\"All Chat Rooms\""))
        assertTrue(xml.contains(">Do you see me yet &lt;b&gt;</remarks>"))
        assertTrue(xml.contains("messageId=\"tg--500-42\""))
        bridge.onMessage(msg(ann, TgContent.Text("Do you see me yet (edited)"), edited = true, id = 42))
        assertEquals("an edit is not sent again", 1, sent.size)
    }

    @Test
    fun cot1MessageIsRebuiltAndDeduplicated() {
        val original = "<event version=\"2.0\" uid=\"m-1\" type=\"a-h-G-U-C-A\" how=\"h-g-i-g-o\" " +
            "time=\"2026-11-23T11:59:00.000Z\" start=\"2026-11-23T11:59:00.000Z\" stale=\"2026-11-23T13:00:00.000Z\">" +
            "<point lat=\"30.3\" lon=\"-97.7\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/><detail>" +
            "<contact callsign=\"Tank 1\" endpoint=\"10.0.0.9:4242:tcp\"/><remarks>north</remarks>" +
            "<__serverdestination destinations=\"evil\"/></detail></event>"
        val text = "Tank 1\nHostile ground armor\n" + Cot1Codec.encode(original)
        bridge.onMessage(msg(ann, TgContent.Text(text)))
        assertEquals(1, sent.size)
        assertEquals("m-1", parsed().uid)
        assertEquals("Tank 1", parsed().callsign)
        assertFalse("unknown detail is not passed through", sent[0].contains("serverdestination") || sent[0].contains("endpoint"))
        assertEquals("the poster is recorded as the creator", "TG-1001", parsed().creatorUid)

        bridge.onMessage(msg(bob, TgContent.Text(text)))
        assertEquals("same uid and time is a duplicate", 1, sent.size)
        val newer = original.replace("time=\"2026-11-23T11:59:00.000Z\"", "time=\"2026-11-23T11:59:30.000Z\"")
        bridge.onMessage(msg(ann, TgContent.Text("x\n" + Cot1Codec.encode(newer)), edited = true))
        assertEquals("a newer version of the object is sent", 2, sent.size)
    }

    @Test
    fun badOrStaleCot1IsDropped() {
        bridge.onMessage(msg(ann, TgContent.Text("x\n#cot1 AAAA")))
        bridge.onMessage(msg(ann, TgContent.Text("x\n#cot1 " + "A".repeat(20_000))))
        val stale = CotBuild.marker("old", "a-u-G", "Old", 1.0, 1.0, staleSeconds = 60, now = clock.minusSeconds(3600))
        bridge.onMessage(msg(ann, TgContent.Text("x\n" + Cot1Codec.encode(stale))))
        val dtd = "<!DOCTYPE x [<!ENTITY a \"b\">]><event uid=\"&a;\" type=\"a-u-G\"><point lat=\"1\" lon=\"1\"/></event>"
        bridge.onMessage(msg(ann, TgContent.Text("x\n" + Cot1Codec.encode(dtd))))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun pinBecomesMarkerAndAReplyNamesIt() {
        bridge.onMessage(msg(ann, TgContent.Pin(30.5, -97.5), id = 77))
        assertEquals("TGPIN--500-77", parsed().uid)
        assertEquals("Pin (TG-Ann)", parsed().callsign)
        bridge.onMessage(msg(bob, TgContent.Text("Rally point"), replyTo = 77))
        assertEquals(2, sent.size)
        assertEquals("TGPIN--500-77", parsed().uid)
        assertEquals("Rally point", parsed().callsign)
        assertFalse("the naming reply is not also chat", sent[1].contains("b-t-f"))
    }

    @Test
    fun stationaryTimedShareIsRepeatedUntilItExpires() {
        bridge.onMessage(msg(ann, live(expires = 300)))
        assertEquals(1, sent.size)
        advance(30); bridge.tick()
        assertEquals("not repeated before a minute", 1, sent.size)
        advance(40); bridge.tick()
        assertEquals(2, sent.size)
        assertEquals(clock.plusSeconds(Bridge.POSITION_STALE_S), parsed().stale)
        assertEquals(listOf("TG-Ann"), bridge.activeCallsigns())
        advance(300); bridge.tick()
        assertEquals("expired: no more repeats", 2, sent.size)
        assertTrue(bridge.activeCallsigns().isEmpty())
    }

    @Test
    fun silentForeverShareIsDroppedAndStopIsHonoured() {
        bridge.onMessage(msg(ann, live()))
        advance(70); bridge.tick()
        assertEquals(2, sent.size)
        advance(200); bridge.tick()
        assertEquals("a TAKgram phone that went quiet is not repeated", 2, sent.size)

        bridge.onMessage(msg(bob, live()))
        bridge.onMessage(msg(bob, live(expires = 0), edited = true))
        advance(70); bridge.tick()
        assertEquals(3, sent.size)
    }

    @Test
    fun theLinkedChatIsAStandingContact() {
        bridge.setChat(-500, "Ops <team>")
        assertEquals(1, sent.size)
        assertEquals("TG-CHAT--500", parsed().uid)
        assertEquals("Telegram: Ops <team>", parsed().callsign)
        assertTrue(sent[0].contains("endpoint=\"*:-1:stcp\""))
        assertTrue(bridge.isTelegramContact("TG-CHAT--500"))
        assertTrue(bridge.isTelegramCallsign("Telegram: Ops <team>"))
        advance(30); bridge.tick()
        assertEquals(1, sent.size)
        advance(40); bridge.tick()
        assertEquals("repeated so it stays online", 2, sent.size)
        bridge.setChat(-600, "Other")
        assertTrue("the old chat's contact is removed", sent[2].contains("t-x-d-d") && sent[2].contains("TG-CHAT--500"))
        assertEquals("TG-CHAT--600", parsed().uid)
        bridge.clearChat()
        assertTrue(sent.last().contains("t-x-d-d") && sent.last().contains("TG-CHAT--600"))
        advance(120); bridge.tick()
        assertTrue(sent.last().contains("t-x-d-d"))
    }

    @Test
    fun outOfRangeAndOversizeInputIsIgnored() {
        bridge.onMessage(msg(ann, live(lat = 91.0)))
        bridge.onMessage(msg(ann, live(lat = Double.NaN)))
        bridge.onMessage(msg(ann, TgContent.Pin(0.0, 181.0)))
        bridge.onMessage(msg(ann, TgContent.Text("x".repeat(Bridge.MAX_TEXT_CHARS + 1))))
        bridge.onMessage(msg(ann, TgContent.Other))
        assertTrue(sent.isEmpty())
    }
}

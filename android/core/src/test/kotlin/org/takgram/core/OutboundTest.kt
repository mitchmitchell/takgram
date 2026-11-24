package org.takgram.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OutboundTest {
    private val now = Instant.parse("2026-11-23T12:00:00Z")
    private val me = "ANDROID-f2ea670fc71b500d"

    // Captured from ATAK-CIV 5.8.0.10 on first connecting to a streaming server (trimmed, phone number changed).
    private val atakSelf = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <event version="2.0" uid="ANDROID-f2ea670fc71b500d" type="a-f-G-U-C" time="2026-11-23T12:00:00.000Z"
          start="2026-11-23T12:00:00.000Z" stale="2026-11-23T12:06:15.000Z" how="m-g" access="Undefined">
        <point lat="30.267198" lon="-97.742998" hae="-26.904" ce="5.0" le="9999999.0"/><detail>
        <takv os="34" version="5.8.0.10 (daaa2b7).1793994885-CIV" device="GOOGLE SDK" platform="ATAK-CIV"/>
        <contact endpoint="*:-1:stcp" phone="+15550100" callsign="BARRACUDA"/><uid Droid="BARRACUDA"/>
        <__group role="Team Member" name="Orange"/></detail></event>"""

    private fun ev(xml: String) = CotParse.parse(xml.toByteArray(), now)

    private fun marker(
        uid: String = "m-1",
        creator: String = me,
        extra: String = "",
        type: String = "a-h-G-U-C-A",
        remarks: String = "Seen moving north",
    ) = """<event version="2.0" uid="$uid" type="$type" how="h-g-i-g-o" time="2026-11-23T12:00:00.000Z"
          start="2026-11-23T12:00:00.000Z" stale="2026-11-23T13:00:00.000Z">
        <point lat="30.3" lon="-97.7" hae="9999999.0" ce="9999999.0" le="9999999.0"/><detail>
        <contact callsign="Tank 1"/><link uid="$creator" production_time="2026-11-23T12:00:00Z" type="a-f-G-U-C"
          parent_callsign="BARRACUDA" relation="p-p"/><remarks>$remarks</remarks>$extra</detail></event>"""

    private fun chat(sender: String, toUid: String, text: String) = CotParse.parse(
        (
            "<event version=\"2.0\" uid=\"GeoChat.$sender.x.1\" type=\"b-t-f\" how=\"h-g-i-g-o\"><point lat=\"0\" lon=\"0\"/><detail>" +
                "<__chat parent=\"RootContactGroup\" messageId=\"1\" chatroom=\"room\" id=\"$toUid\" senderCallsign=\"BARRACUDA\">" +
                "<chatgrp uid0=\"$sender\" uid1=\"$toUid\" id=\"$toUid\"/></__chat>" +
                "<link uid=\"$sender\" type=\"a-f-G-U-C\" relation=\"p-p\"/><remarks>$text</remarks></detail></event>"
            ).toByteArray(),
        now,
    )

    private fun policy(fromTelegram: Set<String> = emptySet(), contacts: Set<String> = emptySet()) =
        OutboundPolicy({ it in fromTelegram }, { it in contacts }).apply { decide(ev(atakSelf)) }

    @Test
    fun ownPositionReportGivesIdentityAndIsNeverPosted() {
        val p = OutboundPolicy({ false }, { false })
        val a = p.decide(ev(atakSelf))
        assertEquals(OutAction.SelfSeen(Identity(me, "BARRACUDA", "Orange")), a)
        assertEquals(me, p.selfUid)
        val other = atakSelf.replace(me, "ANDROID-someone-else")
        assertEquals(OutAction.Ignore, p.decide(ev(other)))
    }

    @Test
    fun ownMarkersAndAlertsBecomeCards() {
        val p = policy()
        assertTrue(p.decide(ev(marker())) is OutAction.Card)
        assertTrue(p.decide(ev(marker(type = "b-m-p-s-m"))) is OutAction.Card)
        assertTrue(p.decide(ev(marker(uid = "$me-9-1-1", type = "b-a-o-tbl"))) is OutAction.Card)
        assertEquals(OutAction.Ignore, p.decide(ev(marker(type = "u-d-r"))))
        assertEquals(OutAction.Ignore, p.decide(ev(marker(type = "t-x-d-d"))))
    }

    @Test
    fun nothingFromTelegramOrFromOtherCreatorsIsPostedBack() {
        val p = policy(fromTelegram = setOf("m-tg"))
        assertEquals(OutAction.Ignore, p.decide(ev(marker(uid = "m-tg"))))
        assertEquals(OutAction.Ignore, p.decide(ev(marker(creator = "ANDROID-someone-else"))))
        val directed = marker(extra = "<marti><dest callsign=\"BRAVO-6\"/></marti>")
        assertEquals("sent to one named contact, not to everyone", OutAction.Ignore, p.decide(ev(directed)))
    }

    @Test
    fun chatGoesOutOnlyFromSelfToAllChatRoomsOrAChatMember() {
        val p = policy(contacts = setOf("TG-1001"))
        assertEquals(OutAction.ChatText("Roger"), p.decide(chat(me, "All Chat Rooms", "Roger")))
        assertEquals(OutAction.ChatText("for Ann"), p.decide(chat(me, "TG-1001", "for Ann")))
        assertEquals(OutAction.Ignore, p.decide(chat(me, "ANDROID-wifi-peer", "private")))
        assertEquals(OutAction.Ignore, p.decide(chat("TG-1001", "All Chat Rooms", "echo")))
    }

    @Test
    fun bridgeRemembersWhatCameFromTelegram() {
        val sent = ArrayList<String>()
        val bridge = Bridge({ sent.add(it) }, { now })
        val ann = TgSender(1001, "Ann", isBot = false, isSelf = false)
        val xml = CotBuild.marker("m-tg", "a-h-G", "Tank", 30.0, -97.0, now = now)
        bridge.onMessage(TgMessage(-5, 1, ann, TgContent.Text("x\n" + Cot1Codec.encode(xml))))
        bridge.onMessage(TgMessage(-5, 2, ann, TgContent.Pin(30.0, -97.0)))
        bridge.onMessage(TgMessage(-5, 3, ann, TgContent.LiveLocation(30.0, -97.0, 5.0, 0, 600)))
        assertTrue(bridge.cameFromTelegram("m-tg"))
        assertTrue(bridge.cameFromTelegram("TGPIN--5-2"))
        assertTrue(bridge.isTelegramContact("TG-1001"))
        assertFalse(bridge.cameFromTelegram("m-local"))
        assertFalse(bridge.isTelegramContact("ANDROID-wifi-peer"))
    }

    @Test
    fun captionIsReadableFitsAndCarriesTheEventWithoutPrivateDetail() {
        val card = Caption.card(ev(marker()), Caption.PHOTO_LIMIT)!!
        val lines = card.lines()
        assertEquals("Tank 1 · Hostile ground armor", lines[0])
        assertEquals("30.30000, -97.70000", lines[1])
        assertTrue(lines[2], lines[2].startsWith("MGRS 14R "))
        assertEquals("2026-11-23 12:00Z", lines[3])
        assertEquals("Seen moving north", lines[4])
        assertTrue(card.length <= Caption.PHOTO_LIMIT)
        val rebuilt = String(Cot1Codec.decode(Cot1Codec.find(card)!!), Charsets.UTF_8)
        val back = CotParse.parse(rebuilt.toByteArray(), now)
        assertEquals("m-1", back.uid)
        assertEquals("Tank 1", back.callsign)
        assertFalse(rebuilt.contains("parent_callsign") || rebuilt.contains("<link"))
    }

    @Test
    fun captionCutsLongRemarksAndGivesUpOnlyWhenEssentialsDoNotFit() {
        val long = ev(marker(remarks = "x".repeat(1900)))
        val card = Caption.card(long, Caption.PHOTO_LIMIT)!!
        assertTrue(card.length <= Caption.PHOTO_LIMIT)
        assertNotNull(Cot1Codec.find(card))
        assertNull(Caption.card(long, 100))
        val alert = ev(marker(uid = "sos", type = "b-a-o-tbl"))
        assertTrue(Caption.card(alert, Caption.TEXT_LIMIT)!!.startsWith("EMERGENCY: Tank 1 · Emergency: 911 alert"))
    }
}

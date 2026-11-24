package org.takgram.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds Cursor on Target XML events from validated fields.
 *
 * Every event TAKgram hands to ATAK is rebuilt here; text from Telegram is never passed through as XML.
 * Mirrors the Python package's cot._event / build_marker / build_chat.
 */
object CotBuild {
    private val ISO: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    /** Sentinel ATAK uses for "unknown" height and error values. */
    const val UNKNOWN = 9_999_999.0
    const val ALL_CHAT = "All Chat Rooms"
    const val STREAM_ENDPOINT = "*:-1:stcp"
    private const val UNKNOWN_TEXT = "9999999.0"

    fun iso(t: Instant): String = ISO.format(t)

    /** Escapes text for use in XML element content and double-quoted attributes. */
    fun escape(s: String): String {
        val b = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '&' -> b.append("&amp;")
                '<' -> b.append("&lt;")
                '>' -> b.append("&gt;")
                '"' -> b.append("&quot;")
                '\'' -> b.append("&apos;")
                else -> if (ch >= ' ' || ch == '\n' || ch == '\t') b.append(ch)
            }
        }
        return b.toString()
    }

    private fun num(v: Double): String = String.format(Locale.ROOT, "%.1f", v)

    private fun attr(name: String, value: String): String = " $name=\"${escape(value)}\""

    private fun envelope(
        uid: String,
        type: String,
        how: String,
        time: Instant,
        stale: Instant,
        lat: Double,
        lon: Double,
        hae: Double?,
        ce: Double?,
        detail: String,
    ): String {
        require(lat in -90.0..90.0 && lon in -180.0..180.0) { "coordinates out of range" }
        require(uid.isNotEmpty() && type.isNotEmpty()) { "uid and type are required" }
        return buildString {
            append("<event version=\"2.0\"")
            append(attr("uid", uid)).append(attr("type", type)).append(attr("how", how))
            append(attr("time", iso(time))).append(attr("start", iso(time))).append(attr("stale", iso(stale)))
            append(">")
            append(String.format(Locale.ROOT, "<point lat=\"%.7f\" lon=\"%.7f\"", lat, lon))
            append(attr("hae", if (hae == null) UNKNOWN_TEXT else num(hae)))
            append(attr("ce", if (ce == null) UNKNOWN_TEXT else num(ce)))
            append(attr("le", UNKNOWN_TEXT)).append("/>")
            append("<detail>").append(detail).append("</detail></event>")
        }
    }

    fun event(
        uid: String,
        type: String,
        how: String,
        lat: Double,
        lon: Double,
        hae: Double?,
        staleSeconds: Long,
        detail: String,
        now: Instant = Instant.now(),
    ): String = envelope(uid, type, how, now, now.plusSeconds(staleSeconds), lat, lon, hae, null, detail)

    fun marker(
        uid: String,
        type: String,
        callsign: String,
        lat: Double,
        lon: Double,
        remarks: String = "",
        staleSeconds: Long = 3600,
        how: String = "h-g-i-g-o",
        now: Instant = Instant.now(),
    ): String {
        val detail = "<contact${attr("callsign", callsign)}/><remarks>${escape(remarks)}</remarks>"
        return event(uid, type, how, lat, lon, null, staleSeconds, detail, now)
    }

    /**
     * A team member's position, as ATAK shows for other TAK users. Used for Telegram live locations.
     * ATAK lists the sender as a contact when `contact` and `__group` are present (checked on ATAK-CIV 5.8).
     */
    fun position(
        uid: String,
        callsign: String,
        team: String,
        lat: Double,
        lon: Double,
        accuracyM: Double?,
        courseDeg: Double?,
        staleSeconds: Long,
        now: Instant = Instant.now(),
    ): String {
        val teamName = if (Teams.valid(team)) team else Teams.TELEGRAM_ONLY
        val detail = buildString {
            // The endpoint is what makes ATAK list the sender under Contacts (checked on ATAK-CIV 5.8).
            // "*:-1:stcp" is the value ATAK itself uses for "reachable through the server connection".
            append("<contact").append(attr("callsign", callsign)).append(attr("endpoint", STREAM_ENDPOINT)).append("/>")
            append("<__group").append(attr("name", teamName)).append(attr("role", "Team Member")).append("/>")
            append("<uid").append(attr("Droid", callsign)).append("/>")
            if (courseDeg != null) {
                append("<track").append(attr("course", num(courseDeg))).append(attr("speed", "0.0")).append("/>")
            }
            append("<remarks>via Telegram</remarks>")
        }
        val ce = accuracyM?.takeIf { it > 0 }
        return envelope(uid, "a-f-G-U-C", "m-g", now, now.plusSeconds(staleSeconds), lat, lon, null, ce, detail)
    }

    /** Asks ATAK to remove the object with this uid from the map at once. */
    fun delete(uid: String, now: Instant = Instant.now()): String {
        val detail = "<link" + attr("uid", uid) + " relation=\"none\" type=\"none\"/><__forcedelete/>"
        return event("delete-$uid", "t-x-d-d", "m-g", 0.0, 0.0, null, 20, detail, now)
    }

    /** A GeoChat message to All Chat Rooms. [messageId] must be stable so a repeat is not shown twice. */
    fun chat(
        senderUid: String,
        senderCallsign: String,
        text: String,
        messageId: String,
        now: Instant = Instant.now(),
    ): String {
        val detail = buildString {
            append("<__chat parent=\"RootContactGroup\" groupOwner=\"false\"")
            append(attr("messageId", messageId)).append(attr("chatroom", ALL_CHAT)).append(attr("id", ALL_CHAT))
            append(attr("senderCallsign", senderCallsign)).append(">")
            append("<chatgrp").append(attr("uid0", senderUid)).append(attr("uid1", ALL_CHAT)).append(attr("id", ALL_CHAT))
            append("/></__chat>")
            append("<link").append(attr("uid", senderUid)).append(" type=\"a-f-G-U-C\" relation=\"p-p\"/>")
            append("<remarks").append(attr("source", "BAO.F.ATAK.$senderUid")).append(attr("to", ALL_CHAT))
            append(attr("time", iso(now))).append(">").append(escape(text)).append("</remarks>")
        }
        return event("GeoChat.$senderUid.$ALL_CHAT.$messageId", "b-t-f", "h-g-i-g-o", 0.0, 0.0, null, 86_400, detail, now)
    }

    /** Writes a fresh event from validated fields. Nothing from the original XML is copied through. */
    fun fromEvent(ev: CotEvent): String {
        val detail = buildString {
            if (ev.callsign.isNotEmpty()) append("<contact").append(attr("callsign", ev.callsign)).append("/>")
            if (ev.team.isNotEmpty()) {
                append("<__group").append(attr("name", ev.team))
                append(attr("role", ev.role.ifEmpty { "Team Member" })).append("/>")
            }
            if (ev.course != null || ev.speed != null) {
                append("<track").append(attr("course", num(ev.course ?: 0.0)))
                append(attr("speed", num(ev.speed ?: 0.0))).append("/>")
            }
            if (ev.colorArgb != null) append("<color").append(attr("argb", ev.colorArgb.toString())).append("/>")
            if (ev.iconsetPath.isNotEmpty()) append("<usericon").append(attr("iconsetpath", ev.iconsetPath)).append("/>")
            append("<remarks>").append(escape(ev.remarks)).append("</remarks>")
        }
        return envelope(ev.uid, ev.type, ev.how, ev.time, ev.stale, ev.lat, ev.lon, ev.hae, ev.ce, detail)
    }
}

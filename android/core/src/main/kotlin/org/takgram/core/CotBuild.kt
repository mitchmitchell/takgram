package org.takgram.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds Cursor on Target XML events from validated fields.
 *
 * Every event TAKgram hands to ATAK is rebuilt here; text from Telegram is never passed through as XML.
 * Mirrors the Python package's cot._event / build_marker.
 */
object CotBuild {
    private val ISO: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    /** Sentinel ATAK uses for "unknown" height and error values. */
    const val UNKNOWN = 9_999_999.0

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
    ): String {
        require(lat in -90.0..90.0 && lon in -180.0..180.0) { "coordinates out of range" }
        require(uid.isNotEmpty() && type.isNotEmpty()) { "uid and type are required" }
        val stale = now.plusSeconds(staleSeconds)
        val h = if (hae == null) "9999999.0" else String.format(Locale.ROOT, "%.1f", hae)
        return buildString {
            append("<event version=\"2.0\" uid=\"").append(escape(uid))
            append("\" type=\"").append(escape(type))
            append("\" how=\"").append(escape(how))
            append("\" time=\"").append(iso(now))
            append("\" start=\"").append(iso(now))
            append("\" stale=\"").append(iso(stale)).append("\">")
            append(String.format(Locale.ROOT, "<point lat=\"%.7f\" lon=\"%.7f\" ", lat, lon))
            append("hae=\"").append(h).append("\" ce=\"9999999.0\" le=\"9999999.0\"/>")
            append("<detail>").append(detail).append("</detail></event>")
        }
    }

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
        val detail = "<contact callsign=\"${escape(callsign)}\"/><remarks>${escape(remarks)}</remarks>"
        return event(uid, type, how, lat, lon, null, staleSeconds, detail, now)
    }
}

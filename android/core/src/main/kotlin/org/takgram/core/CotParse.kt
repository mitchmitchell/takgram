package org.takgram.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.xml.parsers.DocumentBuilderFactory

class CotException(message: String) : Exception(message)

/**
 * Parses one CoT XML event from an untrusted source into validated fields.
 *
 * DTDs and entities are refused outright, sizes are capped, and every field is checked. The result
 * is never forwarded as XML; [CotBuild.fromEvent] writes a fresh event from these fields.
 */
object CotParse {
    const val MAX_EVENT_BYTES = 64 * 1024
    const val MAX_UID = 128
    const val MAX_TYPE = 64
    const val MAX_NAME = 64
    const val MAX_REMARKS = 2000
    private const val MAX_ICON = 256
    private val TOKEN = Regex("[A-Za-z0-9_.-]+")

    fun parse(data: ByteArray, now: Instant = Instant.now()): CotEvent {
        if (data.isEmpty()) throw CotException("empty")
        if (data[0] == 0xBF.toByte()) throw CotException("TAK protobuf is not supported")
        if (data.size > MAX_EVENT_BYTES) throw CotException("event too large")
        // Refuse DTDs before any XML parser sees the input: no entity expansion, no external fetches.
        val raw = String(data, Charsets.ISO_8859_1)
        if (raw.contains("<!DOCTYPE", ignoreCase = true) || raw.contains("<!ENTITY", ignoreCase = true)) {
            throw CotException("DTD or entity declaration refused")
        }
        val root = try {
            factory().newDocumentBuilder().apply { setErrorHandler(null) }
                .parse(ByteArrayInputStream(data)).documentElement
        } catch (e: Exception) {
            throw CotException("bad xml")
        }
        if (root.tagName != "event") throw CotException("not an event")

        val uid = root.getAttribute("uid")
        val type = root.getAttribute("type")
        if (uid.isEmpty() || uid.length > MAX_UID || uid.any { it < ' ' }) throw CotException("bad uid")
        if (type.length > MAX_TYPE || !TOKEN.matches(type)) throw CotException("bad type")
        val how = root.getAttribute("how").takeIf { it.length <= 32 && TOKEN.matches(it) } ?: "h-g-i-g-o"

        val time = instant(root.getAttribute("time")) ?: now
        val stale = instant(root.getAttribute("stale")) ?: time.plus(Duration.ofMinutes(5))

        val pt = child(root, "point") ?: throw CotException("no point")
        val lat = pt.getAttribute("lat").toDoubleOrNull() ?: throw CotException("bad lat")
        val lon = pt.getAttribute("lon").toDoubleOrNull() ?: throw CotException("bad lon")
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            throw CotException("coordinates out of range")
        }
        val detail = child(root, "detail")
        val contact = detail?.let { child(it, "contact") }
        val group = detail?.let { child(it, "__group") }
        val track = detail?.let { child(it, "track") }
        val color = detail?.let { child(it, "color") }
        val icon = detail?.let { child(it, "usericon") }
        val remarks = detail?.let { child(it, "remarks") }?.textContent.orEmpty()

        return CotEvent(
            uid = uid,
            type = type,
            how = how,
            time = time,
            stale = stale,
            lat = lat,
            lon = lon,
            hae = known(pt.getAttribute("hae")),
            ce = known(pt.getAttribute("ce")),
            callsign = clean(contact?.getAttribute("callsign").orEmpty(), MAX_NAME),
            remarks = clean(remarks, MAX_REMARKS, multiline = true),
            team = group?.getAttribute("name").orEmpty().takeIf { Teams.valid(it) }.orEmpty(),
            role = clean(group?.getAttribute("role").orEmpty(), MAX_NAME),
            course = track?.getAttribute("course")?.toDoubleOrNull()?.takeIf { it in 0.0..360.0 },
            speed = track?.getAttribute("speed")?.toDoubleOrNull()?.takeIf { it in 0.0..10_000.0 },
            colorArgb = color?.let { c ->
                c.getAttribute("argb").ifEmpty { c.getAttribute("value") }.toLongOrNull()?.toInt()
            },
            iconsetPath = icon?.getAttribute("iconsetpath").orEmpty()
                .takeIf { it.length <= MAX_ICON && it.none { ch -> ch < ' ' } && !it.contains("..") }.orEmpty(),
            isTakClient = detail?.let { child(it, "takv") } != null || group != null,
        )
    }

    private fun factory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        // Not every platform parser knows every feature (Android's does not). The DOCTYPE check
        // above is the guarantee; these are a second line of defence where supported.
        val features = listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        )
        for ((feature, value) in features) {
            try {
                setFeature(feature, value)
            } catch (e: Exception) {
                // unsupported on this platform
            }
        }
    }

    private fun child(parent: Element, name: String): Element? {
        var n = parent.firstChild
        while (n != null) {
            if (n is Element && n.tagName == name) return n
            n = n.nextSibling
        }
        return null
    }

    private fun instant(s: String): Instant? = try {
        if (s.isEmpty() || s.length > 40) null else Instant.parse(s)
    } catch (e: DateTimeParseException) {
        null
    }

    /** ATAK writes 9999999 for "unknown". */
    private fun known(s: String): Double? =
        s.toDoubleOrNull()?.takeIf { !it.isNaN() && it < 9_000_000 && it > -100_000 }

    /** Drops control characters, caps the length and trims. */
    fun clean(s: String, max: Int, multiline: Boolean = false): String {
        val b = StringBuilder()
        for (ch in s.trim()) {
            if (b.length >= max) break
            if (ch >= ' ' || (multiline && ch == '\n')) b.append(ch)
        }
        return b.toString().trim()
    }
}

package org.takgram.core

/** Plain-language descriptions of CoT type codes. Ported from the Python package's cottypes.py. */
object CotTypes {
    private val AFFILIATION = mapOf(
        "f" to "Friendly", "a" to "Assumed friendly", "h" to "Hostile", "s" to "Suspect",
        "j" to "Joker", "k" to "Faker", "n" to "Neutral", "u" to "Unknown", "p" to "Pending", "o" to "Unspecified",
    )

    /** Frame family used for drawing: friend, hostile, neutral, unknown. */
    private val FRAME = mapOf(
        "f" to 'f', "a" to 'f', "h" to 'h', "s" to 'h', "j" to 'h', "k" to 'h', "n" to 'n', "u" to 'u', "p" to 'u', "o" to 'u',
    )

    private val DIMENSION = mapOf(
        "P" to "space", "A" to "air", "G" to "ground", "S" to "sea surface", "U" to "subsurface", "F" to "special operations",
    )

    /** Longest match wins. Keys are the part of the type after the dimension letter. */
    private val FUNCTION: Map<String, Map<String, String>> = mapOf(
        "G" to mapOf(
            "U" to "unit", "U-C" to "combat unit", "U-C-I" to "infantry", "U-C-A" to "armor", "U-C-F" to "artillery",
            "U-C-R" to "reconnaissance", "U-C-E" to "engineer", "U-C-D" to "air defense", "U-C-V" to "aviation",
            "U-U" to "combat support", "U-U-S" to "signal", "U-U-M" to "military intelligence",
            "U-U-L" to "law enforcement", "U-S" to "service support", "U-S-M" to "medical", "U-S-S" to "supply",
            "U-S-T" to "transport", "U-H" to "headquarters",
            "E" to "equipment", "E-V" to "vehicle", "E-V-A" to "armored vehicle", "E-V-C" to "civilian vehicle",
            "E-W" to "weapon", "E-S" to "sensor",
            "I" to "installation", "I-U-T" to "telecommunications site", "I-X-H" to "hospital",
        ),
        "A" to mapOf(
            "M" to "military aircraft", "M-F" to "fixed-wing aircraft", "M-H" to "helicopter", "M-F-Q" to "drone",
            "M-H-Q" to "rotary drone", "C" to "civil aircraft", "C-F" to "civil fixed-wing", "C-H" to "civil helicopter",
            "W" to "weapon",
        ),
        "S" to mapOf(
            "C" to "combatant vessel", "N" to "noncombatant vessel", "X" to "non-military vessel",
            "X-M" to "merchant vessel", "X-F" to "fishing vessel", "X-R" to "leisure craft",
        ),
        "U" to mapOf("S" to "submarine", "W" to "underwater weapon"),
    )

    private val BITS = mapOf(
        "b-m-p-s-m" to "Spot marker",
        "b-m-p-w" to "Waypoint",
        "b-m-p-w-GOTO" to "Go-to waypoint",
        "b-m-p-c" to "Control point",
        "b-m-p-s-p-i" to "Sensor point of interest",
        "b-m-p-s-p-loc" to "Point of interest",
        "b-m-p-s-p-op" to "Observation post",
        "b-m-r" to "Route",
        "b-t-f" to "Chat message",
        "b-r-f-h-c" to "CASEVAC request",
        "b-a-o-tbl" to "Emergency: 911 alert",
        "b-a-o-pan" to "Emergency: ring the bell",
        "b-a-o-opn" to "Emergency: troops in contact",
        "b-a-g" to "Emergency: geofence breached",
        "b-a-o-can" to "Emergency cancelled",
        "b-i-x-i" to "Image",
        "u-d-f" to "Freehand shape",
        "u-d-r" to "Rectangle",
        "u-d-c-c" to "Circle",
        "u-d-c-e" to "Ellipse",
        "u-d-p" to "Polygon",
        "u-rb-a" to "Range and bearing line",
        "t-x-d-d" to "Delete",
    )

    fun affiliation(type: String): String {
        val parts = type.split("-")
        return if (parts.size > 1 && parts[0] == "a") parts[1] else ""
    }

    /** 'f' friend, 'h' hostile, 'n' neutral, 'u' unknown. */
    fun frame(type: String): Char = FRAME[affiliation(type)] ?: 'u'

    /** "a-h-G-U-C-A" becomes "Hostile ground armor". Unknown codes fall back gracefully. */
    fun describe(type: String): String {
        val parts = type.split("-")
        if (parts[0] != "a") {
            var best = ""
            for (k in BITS.keys) {
                if ((type == k || type.startsWith("$k-")) && k.length > best.length) best = k
            }
            return if (best.isNotEmpty()) BITS.getValue(best) else "Object ($type)"
        }
        val aff = AFFILIATION[parts.getOrElse(1) { "" }] ?: "Unknown"
        val dimCode = parts.getOrElse(2) { "" }
        val dim = DIMENSION[dimCode].orEmpty()
        val rest = parts.drop(3)
        val table = FUNCTION[dimCode].orEmpty()
        var func = ""
        for (n in rest.size downTo 1) {
            val hit = table[rest.take(n).joinToString("-")]
            if (hit != null) {
                func = hit
                break
            }
        }
        val words = ArrayList<String>()
        words.add(aff)
        if (dim.isNotEmpty()) words.add(dim)
        words.add(func.ifEmpty { if (dim.isEmpty()) "object" else "track" })
        return words.joinToString(" ")
    }

    fun isAlert(type: String): Boolean = type.startsWith("b-a-") && type != "b-a-o-can"
}

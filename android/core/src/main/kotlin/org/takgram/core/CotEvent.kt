package org.takgram.core

import java.time.Instant

/** The fields of a CoT event that TAKgram understands. Anything else in the original XML is dropped. */
data class CotEvent(
    val uid: String,
    val type: String,
    val how: String = "h-g-i-g-o",
    val time: Instant,
    val stale: Instant,
    val lat: Double,
    val lon: Double,
    val hae: Double? = null,
    val ce: Double? = null,
    val callsign: String = "",
    val remarks: String = "",
    val team: String = "",
    val role: String = "",
    val course: Double? = null,
    val speed: Double? = null,
    val colorArgb: Int? = null,
    val iconsetPath: String = "",
    val isTakClient: Boolean = false,
)

/** ATAK's team colours, as written in the `__group name` attribute. */
object Teams {
    val ALL = listOf(
        "White", "Yellow", "Orange", "Magenta", "Red", "Maroon", "Purple",
        "Dark Blue", "Blue", "Cyan", "Teal", "Green", "Dark Green", "Brown",
    )
    const val DEFAULT = "Cyan"
    const val TELEGRAM_ONLY = "White"

    fun valid(name: String): Boolean = name in ALL
}

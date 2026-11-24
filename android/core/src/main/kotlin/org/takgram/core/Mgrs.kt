package org.takgram.core

import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Military Grid Reference System from WGS84 latitude and longitude, at 10 m precision. */
object Mgrs {
    private const val A = 6378137.0
    private const val F = 1 / 298.257223563
    private const val K0 = 0.9996
    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"
    private val COLUMN_SETS = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private const val ROWS = "ABCDEFGHJKLMNPQRSTUV"

    /**
     * For example "18S UJ 2337 0651". Empty outside the MGRS latitude band (80°S to 84°N), where
     * the polar system applies.
     */
    fun of(lat: Double, lon: Double): String {
        if (lat.isNaN() || lon.isNaN() || lat < -80.0 || lat > 84.0 || lon < -180.0 || lon > 180.0) return ""
        val lonN = if (lon == 180.0) -180.0 else lon
        val zone = zone(lat, lonN)
        val band = BANDS[minOf(((lat + 80.0) / 8.0).toInt(), BANDS.length - 1)]

        val e2 = F * (2 - F)
        val ep2 = e2 / (1 - e2)
        val phi = Math.toRadians(lat)
        val lam0 = Math.toRadians((zone - 1) * 6.0 - 180.0 + 3.0)
        val n = A / sqrt(1 - e2 * sin(phi).pow(2))
        val t = tan(phi).pow(2)
        val c = ep2 * cos(phi).pow(2)
        val a = (Math.toRadians(lonN) - lam0) * cos(phi)
        val m = A * (
            (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2.pow(3) / 256) * phi -
                (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2.pow(3) / 1024) * sin(2 * phi) +
                (15 * e2 * e2 / 256 + 45 * e2.pow(3) / 1024) * sin(4 * phi) -
                (35 * e2.pow(3) / 3072) * sin(6 * phi)
            )
        val easting = K0 * n * (a + (1 - t + c) * a.pow(3) / 6 + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a.pow(5) / 120) + 500_000.0
        var northing = K0 * (
            m + n * tan(phi) * (
                a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a.pow(4) / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * ep2) * a.pow(6) / 720
                )
            )
        if (lat < 0) northing += 10_000_000.0

        val col = COLUMN_SETS[(zone - 1) % 3][(floor(easting / 100_000.0).toInt() - 1).coerceIn(0, 7)]
        val rowOffset = if (zone % 2 == 0) 5 else 0
        val row = ROWS[(floor(northing / 100_000.0).toInt() + rowOffset).mod(20)]
        val e = (floor(easting).toLong() % 100_000) / 10
        val nn = (floor(northing).toLong() % 100_000) / 10
        return String.format(Locale.ROOT, "%d%c %c%c %04d %04d", zone, band, col, row, e, nn)
    }

    private fun zone(lat: Double, lon: Double): Int {
        var z = (floor((lon + 180.0) / 6.0).toInt() + 1).coerceIn(1, 60)
        if (lat >= 56.0 && lat < 64.0 && lon >= 3.0 && lon < 12.0) z = 32 // south-west Norway
        if (lat >= 72.0) { // Svalbard
            z = when {
                lon >= 0.0 && lon < 9.0 -> 31
                lon >= 9.0 && lon < 21.0 -> 33
                lon >= 21.0 && lon < 33.0 -> 35
                lon >= 33.0 && lon < 42.0 -> 37
                else -> z
            }
        }
        return z
    }
}

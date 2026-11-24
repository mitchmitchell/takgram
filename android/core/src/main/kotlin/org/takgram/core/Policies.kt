package org.takgram.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Remembers the newest event time seen per uid, so the same event arriving twice, or an older one
 * arriving late, is ignored. Bounded: the least recently used uids are forgotten first.
 */
class Dedup(private val capacity: Int = 2000) {
    private val newest = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > capacity
    }

    /** @return true if this (uid, time) is newer than anything seen for the uid. */
    @Synchronized
    fun isNew(uid: String, timeMs: Long): Boolean {
        val last = newest[uid]
        if (last != null && timeMs <= last) return false
        newest[uid] = timeMs
        return true
    }
}

/**
 * Decides when the user's own position is worth sending: after a real move, but not more often
 * than [minIntervalMs]; and at least every [heartbeatMs] so teammates can tell the phone is alive.
 */
class SharePolicy(
    private val minIntervalMs: Long = 15_000,
    private val minMoveM: Double = 10.0,
    private val heartbeatMs: Long = 60_000,
) {
    private var has = false
    private var lastMs = 0L
    private var lastLat = 0.0
    private var lastLon = 0.0

    fun shouldSend(nowMs: Long, lat: Double, lon: Double): Boolean {
        val send = !has ||
            nowMs - lastMs >= heartbeatMs ||
            (nowMs - lastMs >= minIntervalMs && Geo.distanceM(lastLat, lastLon, lat, lon) >= minMoveM)
        if (send) {
            has = true
            lastMs = nowMs
            lastLat = lat
            lastLon = lon
        }
        return send
    }

    fun reset() {
        has = false
    }
}

object Geo {
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val a = sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin(Math.toRadians(lon2 - lon1) / 2).pow(2)
        return 2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

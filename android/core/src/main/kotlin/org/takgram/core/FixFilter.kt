package org.takgram.core

/**
 * Decides which position fixes to use when several location sources report at once.
 *
 * A phone's GPS fix (a few metres) and its cell/Wi-Fi fix (hundreds of metres) can disagree by a
 * long way. Using both makes the shared position jump. A fix is therefore dropped when it is much
 * less accurate than the last accepted one, unless that one has become old.
 */
class FixFilter(
    private val maxAgeMs: Long = 30_000,
    private val goodEnoughM: Double = 25.0,
    private val worseFactor: Double = 2.0,
) {
    private var lastTimeMs = 0L
    private var lastAccuracyM = Double.MAX_VALUE
    private var hasLast = false

    /**
     * @param timeMs monotonic time of the fix in milliseconds
     * @param accuracyM reported horizontal accuracy in metres; zero or negative means unknown
     * @return true if the fix should be used
     */
    fun accept(timeMs: Long, accuracyM: Double): Boolean {
        val acc = if (accuracyM > 0 && !accuracyM.isNaN()) accuracyM else UNKNOWN_M
        val ok = !hasLast ||
            timeMs - lastTimeMs > maxAgeMs ||
            acc <= goodEnoughM ||
            acc <= lastAccuracyM * worseFactor
        if (ok) {
            hasLast = true
            lastTimeMs = timeMs
            lastAccuracyM = acc
        }
        return ok
    }

    companion object {
        const val UNKNOWN_M = 10_000.0
    }
}

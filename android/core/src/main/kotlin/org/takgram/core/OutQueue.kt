package org.takgram.core

/**
 * Rate-limited outbound queue for Telegram calls. Ported from the Python package's ratequeue.py.
 *
 * Items for the same (chat, key) are coalesced: a newer edit replaces an older one that has not
 * been sent yet, keeping the older one's place in line. Each chat has a minimum interval and there
 * is a global one. A flood wait from Telegram pauses that chat and the item is retried, never
 * dropped. Time is passed in, so the queue itself never sleeps and is tested without a clock.
 */
class OutQueue<T>(
    private val chatIntervalMs: Long = 3_100,
    private val globalIntervalMs: Long = 50,
    private val maxTries: Int = 5,
) {
    class Item<T> internal constructor(
        val chat: Long,
        val key: Any?,
        val payload: T,
        val priority: Int,
        internal val seq: Long,
        internal val ident: Any,
    ) {
        var tries = 0
            internal set
    }

    private val items = LinkedHashMap<Any, Item<T>>()
    private val readyAt = HashMap<Long, Long>()
    private var globalReady = 0L
    private var seq = 0L

    val size: Int @Synchronized get() = items.size

    /** [priority] 0 is urgent. A null [key] never coalesces. */
    @Synchronized
    fun submit(chat: Long, key: Any?, payload: T, priority: Int = 1) {
        val s = seq++
        val ident: Any = if (key != null) chat to key else Triple(chat, "_", s)
        val old = items[ident]
        items[ident] = Item(chat, key, payload, if (old != null) minOf(priority, old.priority) else priority, old?.seq ?: s, ident)
    }

    /** Removes and returns the next item that may be sent now, or null if none is ready. */
    @Synchronized
    fun take(nowMs: Long): Item<T>? {
        var best: Item<T>? = null
        for (item in items.values) {
            if (maxOf(readyAt[item.chat] ?: 0L, globalReady) > nowMs) continue
            val b = best
            if (b == null || item.priority < b.priority || (item.priority == b.priority && item.seq < b.seq)) best = item
        }
        val chosen = best ?: return null
        items.remove(chosen.ident)
        readyAt[chosen.chat] = nowMs + chatIntervalMs
        globalReady = nowMs + globalIntervalMs
        return chosen
    }

    /** When the next item becomes ready, or null if the queue is empty. */
    @Synchronized
    fun nextReadyMs(nowMs: Long): Long? {
        if (items.isEmpty()) return null
        return items.values.minOf { maxOf(readyAt[it.chat] ?: 0L, globalReady) }.coerceAtLeast(nowMs)
    }

    /** Telegram asked us to wait. The item goes back unless a newer one for the same key arrived. */
    @Synchronized
    fun rateLimited(item: Item<T>, nowMs: Long, waitMs: Long) {
        readyAt[item.chat] = nowMs + waitMs + 500
        items.putIfAbsent(item.ident, item)
    }

    /** The send failed for another reason. Retries with back-off; returns false once it is given up. */
    @Synchronized
    fun failed(item: Item<T>, nowMs: Long): Boolean {
        item.tries++
        if (item.tries >= maxTries) return false
        items.putIfAbsent(item.ident, item)
        readyAt[item.chat] = nowMs + minOf(30_000L, 1000L shl item.tries)
        return true
    }
}

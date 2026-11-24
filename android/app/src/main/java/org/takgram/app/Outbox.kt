package org.takgram.app

import android.content.SharedPreferences
import android.os.SystemClock
import org.takgram.core.Caption
import org.takgram.core.CotEvent
import org.takgram.core.CotTypes
import org.takgram.core.OutQueue
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Posts what the user created in ATAK to the linked chat, one Telegram call at a time and no
 * faster than the queue allows. An object is one message, edited in place when the object changes.
 */
class Outbox(private val tg: Tg, private val prefs: SharedPreferences, private val renderer: CardRenderer) {

    private sealed interface Job {
        data class Card(val ev: CotEvent) : Job
        data class Text(val text: String) : Job
    }

    private val queue = OutQueue<Job>()
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var busy = false

    @Volatile var posted = 0
        private set

    fun card(chatId: Long, ev: CotEvent) {
        queue.submit(chatId, ev.uid, Job.Card(ev), if (CotTypes.isAlert(ev.type)) 0 else 1)
        exec.execute { pump() }
    }

    fun text(chatId: Long, text: String) {
        queue.submit(chatId, null, Job.Text(text))
        exec.execute { pump() }
    }

    /** Runs on [exec] only. */
    private fun pump() {
        if (busy) return
        val now = SystemClock.elapsedRealtime()
        val item = queue.take(now)
        if (item == null) {
            queue.nextReadyMs(now)?.let { exec.schedule({ pump() }, it - now + 5, TimeUnit.MILLISECONDS) }
            return
        }
        busy = true
        when (val job = item.payload) {
            is Job.Text -> tg.sendText(item.chat, job.text) { id, error -> finish(item, if (id != null) null else error) }
            is Job.Card -> postCard(item, job.ev)
        }
    }

    private fun postCard(item: OutQueue.Item<Job>, ev: CotEvent) {
        val text = Caption.card(ev, Caption.PHOTO_LIMIT) ?: Caption.lines(ev).joinToString("\n")
        val key = messageKey(item.chat, ev.uid)
        val existing = prefs.getLong(key, 0L)
        val remember = { id: Long?, error: String? ->
            if (id != null) prefs.edit().putLong(key, id).apply()
            finish(item, if (id != null) null else error)
        }
        // Runs on the outbox thread, so fetching map tiles here holds up nothing else.
        val picture = try {
            renderer.render(ev)
        } catch (e: Exception) {
            null
        }
        if (picture == null) {
            tg.sendCardText(item.chat, text, remember)
            return
        }
        val sendNew = { tg.sendCard(item.chat, picture.path, CardRenderer.W, CardRenderer.H, text, remember) }
        if (existing == 0L) {
            sendNew()
        } else {
            tg.editCard(item.chat, existing, picture.path, CardRenderer.W, CardRenderer.H, text) { error ->
                when {
                    error == null || error.contains("not modified", ignoreCase = true) -> finish(item, null)
                    retryAfterMs(error) != null -> finish(item, error)
                    // Deleted, a text card from an older build, or past Telegram's edit window: post a fresh card.
                    else -> sendNew()
                }
            }
        }
    }

    private fun finish(item: OutQueue.Item<Job>, error: String?) = exec.execute {
        busy = false
        val now = SystemClock.elapsedRealtime()
        val wait = error?.let { retryAfterMs(it) }
        when {
            error == null -> posted++
            wait != null -> {
                queue.rateLimited(item, now, wait)
                tg.note("Telegram asked TAKgram to slow down for ${wait / 1000} s")
            }
            !queue.failed(item, now) -> tg.note("gave up posting to the chat: $error")
        }
        pump()
    }

    /** TDLib reports flood limits as "429 Too Many Requests: retry after N". */
    private fun retryAfterMs(error: String): Long? =
        RETRY.find(error)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(1, 3600)?.times(1000)

    private fun messageKey(chatId: Long, uid: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
        return tg.key("obj.$chatId." + digest.take(10).joinToString("") { "%02x".format(it) })
    }

    companion object {
        private val RETRY = Regex("retry after (\\d+)", RegexOption.IGNORE_CASE)
    }
}

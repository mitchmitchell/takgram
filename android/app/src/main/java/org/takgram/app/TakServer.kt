package org.takgram.app

import android.util.Log
import org.takgram.core.CotBuild
import org.takgram.core.CotEvent
import org.takgram.core.CotException
import org.takgram.core.CotParse
import org.takgram.core.StreamSplitter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/**
 * The small local server ATAK connects to, so TAKgram can see what the user creates in ATAK.
 *
 * It listens on 127.0.0.1 only, speaks plain streaming XML CoT, and answers ATAK's pings (ATAK
 * drops a server that stays silent for about 25 seconds). It is not a TAK Server: no TLS, no
 * relaying, and nothing outside the phone can reach it.
 */
class TakServer(
    private val port: Int,
    private val onEvent: (CotEvent) -> Unit,
    private val onClients: (Int) -> Unit,
    private val log: (String) -> Unit,
) {
    @Volatile private var server: ServerSocket? = null
    private val clients = CopyOnWriteArraySet<Socket>()

    val clientCount: Int get() = clients.size

    fun start() {
        if (server != null) return
        val s = try {
            ServerSocket(port, 4, LOOPBACK)
        } catch (e: Exception) {
            log("could not open the local server on port $port: ${e.message}")
            return
        }
        server = s
        thread(name = "tak-accept", isDaemon = true) {
            while (!s.isClosed) {
                val c = try {
                    s.accept()
                } catch (e: SocketException) {
                    break
                }
                if (clients.size >= MAX_CLIENTS) {
                    c.close()
                    continue
                }
                clients.add(c)
                onClients(clients.size)
                thread(name = "tak-client", isDaemon = true) { serve(c) }
            }
        }
    }

    fun stop() {
        server?.let { runCatching { it.close() } }
        server = null
        for (c in clients) runCatching { c.close() }
        clients.clear()
        onClients(0)
    }

    private fun serve(c: Socket) {
        val split = StreamSplitter()
        val buf = ByteArray(16 * 1024)
        try {
            c.soTimeout = IDLE_TIMEOUT_MS
            val input = c.getInputStream()
            val output = c.getOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (raw in split.feed(buf, n)) {
                    val ev = try {
                        CotParse.parse(raw)
                    } catch (e: CotException) {
                        continue
                    }
                    if (ev.type == PING) {
                        // Keepalive only: answered here and never passed on.
                        output.write(CotBuild.pong().toByteArray(Charsets.UTF_8))
                        output.flush()
                    } else {
                        try {
                            onEvent(ev)
                        } catch (e: Exception) {
                            Log.e(Tg.TAG, "failed on an event from ATAK", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // connection dropped or timed out
        } finally {
            runCatching { c.close() }
            clients.remove(c)
            onClients(clients.size)
        }
    }

    companion object {
        const val PING = "t-x-c-t"
        private const val MAX_CLIENTS = 4
        private const val IDLE_TIMEOUT_MS = 120_000
        private val LOOPBACK: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    }
}

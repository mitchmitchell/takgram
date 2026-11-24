package org.takgram.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Sends CoT XML to ATAK's local UDP input. ATAK listens on 0.0.0.0:4242 by default. */
object CotUdpSender {
    const val PORT = 4242

    /**
     * Must be IPv4 127.0.0.1. On Android InetAddress.getLoopbackAddress() is IPv6 ::1, and ATAK's
     * input socket is IPv4-only, so datagrams sent there are silently lost (seen on ATAK-CIV 5.8).
     */
    val LOOPBACK_V4: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    private const val MAX_DATAGRAM = 60_000

    fun send(xml: String, port: Int = PORT) {
        val bytes = xml.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_DATAGRAM) { "event too large for one datagram" }
        DatagramSocket().use { s ->
            s.send(DatagramPacket(bytes, bytes.size, LOOPBACK_V4, port))
        }
    }
}

package org.takgram.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class ConnectionPackageTest {
    private fun entries(zip: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = String(z.readBytes(), Charsets.UTF_8)
            }
        }
        return out
    }

    @Test
    fun packageAddsTheLocalServerAsAStreamingConnection() {
        val files = entries(ConnectionPackage.build())
        assertEquals(setOf("takgram.pref", "MANIFEST/manifest.xml"), files.keys)
        val pref = files.getValue("takgram.pref")
        assertTrue(pref.contains(">127.0.0.1:8099:tcp</entry>"))
        assertTrue(pref.contains("name=\"cot_streams\""))
        assertTrue(pref.contains(">TAKgram</entry>"))
        val manifest = files.getValue("MANIFEST/manifest.xml")
        assertTrue(manifest.contains("zipEntry=\"takgram.pref\""))
        assertTrue(manifest.contains("name=\"onReceiveDelete\" value=\"true\""))
    }

    @Test
    fun namesAreEscapedAndPongIsWellFormed() {
        val pref = entries(ConnectionPackage.build(name = "A<b>&")).getValue("takgram.pref")
        assertTrue(pref.contains(">A&lt;b&gt;&amp;</entry>"))
        val pong = CotParse.parse(CotBuild.pong().toByteArray())
        assertEquals("t-x-c-t-r", pong.type)
        assertTrue(pong.stale.isAfter(pong.time))
    }
}

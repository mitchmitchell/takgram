package org.takgram.core

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The TAK data package that adds TAKgram's local server to ATAK as a streaming connection, so the
 * user does not have to type a host and port. Ported from the Python package's datapackage.py;
 * ATAK-CIV 5.8 imported that package and connected at once.
 */
object ConnectionPackage {
    const val HOST = "127.0.0.1"
    const val PORT = 8099
    const val FILE_NAME = "takgram-connection.zip"

    fun build(host: String = HOST, port: Int = PORT, name: String = "TAKgram"): ByteArray {
        require(port in 1..65535) { "bad port" }
        val e = CotBuild::escape
        val pref = "<?xml version='1.0' encoding='ASCII' standalone='yes'?>\n<preferences>" +
            "<preference version=\"1\" name=\"cot_streams\">" +
            "<entry key=\"count\" class=\"class java.lang.Integer\">1</entry>" +
            "<entry key=\"description0\" class=\"class java.lang.String\">${e(name)}</entry>" +
            "<entry key=\"enabled0\" class=\"class java.lang.Boolean\">true</entry>" +
            "<entry key=\"connectString0\" class=\"class java.lang.String\">${e(host)}:$port:tcp</entry>" +
            "</preference></preferences>"
        val manifest = "<MissionPackageManifest version=\"2\"><Configuration>" +
            "<Parameter name=\"uid\" value=\"${UUID.randomUUID()}\"/>" +
            "<Parameter name=\"name\" value=\"${e("$name connection")}\"/>" +
            "<Parameter name=\"onReceiveDelete\" value=\"true\"/>" +
            "</Configuration><Contents><Content ignore=\"false\" zipEntry=\"takgram.pref\"/></Contents>" +
            "</MissionPackageManifest>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("takgram.pref"))
            z.write(pref.toByteArray(Charsets.US_ASCII))
            z.closeEntry()
            z.putNextEntry(ZipEntry("MANIFEST/manifest.xml"))
            z.write(manifest.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return out.toByteArray()
    }
}

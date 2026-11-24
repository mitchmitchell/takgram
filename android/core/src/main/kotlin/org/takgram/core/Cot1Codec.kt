package org.takgram.core

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The machine line that carries a CoT event inside a Telegram message:
 *
 *     #cot1 <base64url, no padding, of raw DEFLATE of the UTF-8 event XML>
 *
 * It is always the last line of the message. Decoding caps both the encoded and the inflated size,
 * so a hostile message cannot make the app allocate without bound.
 */
object Cot1Codec {
    const val TAG = "#cot1"
    const val MAX_ENCODED_CHARS = 8 * 1024
    const val MAX_XML_BYTES = CotParse.MAX_EVENT_BYTES

    fun encode(xml: String): String {
        val input = xml.toByteArray(Charsets.UTF_8)
        require(input.size <= MAX_XML_BYTES) { "event too large" }
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(input.size / 2 + 16)
            val buf = ByteArray(4096)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            return "$TAG " + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        } finally {
            deflater.end()
        }
    }

    /** The payload of the last line if it is a #cot1 line, else null. */
    fun find(text: String): String? {
        val last = text.trimEnd().substringAfterLast('\n').trim()
        if (!last.startsWith("$TAG ")) return null
        return last.substring(TAG.length + 1).trim()
    }

    /** @return the event XML bytes; throws [CotException] on anything malformed or oversized. */
    fun decode(payload: String): ByteArray {
        if (payload.isEmpty() || payload.length > MAX_ENCODED_CHARS) throw CotException("bad #cot1 length")
        if (!payload.all { (it in 'A'..'Z') || (it in 'a'..'z') || (it in '0'..'9') || it == '-' || it == '_' }) {
            throw CotException("bad #cot1 characters")
        }
        val packed = try {
            Base64.getUrlDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            throw CotException("bad #cot1 base64")
        }
        val inflater = Inflater(true)
        try {
            inflater.setInput(packed)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw CotException("truncated #cot1")
                }
                out.write(buf, 0, n)
                if (out.size() > MAX_XML_BYTES) throw CotException("#cot1 inflates too large")
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw CotException("bad #cot1 data")
        } finally {
            inflater.end()
        }
    }
}

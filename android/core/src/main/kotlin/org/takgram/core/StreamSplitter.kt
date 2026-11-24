package org.takgram.core

/**
 * Splits a TCP byte stream from a TAK client into single CoT events, on the closing event tag.
 * Anything before an event's opening tag (XML declarations, whitespace) is dropped. If a peer sends
 * more than [maxBytes] without completing an event, the buffer is discarded.
 */
class StreamSplitter(private val maxBytes: Int = CotParse.MAX_EVENT_BYTES * 4) {
    private var buf = ByteArray(8192)
    private var len = 0

    fun feed(data: ByteArray, count: Int = data.size): List<ByteArray> {
        if (len + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + count))
        System.arraycopy(data, 0, buf, len, count)
        len += count

        val out = ArrayList<ByteArray>()
        var from = 0
        while (true) {
            val end = indexOf(END, from)
            if (end < 0) break
            val stop = end + END.size
            val start = indexOf(START, from, stop)
            if (start >= 0) out.add(buf.copyOfRange(start, stop))
            from = stop
        }
        if (from > 0) {
            System.arraycopy(buf, from, buf, 0, len - from)
            len -= from
        }
        if (len > maxBytes) len = 0
        return out
    }

    private fun indexOf(needle: ByteArray, from: Int, limit: Int = len): Int {
        var i = from
        val last = limit - needle.size
        while (i <= last) {
            var j = 0
            while (j < needle.size && buf[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    companion object {
        private val START = "<event".toByteArray(Charsets.US_ASCII)
        private val END = "</event>".toByteArray(Charsets.US_ASCII)
    }
}

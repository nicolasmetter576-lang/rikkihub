package me.rerere.rikkihub.web.tls

import java.io.ByteArrayOutputStream

/**
 * Minimal DER (Distinguished Encoding Rules) writer + reader, sufficient to
 * build an X.509 v3 certificate and to extract the subjectPublicKey BIT STRING
 * from an `X.509`-encoded SubjectPublicKeyInfo.
 *
 * Pure JVM — no Android APIs — so certificate construction is fully covered by
 * JVM unit tests.
 */
internal object Der {

    fun writer() = Writer()

    class Writer {
        private val out = ByteArrayOutputStream()

        private fun writeTag(tag: Int, content: ByteArray) {
            out.write(tag)
            writeLength(content.size)
            out.write(content)
        }

        private fun writeLength(len: Int) {
            when {
                len < 0x80 -> out.write(len)
                len < 0x100 -> {
                    out.write(0x81)
                    out.write(len)
                }
                len < 0x10000 -> {
                    out.write(0x82)
                    out.write(len shr 8)
                    out.write(len and 0xff)
                }
                else -> {
                    out.write(0x83)
                    out.write(len shr 16)
                    out.write((len shr 8) and 0xff)
                    out.write(len and 0xff)
                }
            }
        }

        /** Tag number in the lower 5 bits plus class/constructed bits already applied. */
        fun tlv(tag: Int, content: ByteArray): Writer {
            writeTag(tag, content)
            return this
        }

        fun seq(vararg parts: ByteArray): Writer {
            tlv(0x30, concat(*parts))
            return this
        }

        fun set(vararg parts: ByteArray): Writer {
            tlv(0x31, concat(*parts))
            return this
        }

        fun context(tagNumber: Int, content: ByteArray, constructed: Boolean = true): Writer {
            val tag = 0x80 or (if (constructed) 0x20 else 0x00) or tagNumber
            tlv(tag, content)
            return this
        }

        fun intBytes(v: ByteArray): Writer {
            // DER INTEGER: big-endian two's complement; prepend 0x00 when the
            // MSB would read as negative; strip redundant leading zeros.
            var bytes = v.dropWhile { it == 0.toByte() }.toByteArray()
            if (bytes.isEmpty()) bytes = byteArrayOf(0)
            if (bytes[0].toInt() and 0x80 != 0) {
                bytes = byteArrayOf(0) + bytes
            }
            tlv(0x02, bytes)
            return this
        }

        fun oid(vararg arcs: Int): Writer {
            val body = ByteArrayOutputStream()
            body.write(arcs[0] * 40 + arcs[1])
            for (i in 2 until arcs.size) {
                var arc = arcs[i]
                // base-128, most-significant group first, continuation bit on
                // all but the last byte
                val stack = ArrayList<Int>()
                stack.add(arc and 0x7f)
                arc = arc ushr 7
                while (arc != 0) {
                    stack.add(0x80 or (arc and 0x7f))
                    arc = arc ushr 7
                }
                for (j in stack.indices.reversed()) body.write(stack[j])
            }
            tlv(0x06, body.toByteArray())
            return this
        }

        fun utf8(s: String): Writer {
            tlv(0x0c, s.toByteArray(Charsets.UTF_8))
            return this
        }

        fun printable(s: String): Writer {
            tlv(0x13, s.toByteArray(Charsets.US_ASCII))
            return this
        }

        fun ia5(s: String): Writer {
            tlv(0x16, s.toByteArray(Charsets.US_ASCII))
            return this
        }

        fun utcTime(yyMMddHHmmssZ: String): Writer {
            tlv(0x17, yyMMddHHmmssZ.toByteArray(Charsets.US_ASCII))
            return this
        }

        fun generalizedTime(yyyyMMddHHmmssZ: String): Writer {
            tlv(0x18, yyyyMMddHHmmssZ.toByteArray(Charsets.US_ASCII))
            return this
        }

        fun octetString(b: ByteArray): Writer {
            tlv(0x04, b)
            return this
        }

        fun bitString(b: ByteArray): Writer {
            tlv(0x03, byteArrayOf(0x00) + b) // 0 unused bits
            return this
        }

        fun boolean(v: Boolean): Writer {
            tlv(0x01, byteArrayOf(if (v) 0xff.toByte() else 0x00))
            return this
        }

        fun nullValue(): Writer {
            tlv(0x05, byteArrayOf())
            return this
        }

        fun explicit(contextTag: Int, innerTlv: ByteArray): Writer =
            context(contextTag, innerTlv, constructed = true)

        fun encoded(): ByteArray = out.toByteArray()
    }

    /** Read one TLV; returns (tag, contentBytes) or null if data exhausted. */
    data class Tlv(val tag: Int, val content: ByteArray, val tlvBytes: ByteArray)

    fun readTlv(data: ByteArray, offset: Int = 0): Pair<Tlv, Int>? {
        if (offset < 0 || offset >= data.size - 1) return null
        val tag = data[offset].toInt() and 0xff
        var i = offset + 1
        var len = data[i].toInt() and 0xff
        i++
        if (len and 0x80 != 0) {
            val num = len and 0x7f
            if (num !in 1..3 || i + num > data.size) return null
            len = 0
            repeat(num) {
                len = (len shl 8) or (data[i].toInt() and 0xff)
                i++
            }
        }
        if (len < 0 || len > data.size - i) return null
        val content = data.copyOfRange(i, i + len)
        val tlvBytes = data.copyOfRange(offset, i + len)
        return Tlv(tag, content, tlvBytes) to (i + len)
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}

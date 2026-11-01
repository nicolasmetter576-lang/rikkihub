package me.rerere.ai.provider.providers.devin

import me.rerere.ai.provider.providers.devin.DevinTestGolden.hexToBytes
import me.rerere.ai.provider.providers.devin.DevinTestGolden.toHex
import me.rerere.ai.provider.providers.devin.ProtoWire.ProtoWireException
import me.rerere.ai.provider.providers.devin.ProtoWire.appendBytes
import me.rerere.ai.provider.providers.devin.ProtoWire.appendFixed32
import me.rerere.ai.provider.providers.devin.ProtoWire.appendFixed64
import me.rerere.ai.provider.providers.devin.ProtoWire.appendString
import me.rerere.ai.provider.providers.devin.ProtoWire.appendTag
import me.rerere.ai.provider.providers.devin.ProtoWire.appendVarint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ProtoWireTest {

    private fun encode(block: ByteArrayOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        out.block()
        return out.toByteArray()
    }

    @Test
    fun `varint round trip`() {
        val cases = listOf(0L, 1L, 127L, 128L, 300L, 65535L, 1L shl 32, Long.MAX_VALUE, -1L)
        for (v in cases) {
            val bytes = encode { appendVarint(v) }
            val reader = ProtoWire.Reader(bytes)
            assertEquals("varint $v", v, reader.consumeVarint())
            assertTrue(reader.exhausted)
        }
    }

    @Test
    fun `varint canonical encodings`() {
        assertEquals("00", encode { appendVarint(0) }.toHex())
        assertEquals("7f", encode { appendVarint(127) }.toHex())
        assertEquals("8001", encode { appendVarint(128) }.toHex())
        assertEquals("ac02", encode { appendVarint(300) }.toHex())
        // -1 (uint64 max) → 10 字节
        assertEquals(
            "ffffffffffffffffff01",
            encode { appendVarint(-1) }.toHex(),
        )
    }

    @Test
    fun `varint rejects truncated and overlong`() {
        assertThrows(ProtoWireException::class.java) {
            ProtoWire.Reader("80".hexToBytes()).consumeVarint()
        }
        // 11 字节且全部带 continuation bit → 溢出
        assertThrows(ProtoWireException::class.java) {
            ProtoWire.Reader("ffffffffffffffffffffff".hexToBytes()).consumeVarint()
        }
    }

    @Test
    fun `tag encode decode`() {
        val bytes = encode { appendTag(15, ProtoWire.BYTES) }
        val (num, typ) = ProtoWire.Reader(bytes).consumeTag()
        assertEquals(15, num)
        assertEquals(ProtoWire.BYTES, typ)
        assertEquals("7a", bytes.toHex())
    }

    @Test
    fun `tag rejects zero field number`() {
        assertThrows(ProtoWireException::class.java) {
            ProtoWire.Reader(byteArrayOf(0)).consumeTag()
        }
    }

    @Test
    fun `bytes string round trip`() {
        val bytes = encode {
            appendTag(3, ProtoWire.BYTES)
            appendString("你好 devin")
            appendTag(6, ProtoWire.BYTES)
            appendBytes(byteArrayOf(0x00, 0x01, 0x02))
        }
        val reader = ProtoWire.Reader(bytes)
        assertEquals(3 to ProtoWire.BYTES, reader.consumeTag())
        assertEquals("你好 devin", reader.consumeBytes().toString(Charsets.UTF_8))
        assertEquals(6 to ProtoWire.BYTES, reader.consumeTag())
        assertArrayEquals(byteArrayOf(0x00, 0x01, 0x02), reader.consumeBytes())
        assertTrue(reader.exhausted)
    }

    @Test
    fun `fixed32 fixed64 little endian`() {
        val f32 = encode { appendFixed32(0xDEADBEEFu.toLong()) }
        assertEquals("efbeadde", f32.toHex())
        val reader32 = ProtoWire.Reader(f32)
        assertEquals(0xDEADBEEFL, reader32.consumeFixed32())

        val f64 = encode { appendFixed64(java.lang.Double.doubleToRawLongBits(1.0)) }
        assertEquals("000000000000f03f", f64.toHex())
        val reader64 = ProtoWire.Reader(f64)
        assertEquals(1.0, java.lang.Double.longBitsToDouble(reader64.consumeFixed64()), 0.0)
    }

    @Test
    fun `fixed64 float32 cast pattern`() {
        // f8.8 的 0.95 是 float64(float32(0.95))
        val expected = java.lang.Double.doubleToRawLongBits(0.95f.toDouble())
        val bytes = encode { appendFixed64(expected) }
        assertEquals(expected, ProtoWire.Reader(bytes).consumeFixed64())
        assertEquals(0.949999988079071044921875, 0.95f.toDouble(), 0.0)
    }

    @Test
    fun `consumeFieldValue skips all wire types`() {
        val bytes = encode {
            appendTag(1, ProtoWire.VARINT)
            appendVarint(150)
            appendTag(2, ProtoWire.FIXED64)
            appendFixed64(123456789)
            appendTag(3, ProtoWire.BYTES)
            appendString("skip me")
            appendTag(4, ProtoWire.FIXED32)
            appendFixed32(42)
            appendTag(5, ProtoWire.START_GROUP)
            appendTag(1, ProtoWire.VARINT)
            appendVarint(7)
            appendTag(5, ProtoWire.END_GROUP)
            appendTag(6, ProtoWire.VARINT)
            appendVarint(99)
        }
        val reader = ProtoWire.Reader(bytes)
        // f1 varint
        assertEquals(1 to ProtoWire.VARINT, reader.consumeTag())
        reader.consumeFieldValue(1, ProtoWire.VARINT)
        // f2 fixed64
        assertEquals(2 to ProtoWire.FIXED64, reader.consumeTag())
        reader.consumeFieldValue(2, ProtoWire.FIXED64)
        // f3 bytes
        assertEquals(3 to ProtoWire.BYTES, reader.consumeTag())
        reader.consumeFieldValue(3, ProtoWire.BYTES)
        // f4 fixed32
        assertEquals(4 to ProtoWire.FIXED32, reader.consumeTag())
        reader.consumeFieldValue(4, ProtoWire.FIXED32)
        // f5 group
        assertEquals(5 to ProtoWire.START_GROUP, reader.consumeTag())
        reader.consumeFieldValue(5, ProtoWire.START_GROUP)
        // f6 remains
        assertEquals(6 to ProtoWire.VARINT, reader.consumeTag())
        assertEquals(99, reader.consumeVarint())
        assertTrue(reader.exhausted)
    }

    @Test
    fun `consumeBytes rejects overlong length`() {
        assertThrows(ProtoWireException::class.java) {
            ProtoWire.Reader("0a".hexToBytes() + "ff".hexToBytes()).let {
                it.consumeTag()
                it.consumeBytes()
            }
        }
    }
}

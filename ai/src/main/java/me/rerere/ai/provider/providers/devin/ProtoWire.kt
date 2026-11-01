package me.rerere.ai.provider.providers.devin

import java.io.ByteArrayOutputStream

/**
 * 最小的 protobuf wire-format 编解码, 等价 Go 的 `google.golang.org/protobuf/encoding/protowire`.
 *
 * - append*: 往 [ByteArrayOutputStream] 追加编码字节
 * - [Reader]: 在字节数组上按序 consume
 *
 * 只覆盖 Devin 协议用到的类型: varint / fixed32 / fixed64 / bytes, 以及组类型的跳过.
 */
object ProtoWire {
    const val VARINT: Int = 0
    const val FIXED32: Int = 5
    const val FIXED64: Int = 1
    const val BYTES: Int = 2
    const val START_GROUP: Int = 3
    const val END_GROUP: Int = 4

    /** protobuf 合法 field number 上限 (2^29 - 1) */
    const val MAX_FIELD_NUMBER: Int = (1 shl 29) - 1

    /** 64 位 varint 的最大编码长度 */
    private const val MAX_VARINT_LEN: Int = 10

    class ProtoWireException(message: String) : Exception(message)

    // ============ append (encoder) ============

    fun ByteArrayOutputStream.appendTag(fieldNumber: Int, wireType: Int) {
        appendVarint(((fieldNumber.toLong() shl 3) or wireType.toLong()))
    }

    fun ByteArrayOutputStream.appendVarint(value: Long) {
        // 按无符号 64 位编码: 高位还有 bit 时持续输出 (负数 = uint64 大值, 与 Go uint64 一致)
        var v = value
        while (v ushr 7 != 0L) {
            write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        write(v.toInt())
    }

    fun ByteArrayOutputStream.appendBytes(bytes: ByteArray) {
        appendVarint(bytes.size.toLong())
        write(bytes, 0, bytes.size)
    }

    fun ByteArrayOutputStream.appendString(value: String) {
        appendBytes(value.toByteArray(Charsets.UTF_8))
    }

    fun ByteArrayOutputStream.appendFixed32(value: Long) {
        var v = value
        repeat(4) {
            write(v.toInt())
            v = v ushr 8
        }
    }

    fun ByteArrayOutputStream.appendFixed64(value: Long) {
        var v = value
        repeat(8) {
            write(v.toInt())
            v = v ushr 8
        }
    }

    // ============ consume (decoder) ============

    /** 顺序读取一个编码好的 protobuf 消息. 所有 consume* 失败时抛 [ProtoWireException]. */
    class Reader(private val buf: ByteArray, private var pos: Int = 0) {
        val remaining: Int get() = buf.size - pos
        val exhausted: Boolean get() = pos >= buf.size
        val offset: Int get() = pos

        private fun err(message: String): Nothing = throw ProtoWireException(message)

        /**
         * 读取一个 varint. 镜像 Go protowire.ConsumeVarint:
         * 最多 10 字节; 第 10 字节可以是任意 < 0x80 的值 (超出的位随移位丢弃),
         * 超过 10 字节未终结才算溢出错误.
         */
        fun consumeVarint(): Long {
            var result = 0L
            var shift = 0
            var i = pos
            while (i < buf.size && i - pos < MAX_VARINT_LEN) {
                val b = buf[i].toLong() and 0xFF
                if (b and 0x80 == 0L) {
                    result = result or (b shl shift)
                    pos = i + 1
                    return result
                }
                result = result or ((b and 0x7F) shl shift)
                shift += 7
                i++
            }
            if (i - pos >= MAX_VARINT_LEN) {
                err("varint overflows a 64-bit integer")
            }
            err("truncated varint at offset $pos")
        }

        /** 返回 (fieldNumber, wireType). 等价 protowire.ConsumeTag. */
        fun consumeTag(): Pair<Int, Int> {
            val v = consumeVarint()
            val num = (v ushr 3).toInt()
            if (num < 1 || num > MAX_FIELD_NUMBER) {
                err("invalid field number: $num")
            }
            return num to (v and 0x7).toInt()
        }

        fun consumeBytes(): ByteArray {
            val len = consumeVarint()
            if (len < 0 || len > remaining) {
                err("invalid bytes length $len at offset $pos")
            }
            val end = pos + len.toInt()
            val out = buf.copyOfRange(pos, end)
            pos = end
            return out
        }

        fun consumeFixed32(): Long {
            if (remaining < 4) {
                err("truncated fixed32 at offset $pos")
            }
            var v = 0L
            repeat(4) { i ->
                v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
            }
            pos += 4
            return v
        }

        fun consumeFixed64(): Long {
            if (remaining < 8) {
                err("truncated fixed64 at offset $pos")
            }
            var v = 0L
            repeat(8) { i ->
                v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
            }
            pos += 8
            return v
        }

        /**
         * 跳过当前字段的值 (tag 已被消费, [wireType] 来自 [consumeTag]).
         * 等价 protowire.ConsumeFieldValue: StartGroup 会递归跳过嵌套字段,
         * 直到匹配的 EndGroup (tag 本身也被消费).
         */
        fun consumeFieldValue(fieldNumber: Int, wireType: Int) {
            when (wireType) {
                VARINT -> consumeVarint()
                FIXED32 -> consumeFixed32()
                FIXED64 -> consumeFixed64()
                BYTES -> consumeBytes()
                START_GROUP -> {
                    while (true) {
                        if (exhausted) {
                            err("truncated group at offset $pos")
                        }
                        val (num, typ) = consumeTag()
                        if (typ == END_GROUP) {
                            if (num != fieldNumber) {
                                err("end-group mismatch: expected $fieldNumber, got $num")
                            }
                            break
                        }
                        consumeFieldValue(num, typ)
                    }
                }

                END_GROUP -> err("unexpected end-group marker for field $fieldNumber")
                else -> err("unsupported wire type $wireType")
            }
        }
    }
}

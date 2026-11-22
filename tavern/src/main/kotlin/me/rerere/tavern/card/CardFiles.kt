package me.rerere.tavern.card

/** 一个导入的角色：规范化后的卡，加上头像和 CHARX 里带的资源。 */
class ImportedCharacter(
    val card: CharacterCard,
    /** 卡面图片字节；没有时为 null。 */
    val avatar: ByteArray?,
    /** 卡面的小写扩展名（`png`、`jpg` ...）。 */
    val avatarExt: String?,
    /** CHARX 包内文件（路径 → 内容）；其他格式为空。 */
    val assets: Map<String, ByteArray>,
    /** `png`、`json`、`charx`，以及卡本身的 `v1`/`v2`/`v3`/`pygmalion`。 */
    val container: String,
    val cardFormat: String,
)

/** 角色卡文件的读入与写出：按内容（不看扩展名）认 PNG、CHARX（zip）、JSON。 */
object CardFiles {
    fun read(bytes: ByteArray): ImportedCharacter = unsetPrivate(readRaw(bytes))

    /** 导入的卡不带别人的“收藏”标记（酒馆的 `unsetPrivateFields`）。 */
    private fun unsetPrivate(c: ImportedCharacter): ImportedCharacter {
        if (!c.card.fav) return c
        return ImportedCharacter(c.card.withExtension("fav", kotlinx.serialization.json.JsonPrimitive(false)), c.avatar, c.avatarExt, c.assets, c.container, c.cardFormat)
    }

    private fun readRaw(bytes: ByteArray): ImportedCharacter {
        if (bytes.isEmpty()) throw CardFormatException("The file is empty")
        return when {
            PngCards.isPng(bytes) -> {
                val parsed = CardCodec.parse(PngCards.read(bytes))
                ImportedCharacter(parsed.card, PngCards.stripCard(bytes), "png", emptyMap(), "png", parsed.sourceFormat)
            }
            looksLikeZip(bytes) -> {
                val c = Charx.read(bytes)
                ImportedCharacter(c.card, c.avatar, c.avatarExt, c.assets, "charx", c.sourceFormat)
            }
            else -> {
                val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
                val parsed = CardCodec.parse(text)
                ImportedCharacter(parsed.card, null, null, emptyMap(), "json", parsed.sourceFormat)
            }
        }
    }

    /** PNG 卡：写入 `chara`（V2）和 `ccv3`（V3）。[image] 必须是 PNG；没有卡面时用占位图。 */
    fun toPng(card: CharacterCard, image: ByteArray?): ByteArray {
        val base = if (image != null && PngCards.isPng(image)) image else PngCards.placeholder(card.name)
        return PngCards.write(base, CardCodec.encodeV2(card).toString(), CardCodec.encodeV3(card).toString())
    }

    fun toJson(card: CharacterCard): ByteArray = CardCodec.encodeV3(card).toString().toByteArray(Charsets.UTF_8)

    fun toCharx(card: CharacterCard, avatar: ByteArray?, avatarExt: String?, assets: Map<String, ByteArray>): ByteArray =
        Charx.write(card, avatar, avatarExt, assets)

    private fun looksLikeZip(b: ByteArray): Boolean {
        if (b.size < 4) return false
        // 本地文件头、空压缩包的结尾记录，或前面带自解压程序（扫描前 1 MB 找文件头）
        if (b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() && (b[2] == 0x03.toByte() || b[2] == 0x05.toByte())) return true
        val limit = minOf(b.size - 4, 1 shl 20)
        for (i in 1..limit) if (b[i] == 0x50.toByte() && b[i + 1] == 0x4B.toByte() && b[i + 2] == 0x03.toByte() && b[i + 3] == 0x04.toByte() && b[0] == 'M'.code.toByte()) return true
        return false
    }
}

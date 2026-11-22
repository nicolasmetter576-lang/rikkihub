package me.rerere.tavern.card

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 从 CHARX 里读出的东西：卡、头像，以及 `data.assets` 里引用的所有压缩包内文件（路径已规整）。 */
class CharxContents(
    val card: CharacterCard,
    /** 头像图片的字节与小写扩展名（没有图标资源时为 null）。 */
    val avatar: ByteArray?,
    val avatarExt: String?,
    /** 压缩包内路径 → 文件内容，只含被 `assets` 引用的文件（图标也在里面）。 */
    val assets: Map<String, ByteArray>,
    val sourceFormat: String,
)

/**
 * CHARX：一个 zip，根目录有 `card.json`（V2 或 V3 的卡），`data.assets` 里 `uri` 写成 `embeded://路径`
 * （RisuAI 的拼写，`embedded://` 和 `__asset:` 也认）的项指向包内文件；`type=icon` 且名为 `main` 的是头像。
 * 照 `ST:src/charx.js`；读的时候限制条目数和解压大小，路径里有 `..` 的条目忽略。
 */
object Charx {
    private const val MAX_ENTRIES = 4000
    private const val MAX_CARD_JSON = 32 * 1024 * 1024
    private const val MAX_TOTAL = 256L * 1024 * 1024
    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "apng", "avif", "bmp", "jfif")
    private val EMBEDDED_PREFIXES = listOf("embeded://", "embedded://", "__asset:")

    fun read(bytes: ByteArray): CharxContents {
        val start = findZipStart(bytes)
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes, start, bytes.size - start)).use { zip ->
                var count = 0
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (++count > MAX_ENTRIES) throw CardFormatException("The CHARX archive has too many files")
                    val path = normalize(e.name) ?: continue
                    val data = readLimited(zip, if (path == "card.json") MAX_CARD_JSON.toLong() else MAX_TOTAL - total)
                    total += data.size
                    if (total > MAX_TOTAL) throw CardFormatException("The CHARX archive is too large when unpacked")
                    entries[path] = data
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw CardFormatException("The file is not a valid CHARX (zip) archive", e)
        }
        val cardBytes = entries["card.json"] ?: throw CardFormatException("The CHARX archive has no card.json")
        val parsed = CardCodec.parse(String(cardBytes, Charsets.UTF_8))
        val referenced = LinkedHashMap<String, ByteArray>()
        val icons = ArrayList<Triple<String, ByteArray, String>>()
        for (asset in parsed.card.assets) {
            val path = embeddedPath(asset["uri"]) ?: continue
            val data = entries[path] ?: continue
            referenced[path] = data
            val type = (asset["type"] as? JsonPrimitive)?.contentOrNull?.lowercase()
            val ext = extensionOf(asset, path)
            if (type == "icon" && ext in IMAGE_EXTS) {
                icons += Triple((asset["name"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: "", data, ext)
            }
        }
        val icon = icons.firstOrNull { it.first == "main" } ?: icons.firstOrNull()
        return CharxContents(parsed.card, icon?.second, icon?.third, referenced, parsed.sourceFormat)
    }

    /**
     * 写 CHARX。[assets] 是要带上的包内文件；没有 `icon` 资源而有 [avatar] 时，把头像加成 `main` 图标。
     */
    fun write(card: CharacterCard, avatar: ByteArray?, avatarExt: String?, assets: Map<String, ByteArray>): ByteArray {
        var cardOut = card
        val files = LinkedHashMap<String, ByteArray>(assets)
        val hasIcon = card.assets.any { (it["type"] as? JsonPrimitive)?.contentOrNull?.lowercase() == "icon" && embeddedPath(it["uri"]) in files }
        if (!hasIcon && avatar != null && avatarExt != null) {
            val path = "assets/icon/images/main.$avatarExt"
            files[path] = avatar
            val entry = JsonObject(mapOf(
                "type" to JsonPrimitive("icon"),
                "uri" to JsonPrimitive("embeded://$path"),
                "name" to JsonPrimitive("main"),
                "ext" to JsonPrimitive(avatarExt),
            ))
            cardOut = cardOut.withData("assets", JsonArray(listOf<JsonElement>(entry) + card.assets))
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("card.json"))
            zip.write(CardCodec.encodeV3(cardOut).toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((path, data) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun embeddedPath(uri: JsonElement?): String? {
        val text = (uri as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = text.lowercase()
        for (p in EMBEDDED_PREFIXES) if (lower.startsWith(p)) return normalize(text.substring(p.length))
        return null
    }

    private fun extensionOf(asset: JsonObject, path: String): String {
        val fromMeta = (asset["ext"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.removePrefix(".")
        if (!fromMeta.isNullOrEmpty()) return fromMeta
        return path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
    }

    /** 反斜杠换斜杠，去掉前导 `./` 和 `/`；含 `..` 或为空返回 null。 */
    private fun normalize(raw: String): String? {
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** 自解压 zip（前面带可执行代码）：从第一个本地文件头签名开始。 */
    private fun findZipStart(b: ByteArray): Int {
        for (i in 0..b.size - 4) {
            if (b[i] == 0x50.toByte() && b[i + 1] == 0x4B.toByte() && b[i + 2] == 0x03.toByte() && b[i + 3] == 0x04.toByte()) return i
        }
        throw CardFormatException("The file is not a CHARX (zip) archive")
    }

    private fun readLimited(zip: ZipInputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw CardFormatException("A file in the CHARX archive is too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

package me.rerere.rikkihub.data.ai.baseline

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * 旧管线请求基准（见 [LegacyRequestBaseline]）。golden：`src/test/resources/baseline/legacy-requests.json`。
 *
 * 重新录制（只有在**有意**改变旧管线输出、并且迁移报告里列了这项时才做）：
 * `LEGACY_BASELINE_RECORD=<绝对路径> ./gradlew :app:testDebugUnitTest --tests '*LegacyRequestBaselineTest*' -x :web:buildWebUi --offline`
 */
class LegacyRequestBaselineTest {
    private lateinit var savedZone: TimeZone
    private lateinit var savedLocale: Locale

    @Before fun pinEnvironment() {
        savedZone = TimeZone.getDefault()
        savedLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After fun restoreEnvironment() {
        TimeZone.setDefault(savedZone)
        Locale.setDefault(savedLocale)
    }

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    private fun runAll(viaBackup: Boolean = false): List<JsonObject> =
        runBlocking { LegacyRequestBaseline.cases().map { LegacyRequestBaseline.run(it, viaBackup) } }

    /**
     * 2.32.0 的夹具经设置迁移后走现在的请求管线：除了 `migration-differences.json` 里逐条列出理由的几例，
     * 发给 provider 的请求必须和 2.32.0 逐字相同；列出的几例要和那份期望逐字相同，并且确实还和旧版不同
     * （差异消失了就该把它从清单里删掉，不能留着当通行证）。
     */
    @Test fun migratedRequestsMatchTheLegacyBaselineExceptTheListedDifferences() {
        val actual = runAll()
        System.getenv("LEGACY_BASELINE_RECORD")?.takeIf { it.isNotBlank() }?.let { path ->
            val out = File(path)
            out.parentFile?.mkdirs()
            out.writeText(json.encodeToString(JsonArray.serializer(), JsonArray(actual)) + "\n")
            println("baseline replay recorded: ${actual.size} cases -> $path")
        }

        val golden = readJsonArray("/baseline/legacy-requests.json").map { it.jsonObject }
        val differences = Json.parseToJsonElement(read("/baseline/migration-differences.json")).jsonObject

        assertEquals("用例集合变了", golden.map { it.name() }, actual.map { it.name() })
        assertTrue("差异清单里有不存在的用例: " + (differences.keys - golden.map { it.name() }.toSet()),
            golden.map { it.name() }.containsAll(differences.keys))

        val failures = golden.indices.mapNotNull { i ->
            val name = golden[i].name()
            val listed = differences[name]?.jsonObject
            if (listed == null) {
                describeDiff(golden[i], actual[i])
            } else {
                require(listed.getValue("reasons").jsonArray.isNotEmpty()) { "$name: 差异清单必须写理由" }
                when {
                    golden[i] == actual[i] -> "$name: 已经和旧版相同，请把它从 migration-differences.json 里删掉"
                    else -> describeDiff(listed.getValue("expected").jsonObject, actual[i])?.let { "$it\n  （清单里列出的有意差异，期望值与实际不一致）" }
                }
            }
        }
        assertTrue("请求与基准不一致 ${failures.size}/${golden.size}:\n" + failures.joinToString("\n\n"), failures.isEmpty())
    }

    /**
     * 用户把 2.32.0 的备份文件恢复到这一版：整份设置经两遍迁移后，发出的请求和单独迁移各个对象时逐字相同
     * （迁移补出来的全局酒馆设置也算在内）。
     */
    @Test fun aRestoredOldBackupSendsTheSameRequestsAsAMigratedInstall() {
        val viaPieces = runAll()
        val viaBackup = runAll(viaBackup = true)
        val failures = viaPieces.indices.mapNotNull { describeDiff(viaPieces[it], viaBackup[it]) }
        assertTrue("备份恢复路径的请求与直接迁移不一致 ${failures.size}/${viaPieces.size}:\n" + failures.joinToString("\n\n"), failures.isEmpty())
    }

    private fun read(path: String): String =
        (javaClass.getResourceAsStream(path) ?: error("缺少资源 $path：先用 LEGACY_BASELINE_RECORD 录制，见类注释")).reader(Charsets.UTF_8).readText()

    private fun readJsonArray(path: String): JsonArray = Json.parseToJsonElement(read(path)).let { it as? JsonArray ?: JsonArray(emptyList()) }

    @Test fun everyCaseHasAUniqueName() {
        val names = LegacyRequestBaseline.cases().map { it.name }
        assertEquals(names.distinct(), names)
    }

    private fun JsonObject.name() = getValue("name").jsonPrimitive.content

    /** 报第几条消息、哪个 part、哪个字符开始不同，而不是 JUnit 折叠过的 diff。 */
    private fun describeDiff(expected: JsonObject, actual: JsonObject): String? {
        if (expected == actual) return null
        val name = expected.name()
        val em = expected.getValue("messages").jsonArray
        val am = actual.getValue("messages").jsonArray
        if (expected.getValue("params") != actual.getValue("params")) {
            return "$name: params 不同\n  期望: ${expected.getValue("params")}\n  实际: ${actual.getValue("params")}"
        }
        val head = "$name: 期望 ${em.size} 条消息 / 实际 ${am.size} 条"
        val n = minOf(em.size, am.size)
        val i = (0 until n).firstOrNull { em[it] != am[it] }
            ?: return "$head；前 $n 条相同，多出的在 ${if (em.size > n) "期望" else "实际"} 尾部: " +
                (if (em.size > n) em.drop(n) else am.drop(n)).joinToString { it.jsonObject.summary() }
        val e = em[i].jsonObject
        val a = am[i].jsonObject
        val eText = e.text()
        val aText = a.text()
        val k = (0 until minOf(eText.length, aText.length)).firstOrNull { eText[it] != aText[it] } ?: minOf(eText.length, aText.length)
        fun around(s: String) = s.substring(maxOf(0, k - 40), minOf(s.length, k + 60)).replace("\n", "⏎")
        return "$head；第 $i 条不同（期望 ${e.summary()} / 实际 ${a.summary()}）；文字自第 $k 字符起不同\n  期望: …${around(eText)}…\n  实际: …${around(aText)}…"
    }

    private fun JsonObject.summary(): String = "${getValue("role").jsonPrimitive.content}${if (getValue("synthetic").jsonPrimitive.content == "true") "*" else ""}:${text().take(40).replace("\n", "⏎")}"

    private fun JsonObject.text(): String = getValue("parts").jsonArray.joinToString("¶") { p ->
        val o = p.jsonObject
        o["text"]?.jsonPrimitive?.content ?: o["reasoning"]?.jsonPrimitive?.content ?: o.toString()
    }
}

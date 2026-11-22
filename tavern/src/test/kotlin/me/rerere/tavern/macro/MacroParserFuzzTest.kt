package me.rerere.tavern.macro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 随机畸形输入的词法/CST 差分（开发期用，需要环境变量，缺省跳过）。
 * 生成：node tavern/oracle/macro/gen_corpus.mjs 30000 1 > corpus.json
 *       node tavern/oracle/macro/cst_dump.mjs corpus.json > expected.json
 * 运行：MACRO_FUZZ_CORPUS=corpus.json MACRO_FUZZ_EXPECTED=expected.json ../gradlew test --tests '*MacroParserFuzzTest*'
 * 稳定的小语料见 MacroParserGoldenTest（golden/macro/cst.json）。
 */
class MacroParserFuzzTest {
    @Test
    fun fuzzCorpusMatchesUpstreamChevrotain() {
        val corpusPath = System.getenv("MACRO_FUZZ_CORPUS")
        val expectedPath = System.getenv("MACRO_FUZZ_EXPECTED")
        assumeTrue(corpusPath != null && expectedPath != null)
        val corpus = Json.parseToJsonElement(File(corpusPath!!).readText()).jsonArray.map { it.jsonPrimitive.content }
        val expected = Json.parseToJsonElement(File(expectedPath!!).readText()).jsonArray
        assertEquals(corpus.size, expected.size)
        val failures = ArrayList<String>()
        for ((i, input) in corpus.withIndex()) {
            val e = expected[i].jsonObject
            val parsed = MacroParser.parseDocument(input)
            val got = mapOf(
                "tokens" to CstDump.tokens(input),
                "lexErrors" to CstDump.lexErrors(input),
                "cst" to (parsed.cst?.let { CstDump.node(it) } ?: "null"),
            )
            for ((k, v) in got) {
                val want = e.getValue(k).jsonPrimitive.content
                if (want != v) {
                    failures += "input=${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(input))} field=$k\n  want=$want\n  got =$v"
                    break
                }
            }
            if (failures.size >= 15) break
        }
        if (failures.isNotEmpty()) throw AssertionError("${failures.size}+ mismatches:\n" + failures.joinToString("\n"))
    }
}

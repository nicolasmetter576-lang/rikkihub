package me.rerere.rikkihub.data.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkihub.data.db.migrations.Migration_44_45
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MessageNodeSchemaContractTest {
    private fun resolveSchema(version: Int): File {
        val relative = "schemas/me.rerere.rikkihub.data.db.AppDatabase/$version.json"
        return sequenceOf(
            File(relative),
            File("app/$relative"),
            File("../app/$relative"),
        ).firstOrNull { it.isFile }
            ?: error("Cannot find schema $relative from ${File(".").absolutePath}")
    }

    @Test
    fun `schema 45 replaces conversation_id index with non-unique composite index`() {
        val schema45 = Json.parseToJsonElement(resolveSchema(45).readText()).jsonObject
        val database = schema45["database"]!!.jsonObject
        assertEquals(45, database["version"]!!.jsonPrimitive.content.toInt())

        val messageNode = database["entities"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["tableName"]!!.jsonPrimitive.content == "message_node" }
        val indices = messageNode["indices"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, indices.size)

        val index = indices.single()
        assertEquals("index_message_node_conversation_id_node_index", index["name"]!!.jsonPrimitive.content)
        assertFalse(index["unique"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf("conversation_id", "node_index"),
            index["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(indices.none { it["name"]!!.jsonPrimitive.content == "index_message_node_conversation_id" })
    }

    @Test
    fun `migration 44 to 45 metadata matches schema versions`() {
        assertEquals(44, Migration_44_45.startVersion)
        assertEquals(45, Migration_44_45.endVersion)
    }
}

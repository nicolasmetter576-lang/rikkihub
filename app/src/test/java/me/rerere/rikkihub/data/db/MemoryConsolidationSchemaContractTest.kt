package me.rerere.rikkihub.data.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkihub.data.db.migrations.Migration_46_47
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MemoryConsolidationSchemaContractTest {
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
    fun `schema 47 uses composite primary key (conversation_id, memory_id) with cascade foreign key`() {
        val schema47 = Json.parseToJsonElement(resolveSchema(47).readText()).jsonObject
        val database = schema47["database"]!!.jsonObject
        assertEquals(47, database["version"]!!.jsonPrimitive.content.toInt())

        val table = database["entities"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["tableName"]!!.jsonPrimitive.content == "memory_consolidation" }

        val pk = table["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("conversation_id", "memory_id"), pk)

        val fks = table["foreignKeys"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, fks.size)
        val fk = fks.single()
        assertEquals("ConversationEntity", fk["table"]!!.jsonPrimitive.content)
        assertEquals("CASCADE", fk["onDelete"]!!.jsonPrimitive.content)
        assertEquals(listOf("conversation_id"), fk["columns"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `migration 46 to 47 metadata matches schema versions`() {
        assertEquals(46, Migration_46_47.startVersion)
        assertEquals(47, Migration_46_47.endVersion)
    }
}

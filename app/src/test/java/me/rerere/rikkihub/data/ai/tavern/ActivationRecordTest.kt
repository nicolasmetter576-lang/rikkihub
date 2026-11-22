package me.rerere.rikkihub.data.ai.tavern

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.tavern.worldinfo.ActivatedWorldInfoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ActivationRecordTest {
    private fun activation(uid: Int, title: String = "t$uid", position: Int = 0, order: Int = 100) = WorldInfoActivation(
        bookId = "book-1", bookName = "Lore",
        entry = ActivatedWorldInfoEntry(
            book = "book-1", uid = uid, hash = uid.toLong(), comment = title, position = position, order = order,
            depth = 4, role = 0, outletName = "", content = "body $uid",
        ),
    )

    private fun reply(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())
    private val known = setOf(Uuid.random())

    @Test fun nothingActivatedMeansNoRecord() {
        assertNull(ActivationRecord.encode(emptyList()))
    }

    @Test fun encodeThenDecodeKeepsEveryField() {
        val record = ActivationRecord.encode(listOf(activation(3, "Dragon", position = 4, order = 7)))!!
        val summary = ActivationRecord.decode(record)!!
        assertEquals(1, summary.total)
        assertEquals(RecordedActivation("book-1", "Lore", 3, "Dragon", 4, 7), summary.entries.single())
        assertEquals(0, summary.more)
    }

    @Test fun theRecordIsCappedAndCountsWhatWasLeftOut() {
        val many = (1..ActivationRecord.MAX_ENTRIES + 11).map { activation(it) }
        val summary = ActivationRecord.decode(ActivationRecord.encode(many))!!
        assertEquals(ActivationRecord.MAX_ENTRIES, summary.entries.size)
        assertEquals(11, summary.more)
        assertEquals(many.size, summary.total)
    }

    @Test fun decodingGarbageGivesNull() {
        assertNull(ActivationRecord.decode(null))
        assertNull(ActivationRecord.decode(JsonPrimitive("x")))
        assertNull(ActivationRecord.decode(JsonObject(mapOf("entries" to JsonPrimitive(1)))))
    }

    @Test fun attachWritesToTheFirstTextPartOfTheNewReply() {
        val record = ActivationRecord.encode(listOf(activation(1)))
        val reasoning = UIMessagePart.Reasoning("thinking")
        val message = reply(reasoning, UIMessagePart.Text("hello"), UIMessagePart.Text("more"))
        val out = ActivationRecord.attach(listOf(message), known, record)
        val attached = out.single()
        assertEquals(3, attached.parts.size)
        assertNull((attached.parts[1] as UIMessagePart.Text).metadata?.get("other"))
        assertEquals(record, (attached.parts[1] as UIMessagePart.Text).metadata?.get(ActivationRecord.KEY))
        assertNull((attached.parts[2] as UIMessagePart.Text).metadata)
        assertEquals(1, ActivationRecord.of(attached)?.total)
    }

    @Test fun attachKeepsOtherMetadataOnThePart() {
        val record = ActivationRecord.encode(listOf(activation(1)))
        val part = UIMessagePart.Text("hi", metadata = JsonObject(mapOf("thoughtSignature" to JsonPrimitive("sig"))))
        val out = ActivationRecord.attach(listOf(reply(part)), known, record).single()
        val metadata = (out.parts.single() as UIMessagePart.Text).metadata!!
        assertEquals(JsonPrimitive("sig"), metadata["thoughtSignature"])
        assertTrue(ActivationRecord.KEY in metadata)
    }

    @Test fun attachIsIdempotentAndReusesTheListWhenNothingChanges() {
        val record = ActivationRecord.encode(listOf(activation(1)))
        val once = ActivationRecord.attach(listOf(reply(UIMessagePart.Text("hello"))), known, record)
        val twice = ActivationRecord.attach(once, known, record)
        assertSame(once, twice)
    }

    @Test fun attachSkipsOldMessagesUserMessagesAndRepliesWithoutText() {
        val record = ActivationRecord.encode(listOf(activation(1)))
        val old = reply(UIMessagePart.Text("old"))
        assertNull(ActivationRecord.of(ActivationRecord.attach(listOf(old), setOf(old.id), record).single()))
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("q")))
        assertNull(ActivationRecord.of(ActivationRecord.attach(listOf(user), known, record).single()))
        val toolOnly = reply(UIMessagePart.Reasoning("thinking"))
        assertNull(ActivationRecord.of(ActivationRecord.attach(listOf(toolOnly), known, record).single()))
    }

    @Test fun attachWithoutARecordChangesNothing() {
        val messages = listOf(reply(UIMessagePart.Text("hello")))
        assertSame(messages, ActivationRecord.attach(messages, known, null))
    }

    @Test fun theRecordSurvivesMessageSerialization() {
        val record = ActivationRecord.encode(listOf(activation(5, "Keep")))
        val attached = ActivationRecord.attach(listOf(reply(UIMessagePart.Text("hello"))), known, record).single()
        val json = Json.encodeToString(UIMessage.serializer(), attached)
        val back = Json.decodeFromString(UIMessage.serializer(), json)
        assertEquals("Keep", ActivationRecord.of(back)?.entries?.single()?.title)
    }
}

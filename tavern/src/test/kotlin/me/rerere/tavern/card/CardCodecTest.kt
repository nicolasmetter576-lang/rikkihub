package me.rerere.tavern.card

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.tavern.core.PromptRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CardCodecTest {

    @Test
    fun v1CardBecomesV3WithTheTavernDefaults() {
        val parsed = CardCodec.parse(CardSupport.text("v1-lantern-keeper.json"))
        val card = parsed.card
        assertEquals("v1", parsed.sourceFormat)
        assertEquals(CardSpec.V3, card.spec)
        assertEquals("Marin Vale", card.name)
        assertTrue(card.description.startsWith("{{char}} is a soft-spoken lighthouse keeper"))
        assertEquals("patient, dry humour, quietly stubborn", card.personality)
        // `creatorcomment` is copied as written (the placeholder text survives, as it does in the tavern)
        assertEquals("Creator's notes go here.", card.creatorNotes)
        assertEquals(listOf("harbor", "slice of life"), card.tags)
        assertEquals(0.7, card.talkativeness, 0.0)
        assertFalse(card.fav)
        assertEquals("", card.world)
        assertEquals(DepthPrompt("", 4, PromptRole.SYSTEM), card.depthPrompt)
        assertEquals(listOf(card.firstMes), card.greetings)
        assertEquals("fixture author", card.creator)
        assertNull(card.characterBook)
    }

    @Test
    fun v2CardKeepsEveryFieldAndDropsTheStaleTopLevelMirror() {
        val parsed = CardCodec.parse(CardSupport.text("v2-harbor-cartographer.json"))
        val card = parsed.card
        assertEquals("v2", parsed.sourceFormat)
        // data wins over the mirrored top-level name/description
        assertEquals("Ines Okafor", card.name)
        assertTrue(card.description.startsWith("{{char}} draws the coastline"))
        assertEquals("You are {{char}}. Stay in character and keep replies under six sentences.", card.systemPrompt)
        assertEquals("Never mention that you are drawing from a book.", card.postHistoryInstructions)
        assertEquals(2, card.alternateGreetings.size)
        assertEquals(3, card.greetings.size)
        assertEquals("1.2", card.characterVersion)
        assertEquals(0.4, card.talkativeness, 0.0)
        assertTrue(card.fav)
        assertEquals("Coastline Notes", card.world)
        assertEquals(DepthPrompt("[{{char}} is checking a chart while she talks.]", 3, PromptRole.SYSTEM), card.depthPrompt)
        val script = card.regexScripts.single()
        assertEquals("Bracket asides", script.scriptName)
        assertEquals(listOf(2), script.placement)
        assertTrue(script.markdownOnly)
        assertTrue(script.runOnEdit)
        // unknown keys survive: the vendor extension and the unknown top-level key; the mirror and runtime keys do not
        assertEquals(JsonObject(mapOf("kept" to JsonPrimitive(true))), card.extensions["vendor_note"])
        assertTrue("x_custom" in card.extra)
        assertTrue("create_date" in card.extra)
        assertFalse("chat" in card.extra)
        assertFalse("description" in card.extra)
        assertFalse("name" in card.extra)
        assertEquals(3, card.characterBook!!.getValue("entries").let { (it as JsonArray).size })
    }

    @Test
    fun v3CardKeepsItsNewFields() {
        val card = CardSupport.parse("v3-clockwork-orchard.json")
        assertEquals("Toby", card.nickname)
        assertEquals(listOf("Everyone, gather round the tree."), card.groupOnlyGreetings)
        assertEquals(3, card.assets.size)
        assertEquals(JsonPrimitive(1767225600), card.data["creation_date"])
        assertEquals(0.9, card.talkativeness, 0.0)
        // a string depth and a role other than system are still read
        assertEquals(DepthPrompt("", 6, PromptRole.ASSISTANT), card.depthPrompt)
        // the group-only greeting is not an ordinary greeting
        assertEquals(listOf(card.firstMes, "The orchard is quiet today, {{user}}."), card.greetings)
    }

    @Test
    fun v3RoundTripIsLossless() {
        for (name in listOf("v1-lantern-keeper.json", "v2-harbor-cartographer.json", "v3-clockwork-orchard.json")) {
            val card = CardSupport.parse(name)
            val again = CardCodec.parse(card.toJson()).card
            assertEquals(name, card.toJson(), again.toJson())
            assertEquals(name, card, again)
        }
    }

    @Test
    fun unknownDataKeysKeepTheirOrderAndValues() {
        val src = CardSupport.obj("v2-harbor-cartographer.json")
        val card = CardCodec.parse(src).card
        val inputKeys = (src["data"] as JsonObject).keys.toList()
        assertEquals(inputKeys, card.data.keys.toList())
        assertEquals((src["data"] as JsonObject)["extensions"], card.data["extensions"])
        assertEquals((src["data"] as JsonObject)["character_book"], card.data["character_book"])
    }

    @Test
    fun v3EncodingFillsTheRequiredKeysAndV2EncodingStripsTheV3OnlyOnes() {
        val minimal = CardCodec.parse("""{"name":"Bare"}""").card
        val v3 = CardCodec.encodeV3(minimal)
        val data = v3["data"] as JsonObject
        for (key in listOf("name", "description", "personality", "scenario", "first_mes", "mes_example", "creator_notes",
            "system_prompt", "post_history_instructions", "alternate_greetings", "tags", "creator", "character_version",
            "extensions", "group_only_greetings")) {
            assertTrue("missing $key", key in data)
        }
        val v2 = CardCodec.encodeV2(CardSupport.parse("v3-clockwork-orchard.json"))
        assertEquals(JsonPrimitive(CardSpec.V2), v2["spec"])
        assertEquals(JsonPrimitive(CardSpec.V2_VERSION), v2["spec_version"])
        val v2data = v2["data"] as JsonObject
        for (key in listOf("assets", "nickname", "creator_notes_multilingual", "source", "group_only_greetings", "creation_date", "modification_date")) {
            assertFalse("v2 must not carry $key", key in v2data)
        }
        assertEquals(JsonPrimitive("Tobias Rennick"), v2data["name"])
        // the V2 form parses back to the same character fields
        assertEquals("Tobias Rennick", CardCodec.parse(v2).card.name)
    }

    @Test
    fun stringShapedTagsAndGreetingsAreNormalised() {
        val card = CardCodec.parse(
            """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"X","tags":"a, b,, c","alternate_greetings":"hello there","extensions":null}}""",
        ).card
        assertEquals(listOf("a", "b", "c"), card.tags)
        assertEquals(listOf("hello there"), card.alternateGreetings)
        assertTrue(card.data["extensions"] is JsonObject)
    }

    @Test
    fun thePlaceholderNoteIsStrippedFromCreatorNotesOnly() {
        assertEquals("", CardCodec.parse("""{"name":"A","creator_notes":"Creator's notes go here."}""").card.creatorNotes)
        assertEquals("real", CardCodec.parse("""{"name":"A","creator_notes":"Creator's notes go here.real"}""").card.creatorNotes)
        // both keys present: creatorcomment wins, unchanged
        assertEquals("Creator's notes go here.", CardCodec.parse("""{"name":"A","creatorcomment":"Creator's notes go here.","creator_notes":"x"}""").card.creatorNotes)
    }

    @Test
    fun importingDropsTheFavouriteMark() {
        val imported = CardFiles.read(CardSupport.text("v2-harbor-cartographer.json").toByteArray())
        assertFalse(imported.card.fav)
        // the plain parser keeps the file as written
        assertTrue(CardSupport.parse("v2-harbor-cartographer.json").fav)
    }

    @Test
    fun pygmalionStyleJsonIsRecognised() {
        val parsed = CardCodec.parse(
            """{"char_name":"Pip","char_persona":"A cheerful courier.","char_greeting":"Parcel for you!","example_dialogue":"<START>","world_scenario":"A rainy street.","tags":"courier"}""",
        )
        assertEquals("pygmalion", parsed.sourceFormat)
        assertEquals("Pip", parsed.card.name)
        assertEquals("A cheerful courier.", parsed.card.description)
        assertEquals("Parcel for you!", parsed.card.firstMes)
        assertEquals("A rainy street.", parsed.card.scenario)
        assertEquals(listOf("courier"), parsed.card.tags)
    }

    @Test
    fun specWithoutDataFallsBackToTheTopLevelFields() {
        val card = CardCodec.parse("""{"spec":"chara_card_v2","spec_version":"2.0","name":"Old","description":"flat"}""").card
        assertEquals("Old", card.name)
        assertEquals("flat", card.description)
    }

    @Test
    fun notACardIsRejectedWithAReadableMessage() {
        for (text in listOf("not json", "[1,2]", """{"foo":1}""", "")) {
            try {
                CardCodec.parse(text)
                fail("should reject: $text")
            } catch (e: CardFormatException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun editsReturnNewCardsAndNeverTouchTheOriginal() {
        val card = CardSupport.parse("v2-harbor-cartographer.json")
        val edited = card.withData("description", JsonPrimitive("changed")).withExtension("fav", JsonPrimitive(false))
        assertEquals("changed", edited.description)
        assertFalse(edited.fav)
        assertTrue(card.description.startsWith("{{char}} draws"))
        assertTrue(card.fav)
        // other extension keys are untouched
        assertEquals(card.extensions["vendor_note"], edited.extensions["vendor_note"])
        assertEquals(card.toJson()["data"].let { (it as JsonObject).keys.toList() }, edited.toJson()["data"].let { (it as JsonObject).keys.toList() })
    }
}

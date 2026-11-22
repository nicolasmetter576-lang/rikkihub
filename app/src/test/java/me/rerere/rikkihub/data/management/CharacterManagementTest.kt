package me.rerere.rikkihub.data.management

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.character.CardPatch
import me.rerere.rikkihub.data.creator.runtime.CreatorCommandDecoder
import me.rerere.rikkihub.data.creator.runtime.CreatorCommandRejected
import me.rerere.rikkihub.data.creator.session.CreatorObjectKind
import me.rerere.rikkihub.data.creator.session.CreatorOperation
import me.rerere.rikkihub.data.management.adapters.character.CharacterAction
import me.rerere.rikkihub.data.management.adapters.character.CharacterManagementCommand
import me.rerere.rikkihub.data.management.adapters.character.CharacterNative
import me.rerere.rikkihub.data.management.proposal.ProposalServiceError
import me.rerere.rikkihub.data.management.proposal.ProposalStatus
import me.rerere.rikkihub.data.model.CharacterLocalSettings
import me.rerere.rikkihub.data.model.CharacterRecord
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.mutation.ConversationCommandApplier
import me.rerere.rikkihub.data.mutation.ConversationCommandContext
import me.rerere.rikkihub.data.mutation.ConversationHostIds
import me.rerere.rikkihub.data.mutation.ConversationMutationCommand
import me.rerere.tavern.card.CharacterCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

/** The creator assistant creating and editing characters through proposals: plan, approve, apply, conflicts, limits. */
class CharacterManagementTest {
    private fun obj(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf(*pairs))
    private fun str(s: String) = JsonPrimitive(s)
    private fun strings(vararg s: String) = buildJsonArray { s.forEach { add(JsonPrimitive(it)) } }

    private fun decode(command: String, targetId: String? = null, vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) =
        CreatorCommandDecoder.decode(CreatorOperation("CHARACTER", targetId = targetId, command = command, params = obj("fields" to obj(*fields))))

    private fun seed(f: ManagementFixture, card: CharacterCard = CharacterCard.blank("Ines Okafor")): Uuid {
        val id = Uuid.random()
        val now = Instant.now()
        f.room.characters[id] = CharacterRecord(id, card, "", "", now, now, revision = 0)
        return id
    }

    @Test(timeout = 60_000) fun aCharacterIsCreatedFromACardWithAllItsTextFields() = managementFixture { f ->
        val command = decode("Create", null,
            "name" to str("Tobias Rennick"), "description" to str("A clockwork orchard keeper."),
            "first_mes" to str("*Tobias winds a key.* Hello, {{user}}."), "tags" to strings("orchard", "clockwork"),
            "alternate_greetings" to strings("Hm?"), "fav" to JsonPrimitive(true),
            "depth_prompt" to obj("prompt" to str("[{{char}} ticks.]"), "depth" to JsonPrimitive(2), "role" to str("assistant")))
        val proposal = f.runtime.preview(command, f.source)
        // the diff shows the new fields, and nothing exists before the user approves
        assertTrue(proposal.steps.isNotEmpty())
        assertTrue(f.room.characters.isEmpty())
        assertEquals(ProposalStatus.APPLIED, f.approve(proposal.proposalId).status)

        val record = f.room.characters.values.single()
        assertEquals("Tobias Rennick", record.name)
        assertEquals("A clockwork orchard keeper.", record.card.description)
        assertEquals(listOf("orchard", "clockwork"), record.card.tags)
        assertEquals(listOf("Hm?"), record.card.alternateGreetings)
        assertTrue(record.card.fav)
        assertEquals(2, record.card.depthPrompt.depth)
        assertEquals("assistant", record.card.depthPrompt.role.name.lowercase())
        assertEquals("*Tobias winds a key.* Hello, {{user}}.", record.card.firstMes)
    }

    @Test(timeout = 60_000) fun aPatchChangesOnlyTheNamedFieldsAndKeepsWhatThePageCannotEdit() = managementFixture { f ->
        val card = CharacterCard.blank("Ines Okafor").withData("description", str("old")).withData("scenario", str("harbor"))
            .withExtension("vendor_note", str("keep me")).withData("assets", JsonArray(listOf(obj("type" to str("icon")))))
        val id = seed(f, card)
        val proposal = f.runtime.preview(decode("Patch", id.toString(),
            "description" to str("new"), "tags" to strings("a", " b ", "a"),
            "depth_prompt" to obj("depth" to JsonPrimitive(9))), f.source)
        assertEquals(ProposalStatus.APPLIED, f.approve(proposal.proposalId).status)

        val record = f.room.characters.getValue(id)
        assertEquals(1, record.revision)
        assertEquals("new", record.card.description)
        assertEquals("harbor", record.card.scenario) // not named, not touched
        assertEquals(listOf("a", "b"), record.card.tags)
        assertEquals(9, record.card.depthPrompt.depth)
        assertEquals(str("keep me"), record.card.extensions["vendor_note"])
        assertEquals(1, record.card.assets.size)
    }

    @Test(timeout = 60_000) fun anEditPlannedOnAnOldRevisionIsAConflictAndWritesNothing() = managementFixture { f ->
        val id = seed(f)
        val proposal = f.runtime.preview(decode("Patch", id.toString(), "creator" to str("creator-ai")), f.source)
        // somebody (the user, in the app) changes the character before the approval lands
        f.room.characters[id] = f.room.characters.getValue(id).let { it.copy(card = it.card.withData("creator", str("the user")), revision = 1) }
        val result = runCatching { f.approve(proposal.proposalId) }
        assertTrue(result.isFailure || result.getOrNull()?.status != ProposalStatus.APPLIED)
        assertEquals("the user", f.room.characters.getValue(id).card.creator)
    }

    @Test(timeout = 60_000) fun badCommandsAreRefusedWhenThePlanIsMade() = managementFixture { f ->
        val id = seed(f)
        fun planFails(command: CharacterManagementCommand) =
            assertThrows(ProposalServiceError.DomainError::class.java) { runBlocking { f.runtime.preview(command, f.source) } }
        planFails(CharacterManagementCommand(CharacterAction.CREATE, null, obj("name" to str("  ")))) // a name is required
        planFails(CharacterManagementCommand(CharacterAction.CREATE, null, obj("description" to str("no name"))))
        planFails(CharacterManagementCommand(CharacterAction.CREATE, id.toString(), obj("name" to str("x")))) // ids are assigned by the app
        planFails(CharacterManagementCommand(CharacterAction.CREATE, null, obj("name" to str("x")), extraBookIds = listOf(Uuid.random().toString())))
        planFails(CharacterManagementCommand(CharacterAction.CREATE, null, obj("name" to str("x")), detachCardBook = true))
        planFails(CharacterManagementCommand(CharacterAction.PATCH, Uuid.random().toString(), obj("name" to str("x")))) // unknown character
        planFails(CharacterManagementCommand(CharacterAction.PATCH, "not-an-id", obj("name" to str("x"))))
        planFails(CharacterManagementCommand(CharacterAction.PATCH, null, obj("name" to str("x"))))
        planFails(CharacterManagementCommand(CharacterAction.PATCH, id.toString(), obj("name" to str("Ines Okafor")))) // changes nothing
        planFails(CharacterManagementCommand(CharacterAction.PATCH, id.toString(), obj("tags" to JsonPrimitive(3))))
        planFails(CharacterManagementCommand(CharacterAction.PATCH, id.toString(), obj("depth_prompt" to obj("role" to str("robot")))))
        planFails(CharacterManagementCommand(CharacterAction.PATCH, id.toString(), obj("nope" to str("x"))))
        planFails(CharacterManagementCommand(CharacterAction.PATCH, id.toString(), obj("fav" to JsonPrimitive(false)), detachCardBook = true)) // no card book
        assertEquals(1, f.room.characters.size)
    }

    @Test(timeout = 60_000) fun worldBooksAreTiedAndTheCardBookCanBeUntied() = managementFixture { f ->
        val book = Lorebook(id = Uuid.random(), name = "Coastline")
        val cardBook = Lorebook(id = Uuid.random(), name = "From the card")
        f.settings.update { it.copy(lorebooks = it.lorebooks + book + cardBook) }
        val id = Uuid.random()
        val now = Instant.now()
        f.room.characters[id] = CharacterRecord(id, CharacterCard.blank("Ines"), "", "", now, now, CharacterLocalSettings(embeddedBookId = cardBook.id))

        val tie = CharacterManagementCommand(CharacterAction.PATCH, id.toString(), JsonObject(emptyMap()), extraBookIds = listOf(book.id.toString(), book.id.toString()))
        assertEquals(ProposalStatus.APPLIED, f.approve(f.runtime.preview(tie, f.source).proposalId).status)
        assertEquals(listOf(book.id), f.room.characters.getValue(id).local.extraBookIds)
        assertEquals(cardBook.id, f.room.characters.getValue(id).local.embeddedBookId)

        val untie = CharacterManagementCommand(CharacterAction.PATCH, id.toString(), JsonObject(emptyMap()), detachCardBook = true)
        assertEquals(ProposalStatus.APPLIED, f.approve(f.runtime.preview(untie, f.source).proposalId).status)
        assertNull(f.room.characters.getValue(id).local.embeddedBookId)
        assertEquals(listOf(book.id), f.room.characters.getValue(id).local.extraBookIds)
    }

    @Test fun theCreatorDecoderAcceptsCreateAndPatchOnlyAndNamesTheOtherWaysOut() {
        val ok = decode("Create", null, "name" to str("A")) as CharacterManagementCommand
        assertEquals(CharacterAction.CREATE, ok.action)
        assertNull(ok.characterId)
        val patch = decode("Patch", "some-id", "description" to str("d")) as CharacterManagementCommand
        assertEquals(CharacterAction.PATCH, patch.action)
        assertEquals("some-id", patch.characterId)
        // fields given directly in params work too, like for the other kinds
        val direct = CreatorCommandDecoder.decode(CreatorOperation("CHARACTER", command = "Create", params = obj("name" to str("B")))) as CharacterManagementCommand
        assertEquals(str("B"), direct.fields["name"])

        for (command in listOf("Delete", "Remove", "SoftDelete", "Clone", "Reorder")) {
            val e = assertThrows(CreatorCommandRejected::class.java) {
                CreatorCommandDecoder.decode(CreatorOperation("CHARACTER", targetId = "x", command = command))
            }
            assertTrue(e.message!!, e.message!!.contains("Settings"))
        }
        assertThrows(CreatorCommandRejected::class.java) { CreatorCommandDecoder.decode(CreatorOperation("CHARACTER", command = "Create", params = obj("fields" to obj("id" to str("forged"), "name" to str("x"))))) }
        assertThrows(CreatorCommandRejected::class.java) { decode("Create", "forged-id", "name" to str("x")) }
        assertThrows(CreatorCommandRejected::class.java) { decode("Patch", null, "name" to str("x")) }
        assertThrows(CreatorCommandRejected::class.java) { decode("Patch", "x", "unknown_field" to str("x")) }
        // the creator surface is strict: tags are an array here (the web box also takes comma separated text)
        assertThrows(CreatorCommandRejected::class.java) { decode("Patch", "x", "tags" to str("a, b")) }
        assertThrows(CreatorCommandRejected::class.java) { decode("Patch", "x", "embeddedBookId" to str(Uuid.random().toString())) }
        assertThrows(CreatorCommandRejected::class.java) { CreatorCommandDecoder.decode(CreatorOperation("CHARACTER", command = "Create", params = obj("fields" to str("not an object")))) }
        assertEquals(CreatorObjectKind.CHARACTER, CreatorObjectKind.parse("characters"))
        assertEquals(CreatorObjectKind.CHARACTER, CreatorObjectKind.parse("character-card"))
        assertEquals(listOf("Create", "Patch"), CreatorCommandDecoder.commandsFor(CreatorObjectKind.CHARACTER))
    }

    @Test fun everyEditableCardFieldIsInTheCreatorsFieldTableAndTheViewHoldsOnlyKnownKeys() {
        val names = CharacterNative.FIELDS.map { it.name }
        assertTrue(names.containsAll(CardPatch.KEYS.toList()))
        assertTrue(names.containsAll(listOf("extraBookIds", "embeddedBookId")))
        assertEquals(names.size, names.toSet().size)

        val now = Instant.now()
        val view = CharacterNative.view(CharacterRecord(Uuid.random(), CharacterCard.blank("Z"), "avatar-1.png", "z.png", now, now))
        val writable = view.keys - setOf("id", "readOnly")
        assertEquals(names.toSet(), writable)
        // what the creator reads can be sent back as it is
        CharacterNative.split(JsonObject(view - "id" - "readOnly"))
        val readOnly = view.getValue("readOnly").jsonObject
        assertTrue(readOnly.getValue("hasAvatar").jsonPrimitive.content.toBoolean())
        assertEquals("z.png", readOnly.getValue("sourceName").jsonPrimitive.content)
    }

    @Test fun aConversationCanBeTiedToAnExistingCharacterOrFreedFromIt() {
        val character = Uuid.random()
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        fun context(vararg characters: Uuid) = ConversationCommandContext(setOf(conversation.assistantId), emptySet(), emptySet(), emptySet(), characters.toSet())
        fun apply(command: ConversationMutationCommand.PatchMetadata, current: Conversation = conversation, vararg known: Uuid) =
            ConversationCommandApplier.apply(current, command, ConversationHostIds(), context(*known)).conversation

        val tied = apply(ConversationMutationCommand.PatchMetadata(characterId = character.toString()), conversation, character)
        assertEquals(character, tied.characterId)
        assertEquals(character, apply(ConversationMutationCommand.PatchMetadata(title = "t"), tied, character).characterId) // untouched
        assertNull(apply(ConversationMutationCommand.PatchMetadata(clearCharacter = true), tied, character).characterId)
        assertThrows(IllegalArgumentException::class.java) { apply(ConversationMutationCommand.PatchMetadata(characterId = character.toString())) } // unknown
        assertThrows(IllegalArgumentException::class.java) {
            apply(ConversationMutationCommand.PatchMetadata(characterId = character.toString(), clearCharacter = true), conversation, character)
        }
    }
}

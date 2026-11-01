package me.rerere.rikkihub.data.management.ports

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkihub.data.creator.session.CreatorCanonicalizer
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.management.adapters.SettingsCollectionInterpreter
import me.rerere.rikkihub.data.management.common.*
import me.rerere.rikkihub.data.mutation.ExpectedVersions
import me.rerere.rikkihub.data.mutation.MutationActor
import me.rerere.rikkihub.data.mutation.MutationConflictException
import me.rerere.rikkihub.data.mutation.MutationDomain
import me.rerere.rikkihub.data.mutation.VersionToken as MutationToken

/** Real DataStore port. Never applies a cached Settings snapshot from plan/UI. */
class SettingsManagementPort(
    private val settingsStore: SettingsStore,
    private val fences: suspend () -> List<ObjectRef> = { emptyList() },
    private val isBusy: (Set<ObjectRef>) -> Boolean = { false },
    private val authorizedImageUris: Set<String> = emptySet(),
) : SettingsMutationPort, ManagementReadPort {
    private val mutationMutex = Mutex()

    override suspend fun mutate(
        ops: List<StoreOperation>, expected: List<ReadSetEntry>, operationId: String,
    ): StoreReceipt = mutateCaptured(ops, expected, operationId)

    internal suspend fun mutateCaptured(
        ops: List<StoreOperation>, expected: List<ReadSetEntry>, operationId: String,
        captured: Map<String, JsonObject> = emptyMap(),
    ): StoreReceipt = mutationMutex.withLock {
        require(ops.isNotEmpty() && ops.size <= 64 && ops.all { it.store == StoreKind.SETTINGS_DATASTORE })
        val refs = ops.flatMap { it.objectRefs }.toSet()
        check(!isBusy(refs)) { "Affected objects are busy" }
        val epoch = settingsStore.settingsEpoch()
        val objects = expected.filterIsInstance<ReadSetEntry.Object>().filterNot { it.ref.id.startsWith("collection:") }.associate { entry ->
            require(entry.expected.epoch == epoch)
            val key = objectKey(entry.ref)
            key to MutationToken(MutationDomain.SETTINGS, key, entry.expected.epoch,
                entry.expected.generation, entry.expected.revision)
        }
        val collectionEntries = expected.filterIsInstance<ReadSetEntry.Collection>() +
            expected.filterIsInstance<ReadSetEntry.Object>().filter { it.ref.id.startsWith("collection:") }.map {
                ReadSetEntry.Collection(it.ref.id.removePrefix("collection:"), it.expected)
            }
        val collections = collectionEntries.associate { entry ->
            require(entry.expected.epoch == epoch && entry.expected.generation == 0L)
            entry.collectionId.removePrefix("settings.") to entry.expected.revision
        }
        val fingerprint = fingerprint(ops)
        try {
            settingsStore.mutate(operationId = operationId, actor = MutationActor.MANAGEMENT,
                expected = ExpectedVersions(objects, collections), requestFingerprint = fingerprint) { latest ->
                ops.fold(latest) { current, op ->
                    when (op) {
                        is SettingsRestoreOp -> {
                            val snapshot = captured[op.payloadRef] ?: error("Encrypted recovery image unavailable")
                            me.rerere.rikkihub.data.management.recovery.SettingsRecoverySnapshots.restore(current, snapshot)
                        }
                        is me.rerere.rikkihub.data.management.adapters.skills.SkillStoreOp.CascadeEnabledSkillsRename ->
                            current.copy(assistants = current.assistants.map { assistant ->
                                if (op.fromName !in assistant.enabledSkills) assistant
                                else assistant.copy(enabledSkills = assistant.enabledSkills.mapTo(LinkedHashSet()) {
                                    if (it == op.fromName) op.toName else it
                                })
                            })
                        else -> SettingsCollectionInterpreter.apply(current, op, authorizedImageUris)
                    }
                }
            }
        } catch (conflict: MutationConflictException) {
            val entry = expected.firstOrNull()
            throw ConcurrencyConflict((entry as? ReadSetEntry.Object)?.ref,
                (entry as? ReadSetEntry.Collection)?.collectionId,
                when (entry) { is ReadSetEntry.Object -> entry.expected; is ReadSetEntry.Collection -> entry.expected; else -> null },
                null)
        }
        checkNotNull(receiptFor(operationId, refs))
    }

    internal suspend fun receiptFor(operationId: String, refs: Set<ObjectRef>): StoreReceipt? {
        val receipt = settingsStore.receiptFor(operationId) ?: return null
        val tokens = refs.filterNot { it.id.startsWith("collection:") }.mapNotNull { ref ->
            receipt.afterTokens[objectKey(ref)]?.let(MutationToken::decode)?.let {
                TokenRef(ref, VersionToken(it.epoch, it.generation, it.revision))
            }
        }
        val collectionsAfter = receipt.afterCollections.filterKeys { key ->
            key in setOf("quickMessages", "modeInjections", "lorebooks", "assistants", "assistantTags", "providers",
                "defaults.modelSlots", "defaults.prompts") ||
                key.startsWith("providers.") || key.startsWith("assistants[]")
        }.map { (key, revision) ->
            val collection = "settings.$key"
            TokenRef(ObjectRef(domainForFenceCollection(collection), "collection:$collection"),
                VersionToken(receipt.epoch, 0, revision))
        }
        return StoreReceipt(operationId, StoreKind.SETTINGS_DATASTORE, receipt.appliedAtMillis, tokens + collectionsAfter,
            operationFingerprint = receipt.requestFingerprint, storageEpoch = receipt.epoch)
    }

    override suspend fun atomicSettingsSnapshot(): ManagementSettingsSnapshot {
        val snapshot = settingsStore.atomicReadSnapshot()
        return ManagementSettingsSnapshot(snapshot.settings, snapshot.epoch,
            snapshot.objects.mapValues { (_, token) -> VersionToken(token.epoch, token.generation, token.revision) },
            snapshot.collections.mapKeys { "settings.${it.key}" }.mapValues { (_, revision) -> VersionToken(snapshot.epoch, 0, revision) })
    }

    override suspend fun settingsSnapshot(): Settings = settingsStore.settingsSnapshot()

    override suspend fun snapshotIds(collectionId: String): List<String> =
        SettingsCollectionInterpreter.collectionJson(settingsSnapshot(), collectionId)
            .map { it.getValue("id").toString().removeSurrounding("\"") }

    override suspend fun readObjectJson(collectionId: String, id: String): JsonObject? =
        SettingsCollectionInterpreter.collectionJson(settingsSnapshot(), collectionId)
            .firstOrNull { it["id"]?.toString()?.removeSurrounding("\"") == id }

    override suspend fun objectToken(collectionId: String, objectId: String): VersionToken? {
        val domain = domainForCollection(collectionId)
        return tokenFor(ObjectRef(domain, objectId))
    }

    suspend fun tokenFor(ref: ObjectRef): VersionToken? {
        if (ref.id.startsWith("collection:")) return collectionToken(ref.id.removePrefix("collection:"))
        return settingsStore.currentSettingsToken(objectKey(ref))?.let { VersionToken(it.epoch, it.generation, it.revision) }
    }

    override suspend fun collectionToken(collectionId: String): VersionToken {
        require(collectionId.startsWith("settings."))
        return VersionToken(settingsStore.settingsEpoch(), 0,
            settingsStore.currentCollectionRevision(collectionId.removePrefix("settings.")))
    }

    override suspend fun fencedRefs(): List<ObjectRef> = fences()

    companion object {
        fun fingerprint(ops: List<StoreOperation>): String = CreatorCanonicalizer.canonicalDigest(
            kotlinx.serialization.json.JsonArray(ops.map {
                SettingsCollectionInterpreter.strictJson.parseToJsonElement(StoreOperationCodec.encode(it))
            }))

        fun domainForFenceCollection(id: String): ManagementDomain = when {
            id.startsWith("settings.assistants") -> ManagementDomain.ASSISTANT
            else -> domainForCollection(id)
        }

        fun domainForCollection(id: String): ManagementDomain = when {
            id == "settings.quickMessages" -> ManagementDomain.QUICK_MESSAGE
            id == "settings.modeInjections" -> ManagementDomain.MODE_INJECTION
            id == "settings.lorebooks" -> ManagementDomain.WORLD_BOOK
            id == "settings.assistants" || id == "settings.assistantTags" -> ManagementDomain.ASSISTANT
            id == "settings.providers" || id.startsWith("settings.providers.") && id.endsWith(".models") ||
                id.startsWith("settings.defaults.") ->
                ManagementDomain.MODEL_AND_PROMPT_DEFAULTS
            else -> throw IllegalArgumentException("Unknown Settings collection")
        }

        fun objectKey(ref: ObjectRef): String = when (ref.domain) {
            ManagementDomain.QUICK_MESSAGE -> "quickMessage:${ref.id}"
            ManagementDomain.MODE_INJECTION -> "modeInjection:${ref.id}"
            ManagementDomain.WORLD_BOOK -> "lorebook:${ref.id}"
            ManagementDomain.ASSISTANT -> if (ref.id.startsWith("tag:")) "assistantTag:${ref.id.removePrefix("tag:")}" else "assistant:${ref.id}"
            ManagementDomain.MODEL_AND_PROMPT_DEFAULTS -> when {
                ref.id.startsWith("setting:") || ref.id.startsWith("provider:") -> ref.id
                else -> "model:${ref.id}"
            }
            else -> throw IllegalArgumentException("Object does not belong to Settings")
        }
    }
}

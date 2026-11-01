package me.rerere.rikkihub.data.management.ports

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.management.common.VersionToken

/**
 * One read port for planners. Routing is by the frozen collection namespace
 * ([StoreRouting]); each store answers with the same token function its gateway uses
 * for pre-commit verification.
 */
class CompositeManagementReadPort(
    private val settings: SettingsManagementPort,
    private val room: RoomManagementPort,
    private val skills: SkillsManagementPort,
) : ManagementReadPort {
    override suspend fun snapshotIds(collectionId: String): List<String> = when (StoreRouting.forCollection(collectionId)) {
        StoreKind.SETTINGS_DATASTORE -> settings.snapshotIds(collectionId)
        StoreKind.MAIN_ROOM -> room.snapshotIds(collectionId)
        StoreKind.USER_FILES -> skills.snapshotIds(collectionId)
        StoreKind.VAULT -> throw IllegalArgumentException("Vault is not a managed collection")
    }

    override suspend fun readObjectJson(collectionId: String, id: String): JsonObject? = when (StoreRouting.forCollection(collectionId)) {
        StoreKind.SETTINGS_DATASTORE -> settings.readObjectJson(collectionId, id)
        StoreKind.MAIN_ROOM -> room.readObjectJson(collectionId, id)
        StoreKind.USER_FILES -> skills.readObjectJson(collectionId, id)
        StoreKind.VAULT -> throw IllegalArgumentException("Vault is not a managed collection")
    }

    override suspend fun atomicSettingsSnapshot(): ManagementSettingsSnapshot = settings.atomicSettingsSnapshot()

    override suspend fun settingsSnapshot(): Settings = settings.settingsSnapshot()

    override suspend fun objectToken(collectionId: String, id: String): VersionToken? = when (StoreRouting.forCollection(collectionId)) {
        StoreKind.SETTINGS_DATASTORE -> settings.objectToken(collectionId, id)
        StoreKind.MAIN_ROOM -> room.objectToken(collectionId, id)
        StoreKind.USER_FILES -> skills.objectToken(collectionId, id)
        StoreKind.VAULT -> throw IllegalArgumentException("Vault is not a managed collection")
    }

    override suspend fun collectionToken(collectionId: String): VersionToken = when (StoreRouting.forCollection(collectionId)) {
        StoreKind.SETTINGS_DATASTORE -> settings.collectionToken(collectionId)
        StoreKind.MAIN_ROOM -> room.collectionToken(collectionId)
        StoreKind.USER_FILES -> skills.collectionToken(collectionId)
        StoreKind.VAULT -> throw IllegalArgumentException("Vault is not a managed collection")
    }
}

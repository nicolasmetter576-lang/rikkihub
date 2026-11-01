package me.rerere.rikkihub.data.management.ports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkihub.data.management.adapters.skills.SkillCollections
import me.rerere.rikkihub.data.management.adapters.skills.SkillPathRules
import me.rerere.rikkihub.data.management.adapters.skills.SkillStoreOp
import me.rerere.rikkihub.data.management.common.ManagementDomain
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.common.ReadSetEntry
import me.rerere.rikkihub.data.management.common.VersionToken
import me.rerere.rikkihub.data.management.persistence.ParticipationStore
import me.rerere.rikkihub.utils.JsonInstant
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Filesystem facts for the user/builtin skill trees; production wraps SkillManager roots. */
interface SkillsBackend {
    /** User-owned authoritative tree (management writes only ever target this root). */
    val userRoot: File

    /** Packaged read-only tree. */
    val builtinRoot: File?

    suspend fun epoch(): Long

    /** Serializes against catalog listing/builtin publication (same process-wide monitor). */
    fun <T> locked(block: () -> T): T

    /** Effective catalog name for a skill directory (frontmatter `name`), or null if invalid. */
    fun skillName(dir: File): String?

    /** Renames enabled-skill references after a frontmatter rename (Settings side, idempotent). */
    suspend fun receipt(operationId: String): String?
    suspend fun writeReceipt(operationId: String, json: String)
}

/**
 * Skills (USER_FILES) side of management: catalog/file projection for planning and
 * the gateway that applies [SkillStoreOp]s. Every path is re-validated here (the
 * adapter's validation is not trusted), symlinks are never followed, and only the
 * user root is written; builtin skills stay read-only and are overridden by copies.
 *
 * Tokens: `files.skills.catalog` → digest of (name, origin, dir) list; `skill:<name>` →
 * digest of its manifest (path + content digest); `file:<name>/<path>` → content digest.
 */
class SkillsManagementPort(
    private val backend: SkillsBackend,
    private val participation: ParticipationStore,
) : StoreGateway {
    override val store = StoreKind.USER_FILES
    private val mutex = Mutex()

    data class Entry(val name: String, val dir: File, val builtin: Boolean, val shadowsBuiltin: Boolean)

    // ---------------------------------------------------------------- catalog facts

    private fun catalog(): List<Entry> = backend.locked {
        val user = listDirs(backend.userRoot).mapNotNull { dir -> backend.skillName(dir)?.let { it to dir } }
            .distinctBy { it.first }
        val builtin = backend.builtinRoot?.let { root ->
            listDirs(root).mapNotNull { dir -> backend.skillName(dir)?.let { it to dir } }
        }.orEmpty().distinctBy { it.first }
        val builtinNames = builtin.map { it.first }.toSet()
        val userNames = user.map { it.first }.toSet()
        user.map { (name, dir) -> Entry(name, dir, false, name in builtinNames) } +
            builtin.filter { it.first !in userNames }.map { (name, dir) -> Entry(name, dir, true, false) }
    }.sortedBy { it.name }

    private fun listDirs(root: File): List<File> {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return emptyList()
        return root.listFiles().orEmpty().filter { dir ->
            !dir.name.startsWith(".") && Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)
        }
    }

    private fun manifest(dir: File): List<Triple<String, String, Boolean>> {
        val out = mutableListOf<Triple<String, String, Boolean>>()
        val base = dir.toPath()
        Files.walk(base).use { stream ->
            stream.filter { it != base }.limit(MAX_FILES.toLong() + 1).forEach { path ->
                val rel = base.relativize(path).toString().replace(File.separatorChar, '/')
                when {
                    Files.isSymbolicLink(path) -> out += Triple(rel, "symlink", false)
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> out += Triple(rel, "", true)
                    else -> out += Triple(rel, sha256(Files.readAllBytes(path)), false)
                }
            }
        }
        require(out.size <= MAX_FILES) { "Skill tree exceeds $MAX_FILES entries" }
        return out.sortedBy { it.first }
    }

    // ---------------------------------------------------------------- read projection

    suspend fun snapshotIds(collectionId: String): List<String> = withContext(Dispatchers.IO) {
        require(collectionId == SkillCollections.CATALOG) { "Unknown Skills collection" }
        catalog().map { it.name }
    }

    suspend fun readObjectJson(collectionId: String, id: String): JsonObject? = withContext(Dispatchers.IO) {
        require(collectionId == SkillCollections.CATALOG) { "Unknown Skills collection" }
        if (id.startsWith("file:")) {
            val rest = id.removePrefix("file:")
            val dirName = rest.substringBefore('/')
            val path = rest.substringAfter('/', "")
            val entry = catalog().firstOrNull { it.dir.name == dirName } ?: return@withContext null
            val file = resolveInside(entry.dir, path) ?: return@withContext null
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@withContext null
            val bytes = file.readBytes()
            require(bytes.size <= MAX_FILE_BYTES) { "Skill file exceeds the read budget" }
            return@withContext JsonObject(mapOf("content" to JsonPrimitive(bytes.toString(Charsets.UTF_8))))
        }
        val entry = catalog().firstOrNull { it.name == id } ?: return@withContext null
        val files = manifest(entry.dir)
        val epoch = backend.epoch()
        JsonObject(mapOf(
            "name" to JsonPrimitive(entry.name),
            "dirName" to JsonPrimitive(entry.dir.name),
            "origin" to JsonPrimitive(if (entry.builtin) "builtin" else "user"),
            "files" to JsonArray(files.map { (path, digest, isDir) ->
                JsonObject(mapOf("path" to JsonPrimitive(path), "digest" to JsonPrimitive(digest),
                    "sizeBytes" to JsonPrimitive(if (isDir) 0 else resolveInside(entry.dir, path)?.length() ?: 0),
                    "isDirectory" to JsonPrimitive(isDir)))
            }),
            "revision" to JsonPrimitive(digest64(files.toString())),
            "generation" to JsonPrimitive(0),
            "shadowedBuiltinRevision" to (if (entry.shadowsBuiltin) JsonPrimitive(epoch) else JsonNull),
        ))
    }

    suspend fun objectToken(collectionId: String, id: String): VersionToken? =
        currentToken(ObjectRef(ManagementDomain.USER_SKILL, "skill:$id"))

    // ---------------------------------------------------------------- tokens

    override suspend fun storageEpoch(): Long = backend.epoch()

    override suspend fun currentToken(ref: ObjectRef): VersionToken? = withContext(Dispatchers.IO) {
        require(ref.domain == ManagementDomain.USER_SKILL)
        val epoch = backend.epoch()
        when {
            ref.id.startsWith("skill:") -> catalog().firstOrNull { it.name == ref.id.removePrefix("skill:") }
                ?.let { VersionToken(epoch, 0, digest64(manifest(it.dir).toString())) }
            ref.id.startsWith("file:") -> {
                val rest = ref.id.removePrefix("file:")
                val entry = catalog().firstOrNull { it.name == rest.substringBefore('/') } ?: return@withContext null
                val file = resolveInside(entry.dir, rest.substringAfter('/', "")) ?: return@withContext null
                if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) null
                else VersionToken(epoch, 0, digest64(sha256(file.readBytes())))
            }
            else -> null
        }
    }

    override suspend fun collectionToken(collectionId: String): VersionToken = withContext(Dispatchers.IO) {
        require(collectionId == SkillCollections.CATALOG || collectionId.startsWith("files.skill")) { "Unknown Skills collection" }
        VersionToken(backend.epoch(), 0, digest64(catalog().joinToString("\n") { "${it.name}|${it.builtin}|${it.dir.name}" }))
    }

    // ---------------------------------------------------------------- gateway

    override suspend fun apply(op: StoreOperation, operationId: String, expected: List<ReadSetEntry>): StoreReceipt =
        mutex.withLock {
            require(op is SkillStoreOp && op.store == store) { "Not a Skills file operation" }
            val fingerprint = RoomManagementPort.fingerprint(op)
            backend.receipt(operationId)?.let { stored ->
                val receipt = JsonInstant.decodeFromString(StoreReceipt.serializer(), stored)
                require(receipt.operationFingerprint == fingerprint) { "Operation ID changed payload" }
                return@withLock receipt
            }
            for (entry in expected) when (entry) {
                is ReadSetEntry.Object -> if (currentToken(entry.ref) != entry.expected)
                    throw ConcurrencyConflict(entry.ref, null, entry.expected, currentToken(entry.ref))
                is ReadSetEntry.Collection -> if (collectionToken(entry.collectionId) != entry.expected)
                    throw ConcurrencyConflict(null, entry.collectionId, entry.expected, collectionToken(entry.collectionId))
            }
            withContext(Dispatchers.IO) { backend.locked { execute(op) } }
            val after = op.objectRefs.mapNotNull { ref -> currentToken(ref)?.let { TokenRef(ref, it) } }
            val receipt = StoreReceipt(operationId, store, System.currentTimeMillis(), after,
                operationFingerprint = fingerprint, storageEpoch = backend.epoch())
            backend.writeReceipt(operationId, JsonInstant.encodeToString(StoreReceipt.serializer(), receipt))
            receipt
        }

    private fun userDir(dirName: String): File {
        require(SkillPathRules.validateDirName(dirName) && !dirName.startsWith(".")) { "Invalid skill directory" }
        val root = backend.userRoot.canonicalFile
        val dir = File(root, dirName)
        require(!Files.isSymbolicLink(dir.toPath())) { "Skill directory is a link" }
        return dir
    }

    private fun resolveInside(dir: File, path: String): File? {
        if (!SkillPathRules.validatePath(path, allowDir = true)) return null
        var current = dir
        for (segment in SkillPathRules.normalize(path).split('/')) {
            current = File(current, segment)
            if (Files.isSymbolicLink(current.toPath())) return null
        }
        return current
    }

    private fun requireFile(dir: File, path: String): File =
        requireNotNull(resolveInside(dir, path)) { "Invalid skill path" }

    private fun writeAtomic(target: File, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "Skill file exceeds the size limit" }
        val parent = requireNotNull(target.parentFile)
        if (!parent.exists()) check(parent.mkdirs()) { "Cannot create skill directory" }
        val temp = File.createTempFile(".${target.name}-", ".tmp", parent)
        try {
            temp.outputStream().use { out -> out.write(bytes); out.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temp.delete() }
    }

    private fun deleteTree(dir: File) {
        if (!dir.exists()) return
        require(!Files.isSymbolicLink(dir.toPath()))
        Files.walk(dir.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    private fun execute(op: SkillStoreOp) {
        when (op) {
            is SkillStoreOp.PublishTree -> {
                require(op.files.containsKey("SKILL.md")) { "A skill tree requires SKILL.md" }
                require(op.files.size <= MAX_FILES)
                val target = userDir(op.dirName)
                val root = backend.userRoot.canonicalFile.also { if (!it.exists()) check(it.mkdirs()) }
                val staging = Files.createTempDirectory(root.toPath(), ".skill-mgmt-staging-").toFile()
                var backup: File? = null
                try {
                    op.files.forEach { (path, content) -> writeAtomic(requireFile(staging, path), content) }
                    if (target.exists()) {
                        backup = Files.createTempDirectory(root.toPath(), ".skill-mgmt-backup-").toFile().also { it.delete() }
                        Files.move(target.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    }
                    Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    backup?.let(::deleteTree)
                } catch (e: Exception) {
                    if (backup != null && !target.exists()) Files.move(backup.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    throw e
                } finally {
                    if (staging.exists()) deleteTree(staging)
                }
            }
            is SkillStoreOp.PutFile -> {
                val dir = userDir(op.dirName)
                require(dir.isDirectory) { "User skill directory missing" }
                writeAtomic(requireFile(dir, op.path), op.content)
            }
            is SkillStoreOp.DeleteFile -> {
                require(SkillPathRules.normalize(op.path) != "SKILL.md") { "Deleting SKILL.md is a whole-skill delete" }
                val file = requireFile(userDir(op.dirName), op.path)
                require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Not a regular file" }
                Files.delete(file.toPath())
            }
            is SkillStoreOp.RenamePath -> {
                val dir = userDir(op.dirName)
                val from = requireFile(dir, op.fromPath)
                val to = requireFile(dir, op.toPath)
                require(from.exists() && !to.exists()) { "Rename source missing or target exists" }
                require(SkillPathRules.normalize(op.fromPath) != "SKILL.md") { "SKILL.md cannot be renamed" }
                to.parentFile?.mkdirs()
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            is SkillStoreOp.RenameSkillTree -> {
                val from = userDir(op.fromDir)
                val to = userDir(op.toDir)
                require(from.isDirectory && (op.fromDir == op.toDir || !to.exists())) { "Rename source missing or target exists" }
                val skillMd = File(from, "SKILL.md")
                writeAtomic(skillMd, renameFrontmatter(skillMd.readText(), op.newName))
                if (op.fromDir != op.toDir) Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            is SkillStoreOp.DeleteTree -> deleteTree(userDir(op.dirName).also { require(it.isDirectory) { "User skill missing" } })
            is SkillStoreOp.CascadeEnabledSkillsRename -> throw IllegalArgumentException("Settings cascade routed to wrong store")
        }
    }

    override suspend fun reconcile(op: StoreOperation, operationId: String): OperationFact {
        backend.receipt(operationId)?.let { stored ->
            val receipt = JsonInstant.decodeFromString(StoreReceipt.serializer(), stored)
            return if (receipt.operationFingerprint == RoomManagementPort.fingerprint(op))
                OperationFact(OperationFactStatus.APPLIED, receipt) else OperationFact(OperationFactStatus.UNKNOWN)
        }
        // File operations are not transactional with the receipt; a missing receipt is not proof.
        return OperationFact(OperationFactStatus.UNKNOWN)
    }

    override suspend fun compensate(op: StoreOperation, operationId: String, afterTokens: List<TokenRef>): CompensationResult =
        CompensationResult(CompensationStatus.FAILED, reason = "Skill file steps require a reviewed recovery proposal")

    override suspend fun writeParticipation(record: ParticipationRecord) {
        require(record.store == store)
        participation.write(store, record)
    }
    override suspend fun readParticipation(operationId: String): ParticipationRecord? = participation.read(store, operationId)
    override suspend fun pendingParticipations(): List<ParticipationRecord> = participation.pending().filter { it.store == store }
    override suspend fun releaseParticipation(operationId: String) {
        val record = readParticipation(operationId) ?: return
        participation.write(store, record.copy(phase = ParticipationPhase.RELEASED, fenceHeld = false,
            updatedAtMillis = System.currentTimeMillis()))
    }

    companion object {
        const val MAX_FILES = 512
        const val MAX_FILE_BYTES = 1024 * 1024

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun digest64(text: String): Long = RoomManagementPort.digest64(text)

        /** Replace the frontmatter `name:` value; the body and other keys stay byte-identical. */
        fun renameFrontmatter(content: String, newName: String): String {
            require(newName.isNotBlank() && newName.length <= 64 && newName.none { it == '\n' || it == '\r' })
            val lines = content.split('\n').toMutableList()
            require(lines.firstOrNull()?.trimEnd() == "---") { "SKILL.md has no frontmatter" }
            val end = lines.drop(1).indexOfFirst { it.trimEnd() == "---" }.takeIf { it >= 0 }?.plus(1)
                ?: throw IllegalArgumentException("Unterminated frontmatter")
            val nameLine = (1 until end).firstOrNull { lines[it].trimStart().startsWith("name:") }
                ?: throw IllegalArgumentException("Frontmatter has no name")
            lines[nameLine] = "name: $newName"
            return lines.joinToString("\n")
        }
    }
}

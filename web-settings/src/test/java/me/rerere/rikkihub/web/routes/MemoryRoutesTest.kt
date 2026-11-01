package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.ai.MemoryConsolidationRun
import me.rerere.rikkihub.data.ai.MemoryConsolidationStatus
import me.rerere.rikkihub.data.ai.MemoryDreamConfig
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.repository.FakeMemoryDAO
import me.rerere.rikkihub.data.repository.MemoryRepository
import me.rerere.rikkihub.data.repository.memoryId
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.CodedApiException
import me.rerere.rikkihub.web.NotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRoutesTest {
    private val own = Assistant(name = "own")
    private val other = Assistant(name = "other")
    private val global = Assistant(name = "global", useGlobalMemory = true)
    private val assistants = listOf(own, other, global)

    @Test
    fun `scope follows the saved useGlobalMemory flag`() {
        assertEquals(own.id.toString(), memoryAssistantOf(own.id.toString(), assistants).memoryId)
        assertEquals(MemoryRepository.GLOBAL_MEMORY_ID, memoryAssistantOf(global.id.toString(), assistants).memoryId)
        assertEquals(MEMORY_SCOPE_GLOBAL, memoryScopeLabel(global.memoryId))
        assertEquals(MEMORY_SCOPE_ASSISTANT, memoryScopeLabel(own.memoryId))
    }

    @Test
    fun `unknown or malformed assistant ids are rejected`() {
        assertThrows(NotFoundException::class.java) { memoryAssistantOf(Assistant().id.toString(), assistants) }
        assertThrows(BadRequestException::class.java) { memoryAssistantOf("not-a-uuid", assistants) }
        assertThrows(BadRequestException::class.java) { memoryAssistantOf(null, assistants) }
    }

    @Test
    fun `paths are normalized and invalid ones rejected`() {
        assertEquals("/topics/food.md", requireMemoryPath("topics/food.md"))
        assertEquals("/profile.md", requireMemoryPath("/profile.md"))
        listOf(null, "", "/notes.txt", "/../x.md", "/a//b.md", "/.hidden.md").forEach { raw ->
            assertThrows(BadRequestException::class.java) { requireMemoryPath(raw) }
        }
    }

    @Test
    fun `a file is only reachable through its own store`() = runBlocking<Unit> {
        val repo = MemoryRepository(FakeMemoryDAO())
        repo.writeFile(own.memoryId, "/profile.md", "mine\n", MemoryRepository.NEW_FILE_VERSION)

        assertEquals("mine\n", repo.getFile(own.memoryId, "/profile.md")?.content)
        // 同一路径从别的助手或全局记忆库看不到
        assertNull(repo.getFile(other.memoryId, "/profile.md"))
        assertNull(repo.getFile(global.memoryId, "/profile.md"))
    }

    @Test
    fun `write results map to http errors`() = runBlocking<Unit> {
        val repo = MemoryRepository(FakeMemoryDAO())
        val created = repo.writeFile(own.memoryId, "/a.md", "one\n", MemoryRepository.NEW_FILE_VERSION).orThrow(isNew = true)!!

        // 路径已被占用
        val taken = assertThrows(CodedApiException::class.java) {
            runBlocking { repo.writeFile(own.memoryId, "/a.md", "two\n", MemoryRepository.NEW_FILE_VERSION).orThrow(isNew = true) }
        }
        assertEquals(HttpStatusCode.Conflict, taken.status)
        assertEquals(MEMORY_VERSION_CONFLICT, taken.errorCode)

        // 编辑期间被改过（比如后台做梦），旧版本写不进去，内容不变
        repo.writeFile(own.memoryId, "/a.md", "dreamed\n", created.version)
        val stale = assertThrows(CodedApiException::class.java) {
            runBlocking { repo.writeFile(own.memoryId, "/a.md", "mine\n", created.version).orThrow(isNew = false) }
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("dreamed\n", repo.getFile(own.memoryId, "/a.md")?.content)

        // 不存在的文件改不了，也删不了
        assertThrows(NotFoundException::class.java) {
            runBlocking { repo.writeFile(own.memoryId, "/missing.md", "x\n", "abc").orThrow(isNew = false) }
        }
        assertThrows(NotFoundException::class.java) {
            runBlocking { repo.deleteFile(own.memoryId, "/missing.md", null).orThrow(isNew = false) }
        }

        // 超过大小上限是请求本身的问题
        assertThrows(BadRequestException::class.java) {
            runBlocking {
                repo.writeFile(own.memoryId, "/big.md", "x".repeat(60 * 1024), MemoryRepository.NEW_FILE_VERSION)
                    .orThrow(isNew = true)
            }
        }

        assertNull(repo.deleteFile(own.memoryId, "/a.md", null).orThrow(isNew = false))
        assertNull(repo.getFile(own.memoryId, "/a.md"))
    }

    @Test
    fun `dream status reports whether dreaming runs on its own`() {
        val status = MemoryConsolidationStatus(
            pendingTurns = 3,
            running = false,
            lastRun = MemoryConsolidationRun(
                memoryId = own.memoryId,
                finishedAt = 42,
                fullReview = false,
                changedPaths = listOf("/profile.md"),
                error = null,
            ),
        )
        val memoryOn = own.copy(enableMemory = true)

        val auto = status.toDto(memoryOn, Settings())
        assertTrue(auto.enabled)
        assertTrue(auto.automatic)
        assertEquals(3, auto.pendingTurns)
        assertEquals(42L, auto.lastFinishedAt)
        assertEquals(listOf("/profile.md"), auto.lastChangedPaths)

        assertFalse(status.toDto(memoryOn, Settings(memoryDream = MemoryDreamConfig(autoDream = false))).automatic)
        assertFalse(status.toDto(memoryOn.copy(autoConsolidateMemory = false), Settings()).automatic)
        assertFalse(status.toDto(own, Settings()).enabled)
    }

    @Test
    fun `a web client that does not know auto dreaming keeps the saved value`() {
        val existing = own.copy(autoConsolidateMemory = false)
        val incoming = own.copy(name = "renamed")

        val kept = mergeAssistant(existing, incoming, presentKeys = setOf("name"))
        assertEquals("renamed", kept.name)
        assertFalse(kept.autoConsolidateMemory)

        val changed = mergeAssistant(existing, incoming.copy(autoConsolidateMemory = true), presentKeys = setOf("autoConsolidateMemory"))
        assertTrue(changed.autoConsolidateMemory)
    }
}

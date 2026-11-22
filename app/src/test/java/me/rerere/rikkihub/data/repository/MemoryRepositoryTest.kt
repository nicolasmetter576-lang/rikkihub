package me.rerere.rikkihub.data.repository

import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.db.entity.MemoryFileEntity
import me.rerere.rikkihub.data.model.MemoryFile
import me.rerere.rikkihub.data.model.groupByDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryTest {
    private val dao = FakeMemoryDAO()
    private val repository = MemoryRepository(dao)
    private val assistant = "assistant"

    private suspend fun create(path: String, content: String): MemoryFile {
        val result = repository.writeFile(assistant, path, content, MemoryRepository.NEW_FILE_VERSION)
        return (result as MemoryWriteResult.Success).file!!
    }

    @Test
    fun `creating a file that already exists returns the current content`() = runBlocking {
        val file = create("/topics/food.md", "- [stated] Likes noodles\n")

        val result = repository.writeFile(assistant, "/topics/food.md", "other", MemoryRepository.NEW_FILE_VERSION)

        assertEquals(MemoryWriteResult.Conflict(file), result)
        assertEquals(file.content, repository.getFile(assistant, "/topics/food.md")?.content)
    }

    @Test
    fun `writing with a stale version is rejected and a fresh version succeeds`() = runBlocking {
        val first = create("/profile.md", "- [stated] Lives in Shenzhen\n")
        val second = (repository.appendToFile(assistant, "/profile.md", "- [stated] Works as a designer", first.version)
            as MemoryWriteResult.Success).file!!

        val stale = repository.appendToFile(assistant, "/profile.md", "- [stated] Stale", first.version)
        assertEquals(MemoryWriteResult.Conflict(second), stale)

        val fresh = repository.writeFile(assistant, "/profile.md", "rewritten", second.version)
        assertEquals("rewritten", (fresh as MemoryWriteResult.Success).file?.content)
    }

    @Test
    fun `editing a missing file reports that it does not exist`() = runBlocking {
        val result = repository.appendToFile(assistant, "/topics/none.md", "line", "abc")

        assertEquals(MemoryWriteResult.Conflict(null), result)
    }

    @Test
    fun `append adds a line break when the file does not end with one`() = runBlocking {
        val file = create("/topics/food.md", "- first")

        val result = repository.appendToFile(assistant, "/topics/food.md", "- second\n", file.version)

        assertEquals("- first\n- second\n", (result as MemoryWriteResult.Success).file?.content)
    }

    @Test
    fun `replacing a whole line with nothing removes the line`() = runBlocking {
        val file = create("/topics/food.md", "- keep one\n- forget me\n- keep two\n")

        val result = repository.replaceInFile(assistant, "/topics/food.md", "- forget me", "", file.version)

        assertEquals("- keep one\n- keep two\n", (result as MemoryWriteResult.Success).file?.content)
    }

    @Test
    fun `replacing part of a line keeps the rest of the line`() = runBlocking {
        val file = create("/topics/work.md", "- [stated] On the search team\n")

        val result = repository.replaceInFile(
            assistant,
            "/topics/work.md",
            "On the search team",
            "On the infra team (previously on search)",
            file.version,
        )

        assertEquals(
            "- [stated] On the infra team (previously on search)\n",
            (result as MemoryWriteResult.Success).file?.content,
        )
    }

    @Test
    fun `replace is rejected when old_str matches nothing or several places`() = runBlocking {
        val file = create("/topics/food.md", "- tea\n- tea\n")

        val several = repository.replaceInFile(assistant, "/topics/food.md", "- tea", "- coffee", file.version)
        val none = repository.replaceInFile(assistant, "/topics/food.md", "- milk", "- coffee", file.version)

        assertTrue(several is MemoryWriteResult.Rejected)
        assertTrue(none is MemoryWriteResult.Rejected)
        assertEquals(file.content, repository.getFile(assistant, "/topics/food.md")?.content)
    }

    @Test
    fun `delete checks the version and removes the file`() = runBlocking {
        val file = create("/people/mom.md", "- [stated] Lives in Chengdu\n")

        assertEquals(MemoryWriteResult.Conflict(file), repository.deleteFile(assistant, "/people/mom.md", "stale"))
        assertEquals(MemoryWriteResult.Success(null), repository.deleteFile(assistant, "/people/mom.md", file.version))
        assertNull(repository.getFile(assistant, "/people/mom.md"))
    }

    @Test
    fun `oversized content and invalid paths are rejected`() = runBlocking {
        val tooLarge = repository.writeFile(assistant, "/topics/big.md", "a".repeat(MemoryFile.MAX_BYTES + 1))
        val notMarkdown = repository.writeFile(assistant, "/topics/food.txt", "content")
        val traversal = repository.writeFile(assistant, "/topics/../profile.md", "content")

        assertTrue(tooLarge is MemoryWriteResult.Rejected)
        assertTrue(notMarkdown is MemoryWriteResult.Rejected)
        assertTrue(traversal is MemoryWriteResult.Rejected)
        assertTrue(repository.getFiles(assistant).isEmpty())
    }

    @Test
    fun `an oversized file can shrink but not grow`() = runBlocking {
        // 迁移直接写库，不经过大小检查
        val lines = List(MemoryFile.MAX_BYTES / 1000 + 10) { "- [stated] Fact $it ${"x".repeat(1000)}\n" }
        dao.upsertFile(MemoryFileEntity(assistant, "/profile.md", lines.joinToString(""), updatedAt = 0))
        val file = repository.getFile(assistant, "/profile.md")!!

        val append = repository.appendToFile(assistant, "/profile.md", "- [stated] More", file.version)
        assertTrue(append is MemoryWriteResult.Rejected)

        val trimmed = lines.drop(1).joinToString("")
        val removed = repository.replaceInFile(assistant, "/profile.md", lines.first().trimEnd(), "", file.version)
        assertEquals(trimmed, (removed as MemoryWriteResult.Success).file?.content)
        assertTrue(trimmed.toByteArray().size > MemoryFile.MAX_BYTES)

        // 用户在编辑页删掉一部分后保存，同样走 writeFile
        val edited = lines.drop(2).joinToString("")
        val saved = repository.writeFile(assistant, "/profile.md", edited)
        assertEquals(edited, (saved as MemoryWriteResult.Success).file?.content)
    }

    @Test
    fun `paths without a leading slash are normalized`() = runBlocking {
        create("topics/food.md", "content")

        assertEquals(listOf("/topics/food.md"), repository.getFiles(assistant).map { it.path })
        assertEquals("content", repository.getFile(assistant, "topics/food.md")?.content)
    }

    @Test
    fun `memory stores are isolated and can migrate safely with conflict strategies`() = runBlocking {
        create("/profile.md", "---\nname: profile\ndescription: About Lin\naliases: [Lin]\n---\n\n- [stated] Name is Lin\n- [stated] Likes tea\n")
        create("/topics/food.md", "- [stated] Likes noodles\n")

        assertTrue(repository.getFiles("other").isEmpty())
        val firstPreview = repository.previewMigration(assistant, "other")
        assertEquals(2, firstPreview.sourceCount)
        assertEquals(listOf("/profile.md", "/topics/food.md"), firstPreview.newPaths)
        assertTrue(firstPreview.conflicts.isEmpty())

        val first = repository.migrateFiles(assistant, "other", MemoryConflictStrategy.MERGE)
        assertEquals(MemoryMigrationResult(copied = 2, merged = 0, overwritten = 0, skipped = 0), first)
        assertEquals("- [stated] Likes noodles\n", repository.getFile("other", "/topics/food.md")?.content)

        // Identical files are skipped cleanly
        val secondPreview = repository.previewMigration(assistant, "other")
        assertEquals(listOf("/profile.md", "/topics/food.md"), secondPreview.identicalPaths)
        assertTrue(secondPreview.conflicts.isEmpty())

        // Diverge target /profile.md and verify SKIP, MERGE (idempotent + frontmatter), and OVERWRITE
        repository.writeFile(
            "other",
            "/profile.md",
            "---\nname: profile\ntitle: 个人资料\naliases: [小林]\ncustom: keep\n---\n\n- [stated] Name is Lin\n- [stated] Lives in Hangzhou\n",
        )
        val conflictPreview = repository.previewMigration(assistant, "other")
        assertEquals(listOf("/profile.md"), conflictPreview.conflicts.map { it.path })
        assertEquals(false, conflictPreview.conflicts.single().mergeExceedsLimit)

        val skipped = repository.migrateFiles(assistant, "other", MemoryConflictStrategy.SKIP)
        assertEquals(MemoryMigrationResult(copied = 0, merged = 0, overwritten = 0, skipped = 2), skipped)
        assertTrue(repository.getFile("other", "/profile.md")!!.content.contains("Lives in Hangzhou"))
        assertTrue(!repository.getFile("other", "/profile.md")!!.content.contains("Likes tea"))

        val merged = repository.migrateFiles(assistant, "other", MemoryConflictStrategy.MERGE)
        assertEquals(MemoryMigrationResult(copied = 0, merged = 1, overwritten = 0, skipped = 1), merged)
        val mergedFile = repository.getFile("other", "/profile.md")!!
        assertEquals("个人资料", mergedFile.title)
        assertEquals("About Lin", mergedFile.description)
        assertEquals(listOf("小林", "Lin"), mergedFile.aliases)
        assertTrue(mergedFile.content.contains("custom: keep"))
        // Duplicate bullet "- [stated] Name is Lin" appears only once
        assertEquals(1, Regex("""-\s+\[stated\]\s+Name is Lin""").findAll(mergedFile.body).count())
        assertTrue(mergedFile.body.contains("Lives in Hangzhou"))
        assertTrue(mergedFile.body.contains("Likes tea"))
        // Second merge is idempotent (no new diff)
        assertEquals(
            MemoryMigrationResult(copied = 0, merged = 0, overwritten = 0, skipped = 2),
            repository.migrateFiles(assistant, "other", MemoryConflictStrategy.MERGE),
        )

        val overwritten = repository.migrateFiles(assistant, "other", MemoryConflictStrategy.OVERWRITE)
        assertEquals(MemoryMigrationResult(copied = 0, merged = 0, overwritten = 1, skipped = 1), overwritten)
        assertEquals(repository.getFile(assistant, "/profile.md")?.content, repository.getFile("other", "/profile.md")?.content)

        // Source store was never modified or deleted
        repository.deleteFilesOfAssistant(assistant)
        assertTrue(repository.getFiles(assistant).isEmpty())
        assertEquals(2, repository.getFiles("other").size)
    }

    @Test
    fun `merging files that would exceed 48KB is rejected without corrupting target`() = runBlocking {
        val bigSource = "---\nname: big\n---\n\n- [stated] " + "s".repeat(30 * 1024) + "\n"
        val bigTarget = "---\nname: big\n---\n\n- [stated] " + "t".repeat(30 * 1024) + "\n"
        create("/topics/big.md", bigSource)
        repository.writeFile("other", "/topics/big.md", bigTarget, MemoryRepository.NEW_FILE_VERSION)

        val preview = repository.previewMigration(assistant, "other")
        assertTrue(preview.conflicts.single().mergeExceedsLimit)

        val result = repository.migrateFiles(assistant, "other", MemoryConflictStrategy.MERGE)
        assertEquals(listOf("/topics/big.md"), result.failedOversized)
        assertEquals(0, result.merged)
        assertEquals(bigTarget, repository.getFile("other", "/topics/big.md")?.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `migrating a memory store into itself is rejected`() = runBlocking<Unit> {
        repository.migrateFiles(assistant, assistant, MemoryConflictStrategy.MERGE)
    }

    @Test
    fun `description and aliases come from the frontmatter`() = runBlocking {
        val file = create(
            "/people/mom.md",
            """
                ---
                name: mom
                description: The user's mother and her move to Chengdu
                aliases: [妈妈, mother]
                ---

                - [stated] Lives in Chengdu
            """.trimIndent(),
        )

        assertEquals("The user's mother and her move to Chengdu", file.description)
        assertEquals(listOf("妈妈", "mother"), file.aliases)
    }

    @Test
    fun `files are grouped by directory with profile and preferences first`() = runBlocking {
        listOf(
            "/people/mom.md",
            "/topics/food.md",
            "/work/notes.md",
            "/preferences.md",
            "/areas/house-hunt.md",
            "/profile.md",
            "/topics/books.md",
        ).forEach { create(it, "content") }

        val groups = repository.getFiles(assistant).groupByDirectory()

        assertEquals(
            listOf(
                "/" to listOf("profile", "preferences"),
                "/topics" to listOf("books", "food"),
                "/areas" to listOf("house-hunt"),
                "/people" to listOf("mom"),
                "/work" to listOf("notes"),
            ),
            groups.map { (directory, files) -> directory to files.map { it.name } },
        )
    }

    @Test
    fun `titles are derived from the file name`() = runBlocking {
        assertEquals("Frontend Tooling", create("/topics/frontend-tooling.md", "content").title)
        assertEquals("Profile", create("/profile.md", "content").title)
        assertEquals("妈妈", create("/people/妈妈.md", "content").title)
    }

    @Test
    fun `a title in the frontmatter takes precedence over the file name`() = runBlocking {
        val food = create("/topics/food.md", "---\nname: food\ntitle: 饮食\n---\n\n- [stated] Likes noodles\n")
        assertEquals("饮食", food.title)
        assertEquals("Books", create("/topics/books.md", "---\nname: books\ntitle: \"\"\n---\n").title)
    }

    @Test
    fun `the stated marker is hidden from the displayed body`() = runBlocking {
        val file = create(
            "/topics/work.md",
            """
                ---
                name: work
                ---

                ## Work

                - [stated] On the infra team (previously on search)
                - [Stated] Works with [[alex]]
                - A line without the marker
            """.trimIndent(),
        )

        assertEquals(
            """
                ## Work

                - On the infra team (previously on search)
                - Works with [[alex]]
                - A line without the marker
            """.trimIndent(),
            file.displayBody.trim(),
        )
    }
}

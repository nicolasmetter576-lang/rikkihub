package me.rerere.rikkihub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileNameIndexTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun tree(vararg files: String): File = temp.newFolder().also { root ->
        files.forEach { path -> File(root, path).apply { parentFile!!.mkdirs(); writeText(path) } }
    }

    private fun index(root: File, prefix: String = "", skip: (String) -> Boolean = { false }) =
        FileNameIndexBuilder(skip = skip).apply { add(root, prefix) }.build(builtAt = 0)

    private fun FileNameIndex.paths(query: String, showHidden: Boolean = false) = search(query, showHidden).hits.map { it.path }

    @Test
    fun everyWordMustOccurInTheNameIgnoringCase() {
        val index = index(tree("novel/Chapter 01.md", "novel/chapter 02.txt", "notes/ch01.md", "Chapter.md"))
        assertEquals(listOf("novel/Chapter 01.md"), index.paths("chapter 01"))
        assertEquals(listOf("notes/ch01.md", "novel/Chapter 01.md"), index.paths("01 .md").sorted())
    }

    @Test
    fun exactNamesComeFirstThenPrefixesThenShorterPaths() {
        val index = index(tree("deep/a/b/report.md", "x/old report.md", "report.md", "report.md.bak"))
        assertEquals(listOf("report.md", "deep/a/b/report.md", "report.md.bak", "x/old report.md"), index.paths("report.md"))
    }

    @Test
    fun wildcardsMatchTheWholeName() {
        val index = index(tree("a/photo1.jpg", "a/photo12.jpg", "a/photo1.jpg.txt"))
        assertEquals(listOf("a/photo1.jpg", "a/photo12.jpg"), index.paths("photo*.jpg").sorted())
        assertEquals(listOf("a/photo1.jpg"), index.paths("photo?.jpg"))
    }

    @Test
    fun hiddenEntriesAndAnythingInsideThemOnlyShowWhenAsked() {
        val index = index(tree(".cache/notes.md", "notes.md"))
        assertEquals(listOf("notes.md"), index.paths("notes"))
        assertEquals(listOf("notes.md", ".cache/notes.md"), index.paths("notes", showHidden = true))
    }

    @Test
    fun foldersAreIndexedTooAndSkippedSubtreesAreNotWalked() {
        val root = tree("Android/data/pkg/secret.txt", "Android/media/song.mp3", "Docs/a.txt")
        val index = index(root, prefix = "Phone", skip = { it == "Android/data" })
        val all = index.search("*", showHidden = true).hits.map { it.path }.toSet()
        assertFalse(all.any { it.startsWith("Phone/Android/data") })
        assertEquals(listOf("Phone/Docs"), index.paths("docs"))
        assertEquals(true, index.search("docs", false).hits.single().isDirectory)
    }

    @Test
    fun countsAllMatchesButReturnsOnlyTheLimit() {
        val index = index(tree(*Array(30) { "f/file$it.txt" }))
        val result = index.search("file", showHidden = false, limit = 10)
        assertEquals(30, result.total)
        assertEquals(10, result.hits.size)
    }

    @Test
    fun aBlankQueryFindsNothing() {
        assertEquals(0, index(tree("a.txt")).search("   ", showHidden = true).total)
    }
}

package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceFileNamesTest {
    @Test
    fun rejectsEmptyDotReservedSeparatorAndSurroundingWhitespaceNames() {
        val invalid = listOf(
            "", ".", "..", ".l2s.", ".l2s.hidden", "a/b", "a\\b",
            " leading", "trailing ", "\tname", "name\n", "\u2003name", "name\u00a0",
        )
        invalid.forEach { name ->
            val error = assertThrows(name, WorkspaceFileOperationException::class.java) {
                WorkspaceFileNames.validate(name)
            }
            assertEquals(WorkspaceFileOperationException.INVALID_FILE_NAME, error.code)
        }
    }

    @Test
    fun rejectsEveryAsciiControlIncludingDelAndC1Controls() {
        ((0..31) + (127..159)).forEach { code ->
            val error = assertThrows(WorkspaceFileOperationException::class.java) {
                WorkspaceFileNames.validate("a${code.toChar()}b")
            }
            assertEquals(WorkspaceFileOperationException.INVALID_FILE_NAME, error.code)
        }
    }

    @Test
    fun allowsLiteralUnicodeDotfilesAndInternalSpacesWithoutNormalization() {
        listOf("untitled.md", "no-extension", ".gitignore", "a b.md", "中文😀.md", "é.md", "e\u0301.md")
            .forEach(WorkspaceFileNames::validate)
    }

    @Test
    fun rejectsMalformedUtf16AndBoundaryBomForNameAndExtension() {
        val invalid = listOf("\uD800", "\uDC00", "a\uD800b", "a\uDC00b", "\uFEFFname", "name\uFEFF")
        invalid.forEach { name ->
            assertThrows(WorkspaceFileOperationException::class.java) { WorkspaceFileNames.validate(name) }
            assertThrows(WorkspaceFileOperationException::class.java) {
                WorkspaceFileNames.creationName("name", name)
            }
        }
        WorkspaceFileNames.validate("a\uFEFFb")
        WorkspaceFileNames.validate("paired😀surrogates")
    }

    @Test
    fun assemblesSeparateNameAndExtensionAndEmptyExtensionReturnsDirectoryName() {
        assertEquals("untitled.md", WorkspaceFileNames.creationName("untitled", "md"))
        assertEquals("archive.tar.gz", WorkspaceFileNames.creationName("archive", "tar.gz"))
        assertEquals("中文😀.md", WorkspaceFileNames.creationName("中文😀", "md"))
        assertEquals("folder", WorkspaceFileNames.creationName("folder", ""))
        assertEquals("e\u0301.md", WorkspaceFileNames.creationName("e\u0301", "md"))
    }

    @Test
    fun assembledNameValidatesBothFieldsAndTheCombinedByteLimit() {
        val invalid = listOf(
            "" to "md", ".." to "md", "a/b" to "md", "name " to "md",
            "name" to ".md", "name" to "md.", "name" to " md", "name" to "m/d",
            "name" to "m\\d", "name" to "m\u007fd", "name" to ".l2s.hidden",
            ".l2s" to "md", "a".repeat(253) to "md",
        )
        invalid.forEach { (name, extension) ->
            val error = assertThrows(WorkspaceFileOperationException::class.java) {
                WorkspaceFileNames.creationName(name, extension)
            }
            assertEquals(WorkspaceFileOperationException.INVALID_FILE_NAME, error.code)
        }
        assertEquals("a".repeat(252) + ".md", WorkspaceFileNames.creationName("a".repeat(252), "md"))
        assertEquals("a".repeat(255), WorkspaceFileNames.creationName("a".repeat(255), ""))
    }

    @Test
    fun limitIsUtf8BytesNotCharacters() {
        WorkspaceFileNames.validate("a".repeat(255))
        WorkspaceFileNames.validate("中".repeat(85))
        WorkspaceFileNames.validate("😀".repeat(63) + "abc")
        listOf("a".repeat(256), "中".repeat(86), "😀".repeat(64)).forEach { name ->
            val error = assertThrows(WorkspaceFileOperationException::class.java) {
                WorkspaceFileNames.validate(name)
            }
            assertEquals(WorkspaceFileOperationException.INVALID_FILE_NAME, error.code)
        }
    }
}

package me.rerere.rikkihub.ui.components.ui

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

class FileNameDialogTest {
    @Test
    fun `base name selection stops before the extension and keeps whole names otherwise`() {
        val cases = mapOf(
            "notes.md" to TextRange(0, 5),
            "archive.tar.gz" to TextRange(0, 7),
            "logs.TAR.XZ" to TextRange(0, 4),
            "v1.2.zip" to TextRange(0, 4),
            "第一章.txt" to TextRange(0, 3),
            ".bashrc" to TextRange(0, 7),
            "README" to TextRange(0, 6),
            "trailing." to TextRange(0, 8),
        )
        cases.forEach { (name, expected) -> assertEquals(name, expected, fileBaseNameSelection(name)) }
    }
}

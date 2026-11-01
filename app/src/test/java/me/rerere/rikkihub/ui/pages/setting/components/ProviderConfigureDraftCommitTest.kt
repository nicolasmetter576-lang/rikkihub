package me.rerere.rikkihub.ui.pages.setting.components

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ProviderConfigureDraftCommitTest {
    private fun source(path: String) = sequenceOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun `provider fields commit every keystroke so save buttons never read a stale draft`() {
        val configure = source("app/src/main/java/me/rerere/rikkihub/ui/pages/setting/components/ProviderConfigure.kt")
        val fields = Regex("""CommitOnDoneTextField\((.*?)\n\s*\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(configure).map { it.groupValues[1] }.toList()
        assertEquals(14, fields.size)
        fields.forEach { assertTrue(it, it.contains("commitOnChange = true")) }
    }

    @Test fun `commitOnChange commits from onValueChange and dispose compares against latest value`() {
        val field = source("app/src/main/java/me/rerere/rikkihub/ui/components/ui/CommitOnDoneTextField.kt")
        assertTrue(field.contains("commitOnChange: Boolean = false"))
        assertTrue(field.contains("if (commitOnChange) commit()"))
        assertTrue(field.contains("val currentValue by rememberUpdatedState(value)"))
        assertTrue(field.contains("if (newValue != currentValue)"))
    }
}

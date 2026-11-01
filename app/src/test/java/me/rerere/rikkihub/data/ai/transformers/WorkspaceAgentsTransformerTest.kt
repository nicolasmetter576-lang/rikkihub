package me.rerere.rikkihub.data.ai.transformers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.FakeContext
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.workspace.WorkspaceRequestCapabilities
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class WorkspaceAgentsTransformerTest {
    private val id = Uuid.random()
    private val workspace = WorkspaceEntity(id.toString(), "test", "test-root", WorkspaceShellStatus.READY.name, 0, 0)
    private fun caps(eligible: Boolean = true, cwd: String? = null) = WorkspaceRequestCapabilities(
        workspaceId = id.toString(), workspaceName = "test", harnessMinimal = false,
        availableToolNames = emptyMap(), exposedTargets = emptySet(), reminderPrompt = "FROZEN override reminder",
        instructionsCwd = cwd, instructionsEligible = eligible,
    )
    private fun ctx(capabilities: WorkspaceRequestCapabilities? = caps(), bound: Uuid? = id) = TransformerContext(
        context = FakeContext(File(".")), model = Model(), assistant = Assistant(workspaceId = bound),
        settings = Settings(), workspaceCwd = "/workspace/STALE", workspaceCapabilities = capabilities,
    )
    private fun text(message: UIMessage) = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    @Test fun frozenPrefixAndExplicitNullCwdWithAllToolsOff() = runBlocking {
        val reads = mutableListOf<String>()
        val transformer = WorkspaceReminderTransformer({ error("must not reload reminder") }) { _, path, _ -> reads += path; "rules" }
        val result = transformer.transform(ctx(), listOf(UIMessage.user("question")))
        assertEquals(listOf("/root/.agents/AGENTS.md", "/workspace/AGENTS.md"), reads)
        assertTrue(text(result.first()).startsWith("FROZEN override reminder\n\n<workspace_instructions>"))
        assertTrue(result.first().isSynthetic)
        assertEquals("question", text(result.last()))
    }

    @Test fun capturedDeviceCwdWinsOverStaleContext() = runBlocking {
        val reads = mutableListOf<String>()
        val transformer = WorkspaceReminderTransformer({ null }) { _, path, _ -> reads += path; "rules" }
        transformer.transform(ctx(caps(cwd = "/device/docs/project")), emptyList())
        assertEquals("/device/docs/project/AGENTS.md", reads.last())
        assertTrue(reads.none { "STALE" in it })
    }

    @Test fun appendPreservesFirstSystemPartsAndLaterSystem() = runBlocking {
        val first = UIMessage.system("original").copy(parts = listOf(UIMessagePart.Text("original"), UIMessagePart.Text("second")))
        val later = UIMessage.system("later")
        val transformer = WorkspaceReminderTransformer({ null }) { _, _, _ -> "rules" }
        val result = transformer.transform(ctx(), listOf(first, later))
        assertTrue((result[0].parts[0] as UIMessagePart.Text).text.startsWith("original\n\nFROZEN"))
        assertEquals(first.parts[1], result[0].parts[1])
        assertEquals(later, result[1])
        assertTrue(result[0].isSynthetic)
    }

    @Test fun emptySystemPartsGainSyntheticText() = runBlocking {
        val transformer = WorkspaceReminderTransformer({ null }) { _, _, _ -> "" }
        val result = transformer.transform(ctx(), listOf(UIMessage.system("").copy(parts = emptyList())))
        assertEquals("\n\nFROZEN override reminder", text(result.single()))
        assertTrue(result.single().isSynthetic)
    }

    @Test fun unavailableSnapshotsKeepReminderButNeverRead() = runBlocking {
        val transformer = WorkspaceReminderTransformer({ error("no DB fallback") }) { _, _, _ -> error("no read") }
        assertEquals("FROZEN override reminder", text(transformer.transform(ctx(caps(false)), emptyList()).single()))
    }

    @Test fun noBindingAndMismatchedSnapshotReturnOriginal() = runBlocking {
        val transformer = WorkspaceReminderTransformer({ error("no DB") }) { _, _, _ -> error("no read") }
        val input = listOf(UIMessage.user("hello"))
        assertSame(input, transformer.transform(ctx(bound = null), input))
        assertSame(input, transformer.transform(ctx(caps().copy(workspaceId = "other")), input))
    }

    @Test fun legacyReadyLoadsAndNotReadyOrMissingDoesNot() = runBlocking {
        var reads = 0
        val ready = WorkspaceReminderTransformer({ workspace }) { _, _, _ -> reads++; "legacy rules" }
        assertTrue(text(ready.transform(ctx(null), emptyList()).single()).contains("legacy rules"))
        assertEquals(3, reads) // legacy uses its own context cwd
        val input = listOf(UIMessage.user("hello"))
        val disabled = WorkspaceReminderTransformer({ workspace.copy(shellStatus = WorkspaceShellStatus.DISABLED.name) }) { _, _, _ -> error("no read") }
        assertSame(input, disabled.transform(ctx(null), input))
        val missing = WorkspaceReminderTransformer({ null }) { _, _, _ -> error("no read") }
        assertSame(input, missing.transform(ctx(null), input))
    }

    @Test fun legacyCapsWithoutFrozenReminderRetainContextCwdButEligibleCapsUseCapturedNull() = runBlocking {
        val transformer = WorkspaceReminderTransformer({ error("no DB fallback") }) { _, _, _ -> "" }
        val legacy = caps(false).copy(reminderPrompt = null)
        val legacyPrompt = text(transformer.transform(ctx(legacy), emptyList()).single())
        assertTrue(legacyPrompt.contains("/workspace/STALE"))
        val captured = caps(true).copy(reminderPrompt = null, instructionsCwd = null)
        val capturedPrompt = text(transformer.transform(ctx(captured), emptyList()).single())
        assertFalse(capturedPrompt.contains("/workspace/STALE"))
    }

    @Test fun cancellationIsNotTurnedIntoPartialPrompt() = runBlocking {
        val transformer = WorkspaceReminderTransformer({ null }) { _, path, _ ->
            if (path.startsWith("/workspace")) throw CancellationException("policy cancelled")
            "first rules"
        }
        assertTrue(runCatching { transformer.transform(ctx(), emptyList()) }.exceptionOrNull() is CancellationException)
    }

    @Test fun repeatedAttemptsReReadWithoutAccumulation() = runBlocking {
        var version = "one"
        val transformer = WorkspaceReminderTransformer({ null }) { _, _, _ -> version }
        val input = listOf(UIMessage.user("question"))
        val first = transformer.transform(ctx(), input)
        version = "two"
        val second = transformer.transform(ctx(), input)
        assertTrue(text(first.first()).contains("one"))
        assertFalse(text(second.first()).contains("one"))
        assertTrue(text(second.first()).contains("two"))
        assertEquals(1, Regex("<workspace_instructions>").findAll(text(second.first())).count())
        assertEquals(1, input.size)
    }
}

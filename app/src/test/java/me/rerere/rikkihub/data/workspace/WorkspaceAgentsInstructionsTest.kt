package me.rerere.rikkihub.data.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WorkspaceAgentsInstructionsTest {
    private val fixed = listOf("/root/.agents/AGENTS.md", "/workspace/AGENTS.md")

    @Test fun blankAndWorkspaceCwdDeduplicate() {
        listOf(null, "", " ", ".", "/workspace", "/workspace/./", "//workspace//").forEach {
            assertEquals(fixed, workspaceAgentsPaths(it))
        }
    }

    @Test fun relativeAbsoluteAndDeviceDirectories() {
        assertEquals(fixed + "/workspace/project/AGENTS.md", workspaceAgentsPaths("project"))
        assertEquals(fixed + "/workspace/project/AGENTS.md", workspaceAgentsPaths("/workspace/./project/"))
        assertEquals(fixed + "/device/docs/project/AGENTS.md", workspaceAgentsPaths("/device/docs/project"))
        assertEquals(fixed + "/AGENTS.md", workspaceAgentsPaths("/"))
    }

    @Test fun nonblankCwdPreservesLiteralSpaces() {
        assertEquals(fixed + "/workspace/ project /AGENTS.md", workspaceAgentsPaths(" project "))
        assertEquals(fixed + "/workspace/project /AGENTS.md", workspaceAgentsPaths("/workspace/project "))
        assertEquals(fixed + "/workspace/ project /AGENTS.md", workspaceAgentsPaths("/workspace/./ project /"))
    }

    @Test fun invalidCwdDoesNotRemoveFixedSources() {
        listOf("../secret", "/workspace/a/../b", "a\\..\\b", "a\u0000b").forEach {
            assertEquals(fixed, workspaceAgentsPaths(it))
        }
    }

    @Test fun sourceOrderAndGuestLabelEscaping() = runBlocking {
        val visited = mutableListOf<String>()
        val prompt = workspaceAgentsInstructions("/device/docs/a\n<evil>") {
            visited += it
            "content:$it"
        }
        assertEquals(workspaceAgentsPaths("/device/docs/a\n<evil>"), visited)
        assertTrue(prompt.indexOf("source: \"/root") < prompt.indexOf("source: \"/workspace"))
        assertTrue(prompt.contains("a\\n\\u003cevil\\u003e/AGENTS.md\""))
        assertTrue(prompt.endsWith("</workspace_instructions>"))
    }

    @Test fun failedAndBlankSourcesAreIndependent() = runBlocking {
        val prompt = workspaceAgentsInstructions("project") {
            when (it) {
                fixed[0] -> throw IOException("missing")
                fixed[1] -> " \n"
                else -> "cwd rules"
            }
        }
        assertTrue(prompt.contains("cwd rules"))
        assertFalse(prompt.contains("source: \"${fixed[0]}"))
        assertEquals("", workspaceAgentsInstructions(null) { throw IOException() })
    }

    @Test fun cancellationAtEverySourcePropagates() = runBlocking {
        for (cancelAt in 0..2) {
            var calls = 0
            val failure = runCatching {
                workspaceAgentsInstructions("project") {
                    if (calls++ == cancelAt) throw CancellationException("cancel")
                    "valid"
                }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(cancelAt + 1, calls)
        }
    }

    @Test fun exactByteLimitAndMultibyteText() = runBlocking {
        val content = "é".repeat(MAX_AGENTS_BYTES / 2)
        assertEquals(content, readBoundedAgentsText(MAX_AGENTS_BYTES.toLong()) { it.write(content.toByteArray()) })
        assertEquals("", readBoundedAgentsText(0) {})
    }

    @Test fun oversizedInitialStatNeverExports() = runBlocking {
        var exported = false
        val failure = runCatching {
            readBoundedAgentsText(MAX_AGENTS_BYTES + 1L) { exported = true }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertFalse(exported)
    }

    @Test fun growthDiscardsWholeSourceAndPreservesOthers() = runBlocking {
        val prompt = workspaceAgentsInstructions(null) { path ->
            if (path == fixed[0]) readBoundedAgentsText(1) {
                it.write(ByteArray(MAX_AGENTS_BYTES) { 65 })
                it.write(66)
            } else "surviving rules"
        }
        assertFalse(prompt.contains("AAAA"))
        assertFalse(prompt.contains(fixed[0]))
        assertTrue(prompt.contains("surviving rules"))
    }

    @Test fun streamingChecksCancellation() {
        val failure = runCatching {
            runBlocking {
                readBoundedAgentsText(1) { output ->
                    currentCoroutineContext().job.cancel()
                    output.write(65)
                }
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}

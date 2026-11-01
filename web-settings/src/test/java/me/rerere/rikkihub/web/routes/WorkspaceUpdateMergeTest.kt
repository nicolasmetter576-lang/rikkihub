package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceUpdateMergeTest {
    @Test
    fun missingFieldsAreNotPresent() {
        val patch = parseWorkspaceUpdate(buildJsonObject { put("name", "notes") })
        assertTrue(patch.hasName)
        assertEquals("notes", patch.name)
        assertFalse(patch.hasSubagentModelId)
        assertFalse(patch.hasSubagentAllowContext)
    }

    @Test
    fun nullSubagentModelClearsConfiguredModel() {
        val patch = parseWorkspaceUpdate(
            buildJsonObject {
                put("subagentModelId", JsonNull)
            }
        )
        assertTrue(patch.hasSubagentModelId)
        assertNull(patch.subagentModelId)
        assertFalse(patch.hasName)
    }

    @Test
    fun allowContextCanBePatchedAlone() {
        val patch = parseWorkspaceUpdate(
            buildJsonObject {
                put("subagentAllowContext", true)
            }
        )
        assertTrue(patch.hasSubagentAllowContext)
        assertEquals(true, patch.subagentAllowContext)
        assertFalse(patch.hasName)
        assertFalse(patch.hasSubagentModelId)
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun emptyBodyIsRejected() {
        parseWorkspaceUpdate(buildJsonObject {})
    }

    @Test
    fun shellCompatibilityModeCanBePatchedAlone() {
        val patch = parseWorkspaceUpdate(
            buildJsonObject {
                put("shellCompatibilityMode", true)
            }
        )
        assertTrue(patch.hasShellCompatibilityMode)
        assertEquals(true, patch.shellCompatibilityMode)
        assertFalse(patch.hasName)
        assertTrue(patch.exposedMounts.isEmpty())
        assertTrue(patch.toolApprovals.isEmpty())
    }

    @Test
    fun firstMessageFileSnapshotCanBePatchedAlone() {
        val patch = parseWorkspaceUpdate(buildJsonObject { put("firstMessageFileSnapshot", true) })
        assertEquals(true, patch.firstMessageFileSnapshot)
        assertFalse(patch.hasShellCompatibilityMode)
        assertTrue(patch.exposedMounts.isEmpty())
        // 缺键 = 不变
        assertEquals(null, parseWorkspaceUpdate(buildJsonObject { put("name", "x") }).firstMessageFileSnapshot)
    }

    @Test
    fun firstMessageFileSnapshotDepthIsAnIntegerInRange() {
        assertEquals(3, parseWorkspaceUpdate(buildJsonObject { put("firstMessageFileSnapshotDepth", 3) }).firstMessageFileSnapshotDepth)
        assertEquals(null, parseWorkspaceUpdate(buildJsonObject { put("name", "x") }).firstMessageFileSnapshotDepth)
        listOf<kotlinx.serialization.json.JsonElement>(
            kotlinx.serialization.json.JsonPrimitive(0),
            kotlinx.serialization.json.JsonPrimitive(11),
            kotlinx.serialization.json.JsonPrimitive("2"),
            kotlinx.serialization.json.JsonPrimitive(1.5),
            kotlinx.serialization.json.JsonNull,
        ).forEach { raw ->
            try {
                parseWorkspaceUpdate(buildJsonObject { put("firstMessageFileSnapshotDepth", raw) })
                org.junit.Assert.fail("accepted $raw")
            } catch (_: me.rerere.rikkihub.web.BadRequestException) {
            }
        }
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun stringFirstMessageFileSnapshotIsRejected() {
        parseWorkspaceUpdate(buildJsonObject { put("firstMessageFileSnapshot", "yes") })
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun nullShellCompatibilityModeIsRejected() {
        parseWorkspaceUpdate(
            buildJsonObject {
                put("shellCompatibilityMode", JsonNull)
            }
        )
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun stringShellCompatibilityModeIsRejected() {
        parseWorkspaceUpdate(
            buildJsonObject {
                put("shellCompatibilityMode", "yes")
            }
        )
    }

    @Test
    fun exposedMountsMapToTargets() {
        val patch = parseWorkspaceUpdate(
            buildJsonObject {
                put("exposeUpload", false)
                put("exposeToolOutputs", true)
            }
        )
        assertEquals(
            mapOf("/upload" to false, "/tool_outputs" to true),
            patch.exposedMounts,
        )
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun nullExposeUploadIsRejected() {
        parseWorkspaceUpdate(
            buildJsonObject {
                put("exposeUpload", JsonNull)
            }
        )
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun nonBooleanExposeSkillsIsRejected() {
        parseWorkspaceUpdate(
            buildJsonObject {
                put("exposeSkills", "1")
            }
        )
    }

    @Test
    fun toolApprovalsKeepsOnlyProvidedKeys() {
        val patch = parseWorkspaceUpdate(
            buildJsonObject {
                putJsonObject("toolApprovals") {
                    put("workspace_shell", false)
                    put("bash", true)
                }
            }
        )
        // 增量 merge: patch 里只带传进来的键, 其余工具在写库时保留原 override/默认值
        assertEquals(mapOf("workspace_shell" to false, "bash" to true), patch.toolApprovals)
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun unknownToolApprovalNameIsRejected() {
        parseWorkspaceUpdate(
            buildJsonObject {
                putJsonObject("toolApprovals") {
                    put("not_a_tool", true)
                }
            }
        )
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun nonBooleanToolApprovalValueIsRejected() {
        parseWorkspaceUpdate(
            buildJsonObject {
                putJsonObject("toolApprovals") {
                    put("bash", JsonNull)
                }
            }
        )
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun nonObjectToolApprovalsIsRejected() {
        parseWorkspaceUpdate(
            buildJsonObject {
                put("toolApprovals", true)
            }
        )
    }

    @Test
    fun toolEnablementKeepsOnlyProvidedKeys() {
        val patch = parseWorkspaceUpdate(
            buildJsonObject {
                putJsonObject("toolEnablement") {
                    put("workspace_shell", false)
                    put("subagent", true)
                }
            }
        )
        // 增量 merge: 只携带传入的键, 其余稳定 ID 在写库时保留原值/默认启用
        assertTrue(patch.hasToolEnablement)
        assertEquals(mapOf("workspace_shell" to false, "subagent" to true), patch.toolEnablement)
        assertFalse(patch.resetToolEnablement)
        assertFalse(patch.hasName)
    }

    @Test
    fun toolEnablementAcceptsAllSevenStableIds() {
        val patch = parseWorkspaceUpdate(
            buildJsonObject {
                putJsonObject("toolEnablement") {
                    put("workspace_read_file", false)
                    put("workspace_write_file", false)
                    put("workspace_edit_file", false)
                    put("workspace_shell", false)
                    put("bash", false)
                    put("str_replace_editor", false)
                    put("subagent", true)
                }
            }
        )
        assertEquals(7, patch.toolEnablement.size)
    }

    @Test
    fun toolEnablementEmptyObjectIsValidMergeNoop() {
        val patch = parseWorkspaceUpdate(
            buildJsonObject {
                putJsonObject("toolEnablement") {}
            }
        )
        assertTrue(patch.hasToolEnablement)
        assertTrue(patch.toolEnablement.isEmpty())
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun unknownToolEnablementIdIsRejected() {
        parseWorkspaceUpdate(
            buildJsonObject {
                putJsonObject("toolEnablement") {
                    put("workspace_delete_file", false)
                }
            }
        )
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun stringToolEnablementValueIsRejected() {
        parseWorkspaceUpdate(
            buildJsonObject {
                putJsonObject("toolEnablement") {
                    put("bash", "false")
                }
            }
        )
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun numericToolEnablementValueIsRejected() {
        parseWorkspaceUpdate(
            buildJsonObject {
                putJsonObject("toolEnablement") {
                    put("bash", 0)
                }
            }
        )
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun nullToolEnablementValueIsRejected() {
        parseWorkspaceUpdate(
            buildJsonObject {
                putJsonObject("toolEnablement") {
                    put("bash", JsonNull)
                }
            }
        )
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun nonObjectToolEnablementIsRejected() {
        parseWorkspaceUpdate(
            buildJsonObject {
                put("toolEnablement", true)
            }
        )
    }

    @Test
    fun resetToolEnablementParsesAlone() {
        val patch = parseWorkspaceUpdate(
            buildJsonObject {
                put("resetToolEnablement", true)
            }
        )
        assertTrue(patch.resetToolEnablement)
        assertFalse(patch.hasToolEnablement)
        assertTrue(patch.toolEnablement.isEmpty())
    }

    @Test
    fun resetToolEnablementFalseIsNoop() {
        val patch = parseWorkspaceUpdate(
            buildJsonObject {
                put("resetToolEnablement", false)
                put("name", "ws")
            }
        )
        assertFalse(patch.resetToolEnablement)
        assertTrue(patch.hasName)
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun resetToolEnablementCombinedWithMapIsRejected() {
        parseWorkspaceUpdate(
            buildJsonObject {
                put("resetToolEnablement", true)
                putJsonObject("toolEnablement") {
                    put("bash", false)
                }
            }
        )
    }

    @Test(expected = me.rerere.rikkihub.web.BadRequestException::class)
    fun nonBooleanResetToolEnablementIsRejected() {
        parseWorkspaceUpdate(
            buildJsonObject {
                put("resetToolEnablement", "yes")
            }
        )
    }

    @Test
    fun mixedPatchParsesAllSections() {
        val patch = parseWorkspaceUpdate(
            buildJsonObject {
                put("name", "sandbox")
                put("shellCompatibilityMode", true)
                put("exposeSkills", false)
                putJsonObject("toolApprovals") {
                    put("subagent", false)
                }
            }
        )
        assertTrue(patch.hasName)
        assertEquals("sandbox", patch.name)
        assertTrue(patch.hasShellCompatibilityMode)
        assertEquals(true, patch.shellCompatibilityMode)
        assertEquals(mapOf("/skills" to false), patch.exposedMounts)
        assertEquals(mapOf("subagent" to false), patch.toolApprovals)
    }
}

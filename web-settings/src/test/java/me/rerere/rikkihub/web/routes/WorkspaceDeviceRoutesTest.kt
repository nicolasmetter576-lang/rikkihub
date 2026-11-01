package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceAvailability
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceConfigException
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDeviceRoutesTest {
    // -------------------------------------------------- availability DTO

    @Test
    fun availabilityReadyCanBrowse() {
        val dto = deviceAvailabilityDto(WorkspaceDeviceAvailability.READY)
        assertEquals("READY", dto.status)
        assertTrue(dto.canBrowse)
        assertFalse(dto.needsPermission)
    }

    @Test
    fun availabilityPermissionRequiredFlagsWebNotice() {
        val dto = deviceAvailabilityDto(WorkspaceDeviceAvailability.PERMISSION_REQUIRED)
        assertEquals("PERMISSION_REQUIRED", dto.status)
        assertFalse(dto.canBrowse)
        // 授权动作只能由 App 发起, Web 仅提示
        assertTrue(dto.needsPermission)
    }

    @Test
    fun availabilityUnsupportedApiCannotBrowse() {
        val dto = deviceAvailabilityDto(WorkspaceDeviceAvailability.UNSUPPORTED_API)
        assertEquals("UNSUPPORTED_API", dto.status)
        assertFalse(dto.canBrowse)
        assertFalse(dto.needsPermission)
    }

    @Test
    fun availabilityPrimaryVolumeDownCannotBrowse() {
        val dto = deviceAvailabilityDto(WorkspaceDeviceAvailability.PRIMARY_VOLUME_UNAVAILABLE)
        assertFalse(dto.canBrowse)
        assertFalse(dto.needsPermission)
    }

    // -------------------------------------------------- PATCH body 解析

    @Test
    fun patchParsesSingleFieldPresence() {
        val patch = parseDeviceMountUpdate(buildJsonObject { put("enabled", false) })
        assertFalse(patch.hasAlias)
        assertFalse(patch.hasSourceRelativePath)
        assertTrue(patch.hasEnabled)
        assertEquals(false, patch.enabled)
    }

    @Test
    fun patchParsesAliasAndIgnoresLegacySharedFlag() {
        val patch = parseDeviceMountUpdate(
            buildJsonObject {
                put("alias", "photos")
                put("allowSharedSource", true)
            }
        )
        assertTrue(patch.hasAlias)
        assertEquals("photos", patch.alias)
        assertFalse(patch.hasEnabled)
    }

    @Test
    fun patchAcceptsEmptySourceAsWholeSharedStorage() {
        val patch = parseDeviceMountUpdate(buildJsonObject { put("sourceRelativePath", "") })
        assertTrue(patch.hasSourceRelativePath)
        assertEquals("", patch.sourceRelativePath)
    }

    @Test(expected = BadRequestException::class)
    fun patchEmptyBodyRejected() {
        parseDeviceMountUpdate(buildJsonObject {})
    }

    @Test(expected = BadRequestException::class)
    fun patchInvalidAliasRejected() {
        parseDeviceMountUpdate(buildJsonObject { put("alias", "Bad Alias!") })
    }

    @Test
    fun patchBlankSourceMeansWholeSharedStorage() {
        assertEquals("", parseDeviceMountUpdate(buildJsonObject { put("sourceRelativePath", "  ") }).sourceRelativePath)
    }

    @Test(expected = BadRequestException::class)
    fun patchNonBooleanEnabledRejected() {
        parseDeviceMountUpdate(buildJsonObject { put("enabled", "yes") })
    }

    // -------------------------------------------------- 错误映射

    @Test
    fun configExceptionMapsTo400() {
        assertEquals(
            HttpStatusCode.BadRequest,
            deviceErrorStatus(WorkspaceDeviceConfigException("bad mount")),
        )
    }

    @Test
    fun configExceptionNotFoundMapsTo404() {
        assertEquals(
            HttpStatusCode.NotFound,
            deviceErrorStatus(WorkspaceDeviceConfigException("Device mount not found")),
        )
    }

    @Test
    fun configExceptionPermissionMapsTo403() {
        assertEquals(
            HttpStatusCode.Forbidden,
            deviceErrorStatus(
                WorkspaceDeviceConfigException("All-files access permission is not granted")
            ),
        )
    }

    @Test
    fun busyStateMapsTo409() {
        assertEquals(
            HttpStatusCode.Conflict,
            deviceErrorStatus(IllegalStateException("busy")),
        )
    }

    @Test
    fun invalidArgumentMapsTo400() {
        assertEquals(
            HttpStatusCode.BadRequest,
            deviceErrorStatus(IllegalArgumentException("invalid path")),
        )
    }

    // -------------------------------------------------- 宿主路径脱敏

    @Test
    fun hostPathsAreScrubbedFromMessages() {
        val scrubbed = sanitizeDeviceHostPath(
            "Device directory does not exist: /storage/emulated/0/Documents/private",
            mountPath = "/storage/emulated/0/Documents",
        )
        assertFalse(scrubbed.contains("/storage/emulated/0"))
        assertTrue(scrubbed.contains("<device>"))
    }

    @Test
    fun absolutePathTokensAreScrubbed() {
        val scrubbed = sanitizeDeviceHostPath(
            "Path is not a file: /data/user/0/app/files/x.txt"
        )
        assertFalse(scrubbed.contains("/data/user"))
    }

    @Test
    fun relativeMessagesSurviveScrubbing() {
        val message = "Device mount not found"
        assertEquals(message, sanitizeDeviceHostPath(message))
    }
}

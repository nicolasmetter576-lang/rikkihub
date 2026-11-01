package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.rikkihub.web.FileOperationApiException
import me.rerere.rikkihub.web.dto.ErrorResponse
import me.rerere.workspace.WorkspaceArchiveException
import me.rerere.workspace.WorkspaceFileAlreadyExistsException
import me.rerere.workspace.WorkspaceFileOperationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException

class WorkspaceFileWriteContractTest {
    private fun parse(body: String) = parseWorkspaceFileWriteRequest(body.toByteArray())

    @Test fun emptyExclusiveCreatePreservesCompatibility() {
        val request = parse("""{"content":"","overwrite":false}""")
        assertEquals("", request.content)
        assertFalse(request.overwrite)
    }

    @Test fun absentOverwriteKeepsLegacyWriteContract() {
        assertTrue(parse("""{"content":"中文\\r\\n😀"}""").overwrite)
    }

    @Test fun conditionalSaveAcceptsOnlyAnExplicitSha256Revision() {
        val revision = "a".repeat(64)
        val request = parse("""{"content":"new","expectedRevision":"$revision"}""")
        assertTrue(request.overwrite)
        assertEquals(revision, request.expectedRevision)
        assertNull(parse("""{"content":"legacy"}""").expectedRevision)
        listOf("null", "false", "12", "\"short\"", "\"${"A".repeat(64)}\"").forEach { raw ->
            try {
                parse("""{"content":"","expectedRevision":$raw}""")
                fail("Invalid revision accepted")
            } catch (error: FileOperationApiException) {
                assertEquals(400, error.status.value)
            }
        }
        try {
            parse("""{"content":"","overwrite":false,"expectedRevision":"$revision"}""")
            fail("Revision on exclusive create accepted")
        } catch (error: FileOperationApiException) {
            assertEquals("FILE_INVALID_REQUEST", error.errorCode)
        }
    }

    @Test fun conditionalConflictAndTextFormatErrorsHaveStableSanitizedCodes() {
        mapOf("FILE_REVISION_CONFLICT" to 409, "FILE_NOT_UTF8" to 400, "FILE_NOT_TEXT" to 400).forEach { (code, status) ->
            val error = WorkspaceFileOperationException(code, "/data/private secret")
            val mapped = workspaceFileApiError(error)
            assertEquals(code, mapped.errorCode)
            assertEquals(status, mapped.status.value)
            assertEquals(status, deviceErrorStatus(error).value)
            assertFalse(mapped.message.contains("/data"))
            assertFalse(mapped.message.contains("secret"))
        }
    }

    @Test fun archiveErrorsKeepTheirCodesWithoutLeakingDetails() {
        mapOf(
            WorkspaceArchiveException.ARCHIVE_PASSWORD_REQUIRED to 422,
            WorkspaceArchiveException.ARCHIVE_WRONG_PASSWORD to 422,
            WorkspaceArchiveException.ARCHIVE_UNSUPPORTED to 415,
            WorkspaceArchiveException.ARCHIVE_ENCODING_UNSUPPORTED to 400,
            WorkspaceArchiveException.ARCHIVE_CORRUPT to 400,
        ).forEach { (code, status) ->
            val mapped = workspaceFileApiError(WorkspaceArchiveException(code, "/data/private secret"))
            assertEquals(code, mapped.errorCode)
            assertEquals(status, mapped.status.value)
            assertFalse(mapped.message.contains("/data"))
        }
    }

    @Test fun decodedContentIsNotRewritten() {
        assertEquals("\uFEFF### 中文😀\r\n", parse("""{"content":"\ufeff### 中文😀\r\n"}""").content)
    }

    @Test fun strictLiteralTypesAndAllowlistRejectMalformedBodies() {
        listOf(
            "[]", "null", "{}", "{", """{"content":null}""",
            """{"content":false}""", """{"content":12}""",
            """{"content":"","overwrite":"false"}""",
            """{"content":"","overwrite":null}""", """{"content":"","overwrite":0}""",
            """{"content":"","approval":true}""",
        ).forEach { raw ->
            try {
                parse(raw)
                fail("Malformed request accepted")
            } catch (error: FileOperationApiException) {
                assertEquals(HttpStatusCode.BadRequest, error.status)
                assertEquals("FILE_INVALID_REQUEST", error.errorCode)
            }
        }
    }

    @Test fun invalidUtf8Rejected() {
        try {
            parseWorkspaceFileWriteRequest(byteArrayOf(0xFF.toByte()))
            fail("Invalid UTF-8 accepted")
        } catch (error: FileOperationApiException) {
            assertEquals("FILE_INVALID_REQUEST", error.errorCode)
        }
    }

    @Test fun decodedUtf8ByteLimitEnforced() {
        val raw = "{\"content\":\"" + "中".repeat(MAX_FILE_WRITE_CONTENT_BYTES / 3 + 1) + "\"}"
        try {
            parse(raw)
            fail("Oversized content accepted")
        } catch (error: FileOperationApiException) {
            assertEquals(HttpStatusCode.PayloadTooLarge, error.status)
            assertEquals("FILE_TOO_LARGE", error.errorCode)
        }
    }

    @Test fun transportLimitEnforcedBeforeParsing() {
        try {
            parseWorkspaceFileWriteRequest(ByteArray(MAX_FILE_WRITE_BODY_BYTES.toInt() + 1))
            fail("Oversized body accepted")
        } catch (error: FileOperationApiException) {
            assertEquals(HttpStatusCode.PayloadTooLarge, error.status)
        }
    }

    @Test fun directoryCreationExplicitlySelectsExclusiveOrLegacyContract() {
        val exclusive = parseWorkspaceDirectoryRequest("""{"path":"notes","exclusive":true}""".toByteArray())
        assertEquals("notes", exclusive.path)
        assertTrue(exclusive.exclusive)
        assertFalse(parseWorkspaceDirectoryRequest("""{"path":"a/b"}""".toByteArray()).exclusive)
    }

    @Test fun directoryRequestUsesStrictBoundedSchema() {
        listOf("[]", "{}", """{"path":12}""", """{"path":"x","exclusive":"true"}""",
            """{"path":"x","exclusive":null}""", """{"path":"x","overwrite":true}""").forEach {
            try {
                parseWorkspaceDirectoryRequest(it.toByteArray())
                fail("Malformed directory request accepted")
            } catch (error: FileOperationApiException) {
                assertEquals(400, error.status.value)
            }
        }
        try {
            parseWorkspaceDirectoryRequest(ByteArray(MAX_DIRECTORY_BODY_BYTES.toInt() + 1))
            fail("Oversized directory request accepted")
        } catch (error: FileOperationApiException) {
            assertEquals(413, error.status.value)
        }
    }

    @Test fun invalidUtf8DirectoryRequestIs400Not500() {
        try {
            parseWorkspaceDirectoryRequest(byteArrayOf(0xFF.toByte()))
            fail("Invalid UTF-8 accepted")
        } catch (error: FileOperationApiException) {
            assertEquals(400, error.status.value)
            assertEquals("FILE_INVALID_REQUEST", error.errorCode)
        }
    }

    @Test fun fileAndDirectoryConflictIs409OnBothAdapters() {
        listOf(WorkspaceFileAlreadyExistsException(), FileAlreadyExistsException("/host/secret"))
            .forEach { error ->
                val mapped = workspaceFileApiError(error)
                assertEquals(HttpStatusCode.Conflict, mapped.status)
                assertEquals("FILE_ALREADY_EXISTS", mapped.errorCode)
                assertEquals(HttpStatusCode.Conflict, deviceErrorStatus(error))
                assertFalse(mapped.message.contains("/host"))
            }
    }

    @Test fun typedValidationCodesAreStableAndSanitized() {
        mapOf(
            WorkspaceFileOperationException.INVALID_FILE_NAME to "FILE_INVALID_NAME",
            WorkspaceFileOperationException.INVALID_PATH to "FILE_INVALID_PATH",
            WorkspaceFileOperationException.PARENT_NOT_FOUND to "FILE_PARENT_MISSING",
            WorkspaceFileOperationException.PARENT_NOT_DIRECTORY to "FILE_INVALID_PATH",
            WorkspaceFileOperationException.SYMLINK_NOT_ALLOWED to "FILE_SYMLINK_NOT_ALLOWED",
            WorkspaceFileOperationException.CONTENT_TOO_LARGE to "FILE_TOO_LARGE",
        ).forEach { (internal, wire) ->
            val mapped = workspaceFileApiError(WorkspaceFileOperationException(internal, "/host/private"))
            assertEquals(wire, mapped.errorCode)
            assertFalse(mapped.message.contains("/host"))
            assertEquals(if (wire == "FILE_TOO_LARGE") 413 else 400, mapped.status.value)
        }
    }

    @Test fun permissionMissingParentAndUnknownIoNeverExposeHostMessage() {
        listOf(
            AccessDeniedException("/data/private") to (403 to "FILE_PERMISSION_DENIED"),
            NoSuchFileException("/data/private") to (400 to "FILE_PARENT_MISSING"),
            IOException("/data/private key=secret") to (500 to "FILE_IO_ERROR"),
            IllegalArgumentException("/data/private") to (400 to "FILE_INVALID_PATH"),
        ).forEach { (error, expected) ->
            val mapped = workspaceFileApiError(error)
            assertEquals(expected.first, mapped.status.value)
            assertEquals(expected.second, mapped.errorCode)
            assertFalse(mapped.message.contains("/data"))
            assertFalse(mapped.message.contains("secret"))
        }
    }

    @Test fun cancellationPropagatesUnchanged() = runBlocking {
        val cancelled = CancellationException("cancel")
        try {
            workspaceFileApiOperation<Unit> { throw cancelled }
            fail("Cancellation swallowed")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
    }

    @Test fun typedApiErrorsAndMachineCodeSurviveSerialization() {
        val error = FileOperationApiException(HttpStatusCode.Conflict, "FILE_ALREADY_EXISTS", "File already exists")
        assertSame(error, workspaceFileApiError(error))
        assertEquals(409, deviceErrorStatus(error).value)
        val response = ErrorResponse(error.message, error.status.value, error.errorCode)
        val decoded = Json.decodeFromString<ErrorResponse>(Json.encodeToString(response))
        assertEquals(409, decoded.code)
        assertEquals("FILE_ALREADY_EXISTS", decoded.errorCode)
    }
}

package me.rerere.rikkihub.web.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import me.rerere.rikkihub.data.datastore.DisplaySetting
import me.rerere.rikkihub.data.datastore.EditorFontIds
import me.rerere.rikkihub.data.datastore.EditorTypography
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.webEditorTypography
import me.rerere.rikkihub.data.fonts.EditorFontFiles
import me.rerere.rikkihub.data.fonts.FontDownloadAlreadyRunningException
import me.rerere.rikkihub.data.fonts.FontDownloadState
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.ConflictException
import me.rerere.rikkihub.web.NotFoundException

/**
 * Editor fonts and the Web editor typography.
 *
 *   GET    /api/fonts                  -> WebFontsDto (catalog, install/download state, file versions)
 *   POST   /api/fonts/{id}/download    -> 202 FontDownloadState; 409 when already downloading
 *   DELETE /api/fonts/{id}/download    -> cancels a running download or clears a failed one
 *   DELETE /api/fonts/{id}             -> deletes the downloaded file; 409 while downloading
 *   GET    /api/fonts/{id}/file?v=…    -> the font file (immutable cache when `v` is given)
 *   PUT    /api/display/workspace-editor-typography  merge-patch {font?, fontSize?, letterSpacing?, lineHeight?}
 *          -> the Web typography after the write (the App one is never touched)
 *   PUT    /api/display/workspace-editor  merge-patch {autoPair?} -> {autoPair}
 *          (the Web editor's auto-closing pairs; the App editor has its own switch)
 */
@Serializable
data class WebDownloadableFontDto(
    val id: String,
    val name: String,
    val family: String,
    val sizeBytes: Long,
    val installed: Boolean,
    val license: String,
    val licenseUrl: String,
    val download: FontDownloadState? = null,
)

@Serializable
data class WebFontsDto(
    val fonts: List<WebDownloadableFontDto>,
    /** Font id -> file version, for every id that has a file the browser can load right now. */
    val files: Map<String, String>,
    /** Display name of the imported custom font, null when there is none. */
    val customName: String? = null,
)

internal fun projectWebFonts(fonts: EditorFontFiles, display: DisplaySetting): WebFontsDto {
    val downloads = fonts.downloads
    val installed = downloads.installed.value
    val states = downloads.states.value
    val list = downloads.catalog.map { font ->
        WebDownloadableFontDto(
            id = font.id,
            name = font.displayName,
            family = font.family,
            sizeBytes = font.sizeBytes,
            installed = font.id in installed,
            license = font.license,
            licenseUrl = font.licenseUrl,
            download = states[font.id],
        )
    }
    val files = buildMap {
        for (id in EditorFontIds.ALL) fonts.resolve(id, display)?.let { put(id, it.version) }
    }
    val custom = fonts.resolve(EditorFontIds.CUSTOM, display)?.let { display.chatCustomFontName.ifBlank { "custom" } }
    return WebFontsDto(list, files, custom)
}

private fun EditorFontFiles.requireDownloadable(id: String) =
    downloads.font(id) ?: throw NotFoundException("Unknown font: $id")

/** Merge-patch onto [current]; unknown keys, wrong types and out-of-range values are 400. */
internal fun mergeEditorTypography(current: EditorTypography, body: JsonObject): EditorTypography {
    val unknown = body.keys - setOf("font", "fontSize", "letterSpacing", "lineHeight")
    if (unknown.isNotEmpty()) throw BadRequestException("Unknown keys: ${unknown.sorted().joinToString()}")
    fun number(key: String, min: Float, max: Float): Float? = body[key]?.let { value ->
        val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull
            ?.takeIf { !it.isNaN() && !it.isInfinite() }
            ?: throw BadRequestException("'$key' must be a number")
        if (number < min || number > max) throw BadRequestException("'$key' must be $min..$max")
        number
    }
    val font = body["font"]?.let { value ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in EditorFontIds.ALL }
            ?: throw BadRequestException("'font' must be one of ${EditorFontIds.ALL.joinToString()}")
    }
    return current.copy(
        font = font ?: current.font,
        fontSize = number("fontSize", EditorTypography.MIN_FONT_SIZE, EditorTypography.MAX_FONT_SIZE) ?: current.fontSize,
        letterSpacing = number("letterSpacing", EditorTypography.MIN_LETTER_SPACING, EditorTypography.MAX_LETTER_SPACING)
            ?: current.letterSpacing,
        lineHeight = number("lineHeight", EditorTypography.MIN_LINE_HEIGHT, EditorTypography.MAX_LINE_HEIGHT) ?: current.lineHeight,
    )
}

@Serializable
data class WorkspaceEditorDisplayDto(val autoPair: Boolean)

/** Merge-patch of the editors' behaviour switches onto [current]; unknown keys and non-boolean values are 400. */
internal fun mergeWorkspaceEditorDisplay(current: WorkspaceEditorDisplayDto, body: JsonObject): WorkspaceEditorDisplayDto {
    val unknown = body.keys - setOf("autoPair")
    if (unknown.isNotEmpty()) throw BadRequestException("Unknown keys: ${unknown.sorted().joinToString()}")
    val autoPair = body["autoPair"]?.let { value ->
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw BadRequestException("'autoPair' must be a boolean")
    }
    return WorkspaceEditorDisplayDto(autoPair ?: current.autoPair)
}

/** The Web editor's switches patched onto [display]; the App editor's own ones are never touched. */
internal fun patchWebEditorDisplay(display: DisplaySetting, body: JsonObject): DisplaySetting {
    val next = mergeWorkspaceEditorDisplay(WorkspaceEditorDisplayDto(display.webWorkspaceEditorAutoPair), body)
    return display.copy(webWorkspaceEditorAutoPair = next.autoPair)
}

fun Route.editorTypographyRoutes(settingsStore: SettingsStore) {
    put("/display/workspace-editor") {
        val body = try {
            call.receive<JsonObject>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw BadRequestException("Invalid request body")
        }
        patchWebEditorDisplay(settingsStore.settingsFlow.value.displaySetting, body) // validate before writing
        var result: DisplaySetting? = null
        settingsStore.update { settings ->
            settings.copy(displaySetting = patchWebEditorDisplay(settings.displaySetting, body).also { result = it })
        }
        val display = result ?: settingsStore.settingsFlow.value.displaySetting
        call.respond(HttpStatusCode.OK, WorkspaceEditorDisplayDto(display.webWorkspaceEditorAutoPair))
    }

    put("/display/workspace-editor-typography") {
        val body = try {
            call.receive<JsonObject>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw BadRequestException("Invalid request body")
        }
        mergeEditorTypography(settingsStore.settingsFlow.value.displaySetting.webEditorTypography, body) // validate before writing
        var result: EditorTypography? = null
        settingsStore.update { settings ->
            val display = settings.displaySetting
            val next = mergeEditorTypography(display.webEditorTypography, body).also { result = it }
            settings.copy(displaySetting = display.copy(webWorkspaceEditorTypography = next))
        }
        call.respond(HttpStatusCode.OK, result ?: settingsStore.settingsFlow.value.displaySetting.webEditorTypography)
    }
}

fun Route.fontRoutes(fonts: EditorFontFiles, settingsStore: SettingsStore) {
    route("/fonts") {
        get {
            call.respond(HttpStatusCode.OK, projectWebFonts(fonts, settingsStore.settingsFlow.value.displaySetting))
        }
        post("/{id}/download") {
            val font = fonts.requireDownloadable(call.parameters["id"].orEmpty())
            val state = try {
                fonts.downloads.start(font.id)
            } catch (_: FontDownloadAlreadyRunningException) {
                throw ConflictException("Font download already running")
            }
            call.respond(HttpStatusCode.Accepted, state)
        }
        delete("/{id}/download") {
            val font = fonts.requireDownloadable(call.parameters["id"].orEmpty())
            if (!fonts.downloads.cancel(font.id)) fonts.downloads.dismiss(font.id)
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/{id}") {
            val font = fonts.requireDownloadable(call.parameters["id"].orEmpty())
            try {
                fonts.downloads.delete(font.id)
            } catch (_: FontDownloadAlreadyRunningException) {
                throw ConflictException("Font download running")
            }
            call.respond(HttpStatusCode.NoContent)
        }
        get("/{id}/file") {
            val id = call.parameters["id"].orEmpty()
            val file = fonts.resolve(id, settingsStore.settingsFlow.value.displaySetting)
                ?: throw NotFoundException("Font file not available: $id")
            val requested = call.request.queryParameters["v"]
            call.response.header(
                HttpHeaders.CacheControl,
                if (requested == file.version) "private, max-age=31536000, immutable" else "private, no-cache",
            )
            call.response.header(HttpHeaders.ETag, "\"${file.version}\"")
            call.respondOutputStream(contentType = ContentType("font", "ttf"), contentLength = file.length) {
                withContext(Dispatchers.IO) { file.open().use { it.copyTo(this@respondOutputStream) } }
            }
        }
    }
}

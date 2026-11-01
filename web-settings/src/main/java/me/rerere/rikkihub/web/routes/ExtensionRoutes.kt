package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkihub.data.ai.mcp.McpServerConfig
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.PromptInjection
import me.rerere.rikkihub.data.model.QuickMessage
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.rikkihub.web.projectMcpServerForWeb
import kotlin.uuid.Uuid

fun Route.extensionRoutes(settingsStore: SettingsStore) {
    crudRoutes<QuickMessage>(
        path = "/quick-messages",
        idParam = "quickMessageId",
        notFoundMessage = "Quick message not found",
        settingsStore = settingsStore,
        getList = { it.quickMessages },
        withList = { settings, list -> settings.copy(quickMessages = list) },
        idOf = { it.id },
        copyWithId = { item, id -> item.copy(id = id) },
        onDelete = { settings, id ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    assistant.copy(quickMessageIds = assistant.quickMessageIds - id)
                }
            )
        },
    )

    crudRoutes<PromptInjection.ModeInjection>(
        path = "/mode-injections",
        idParam = "modeInjectionId",
        notFoundMessage = "Mode injection not found",
        settingsStore = settingsStore,
        getList = { it.modeInjections },
        withList = { settings, list -> settings.copy(modeInjections = list) },
        idOf = { it.id },
        copyWithId = { item, id -> item.copy(id = id) },
        onDelete = { settings, id ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    assistant.copy(modeInjectionIds = assistant.modeInjectionIds - id)
                }
            )
        },
    )

    crudRoutes<Lorebook>(
        path = "/lorebooks",
        idParam = "lorebookId",
        notFoundMessage = "Lorebook not found",
        settingsStore = settingsStore,
        getList = { it.lorebooks },
        withList = { settings, list -> settings.copy(lorebooks = list) },
        idOf = { it.id },
        copyWithId = { item, id -> item.copy(id = id) },
        onDelete = { settings, id ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    assistant.copy(lorebookIds = assistant.lorebookIds - id)
                }
            )
        },
    )

    // MCP 用显式路由：PUT 需要 presence-aware 的秘密 merge（headers 值在
    // 投影响应中掩码，客户端回写 masked/null 必须保留服务端最新值），
    // 不能走 crudRoutes 的整对象 decode。
    route("/mcp-servers") {
        post {
            val raw = call.receive<JsonObject>()
            val draft = parseMcpUpdate(raw)
            val id = Uuid.random()
            val created = draft.incoming.clone(id = id).let { created ->
                // Create has nothing to preserve — draft entries with null
                // (preserve) values are meaningless and become empty strings.
                when (created) {
                    is McpServerConfig.SseTransportServer -> created.copy(
                        commonOptions = created.commonOptions.copy(
                            headers = draft.headers?.map { it.name to it.newValue.orEmpty() }
                                ?: created.commonOptions.headers,
                        )
                    )
                    is McpServerConfig.StreamableHTTPServer -> created.copy(
                        commonOptions = created.commonOptions.copy(
                            headers = draft.headers?.map { it.name to it.newValue.orEmpty() }
                                ?: created.commonOptions.headers,
                        )
                    )
                }
            }
            settingsStore.update { settings ->
                settings.copy(mcpServers = settings.mcpServers + created)
            }
            call.respond(HttpStatusCode.Created, projectMcpServerForWeb(created))
        }

        put("/{id}") {
            val id = call.parameters["id"].toUuid("mcpServerId")
            val raw = call.receive<JsonObject>()
            val draft = parseMcpUpdate(raw)
            lateinit var merged: McpServerConfig
            settingsStore.update { settings ->
                val existing = settings.mcpServers.find { it.id == id }
                    ?: throw NotFoundException("MCP server not found")
                merged = mergeMcpServer(existing, draft)
                settings.copy(
                    mcpServers = settings.mcpServers.map { item -> if (item.id == id) merged else item },
                )
            }
            call.respond(HttpStatusCode.OK, projectMcpServerForWeb(merged))
        }

        delete("/{id}") {
            val id = call.parameters["id"].toUuid("mcpServerId")
            settingsStore.update { settings ->
                if (settings.mcpServers.none { it.id == id }) {
                    throw NotFoundException("MCP server not found")
                }
                settings.copy(
                    mcpServers = settings.mcpServers.filter { it.id != id },
                    assistants = settings.assistants.map { assistant ->
                        assistant.copy(mcpServers = assistant.mcpServers - id)
                    },
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private inline fun <reified T : Any> Route.crudRoutes(
    path: String,
    idParam: String,
    notFoundMessage: String,
    settingsStore: SettingsStore,
    crossinline getList: (Settings) -> List<T>,
    crossinline withList: (Settings, List<T>) -> Settings,
    crossinline idOf: (T) -> Uuid,
    crossinline copyWithId: (T, Uuid) -> T,
    crossinline mergeForUpdate: (T, T) -> T = { _, incoming -> incoming },
    crossinline onDelete: (Settings, Uuid) -> Settings = { settings, _ -> settings },
) {
    route(path) {
        post {
            val incoming = call.receive<T>()
            val created = copyWithId(incoming, Uuid.random())
            settingsStore.update { settings ->
                withList(settings, getList(settings) + created)
            }
            call.respond(HttpStatusCode.Created, created)
        }

        put("/{id}") {
            val id = call.parameters["id"].toUuid(idParam)
            val incoming = call.receive<T>()
            lateinit var merged: T
            settingsStore.update { settings ->
                val existing = getList(settings).find { idOf(it) == id }
                    ?: throw NotFoundException(notFoundMessage)
                merged = copyWithId(mergeForUpdate(existing, incoming), id)
                withList(
                    settings,
                    getList(settings).map { item -> if (idOf(item) == id) merged else item },
                )
            }
            call.respond(HttpStatusCode.OK, merged)
        }

        delete("/{id}") {
            val id = call.parameters["id"].toUuid(idParam)
            settingsStore.update { settings ->
                if (getList(settings).none { idOf(it) == id }) {
                    throw NotFoundException(notFoundMessage)
                }
                onDelete(
                    withList(settings, getList(settings).filter { idOf(it) != id }),
                    id,
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

package me.rerere.rikkihub.web

import android.content.Context
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkihub.data.ai.TranslationHandler
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.FavoriteRepository
import me.rerere.rikkihub.data.repository.FolderRepository
import me.rerere.rikkihub.data.repository.MemoryRepository
import me.rerere.rikkihub.data.ai.tools.WorkspacePersistentBashSessionManager
import me.rerere.rikkihub.data.repository.SubagentPresetRepository
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceDirectoryService
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceStorage
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.routes.aiIconRoutes
import me.rerere.rikkihub.web.routes.assetsRoutes
import me.rerere.rikkihub.web.routes.conversationRoutes
import me.rerere.rikkihub.web.routes.eventsRoutes
import me.rerere.rikkihub.web.routes.filesRoutes
import me.rerere.rikkihub.web.routes.folderRoutes
import me.rerere.rikkihub.web.routes.WebWorkspaceTerminalManager
import me.rerere.rikkihub.web.routes.installWebSettingsRoutes
import me.rerere.rikkihub.web.routes.settingsRoutes
import java.io.File

/**
 * Configure Web API for the Ktor application.
 * This should be called from app module when starting the web server.
 *
 * Example usage:
 * ```
 * startWebServer(port = 8080) {
 *     configureWebApi(context, chatService, conversationRepo, settingsStore, filesManager)
 * }
 * ```
 */
fun Application.configureWebApi(
    context: Context,
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
    filesManager: FilesManager,
    memoryRepo: MemoryRepository,
    providerManager: ProviderManager,
    workspaceRepository: WorkspaceRepository,
    terminalManager: WebWorkspaceTerminalManager,
    persistentBashSessionManager: WorkspacePersistentBashSessionManager,
    favoriteRepo: FavoriteRepository,
    translationHandler: TranslationHandler,
    deviceService: WorkspaceDeviceDirectoryService? = null,
    deviceStorage: WorkspaceDeviceStorage? = null,
    subagentPresetRepository: SubagentPresetRepository? = null,
    skillManager: me.rerere.rikkihub.data.files.SkillManager? = null,
    rootfsInstallService: me.rerere.rikkihub.data.workspace.WorkspaceRootfsInstallService? = null,
    fontDownloadService: me.rerere.rikkihub.data.fonts.FontDownloadService? = null,
    translationHistoryRepo: me.rerere.rikkihub.data.repository.TranslationHistoryRepository? = null,
    memoryScheduler: me.rerere.rikkihub.data.ai.MemoryConsolidationScheduler? = null,
    // Optional injection point for DI (AppModule); null → per-server instance
    // under filesDir/web-auth (fail-closed default, no external wiring needed).
    webAuthSecurity: WebAuthSecurity? = null,
) {
    install(ContentNegotiation) {
        json(JsonInstant)
    }

    installWebApiStatusPages()

    // Random server secret + session/ticket lifecycle. The access password is
    // verification material only — never the signing key. Lazily created under
    // filesDir/web-auth so DI modules that do not register an explicit instance
    // still get fail-closed behavior.
    val security = webAuthSecurity ?: WebAuthSecurity(File(context.filesDir, "web-auth"))
    val rateLimiter = WebAuthRateLimiter()

    // Authentication is always installed so the JWT provider can be challenged
    // whenever the CURRENT settings allow it. Admission is decided per request
    // (see the gate intercept below): disabling web auth or clearing/changing
    // the access password takes effect immediately for already-running servers
    // and revokes previously-issued tokens — no restart is required.
    install(Authentication) {
        installWebJwtAuth(security) {
            val current = settingsStore.settingsFlow.value
            WebAuthConfig(
                jwtEnabled = current.webServerJwtEnabled,
                accessPassword = current.webServerAccessPassword,
                requireHttps = current.webServerHttpsEnabled,
            )
        }
    }

    // Gate + Origin admission for the admin surface, registered on the
    // Application Plugins phase so it runs before route-level plugins
    // (including `authenticate`) for every /api/** call — REST, SSE and the
    // WS terminal upgrade alike. The config snapshot is read per request, so
    // disabling web auth or clearing/changing the access password takes effect
    // immediately on a running server and revokes previously-issued tokens —
    // no restart is required.
    installWebApiBoundary {
        val current = settingsStore.settingsFlow.value
        WebAuthConfig(
            jwtEnabled = current.webServerJwtEnabled,
            accessPassword = current.webServerAccessPassword,
            requireHttps = current.webServerHttpsEnabled,
        )
    }

    routing {
        route("/api") {
            publicWebApiRoutes(
                security = security,
                rateLimiter = rateLimiter,
                currentConfig = {
                    val current = settingsStore.settingsFlow.value
                    WebAuthConfig(
                        jwtEnabled = current.webServerJwtEnabled,
                        accessPassword = current.webServerAccessPassword,
                        requireHttps = current.webServerHttpsEnabled,
                    )
                },
            )

            aiIconRoutes(context)

            authenticate(WEB_OPEN_PROVIDER, WEB_JWT_PROVIDER) {
                authenticatedWebAuthRoutes(
                    security = security,
                    currentConfig = {
                        val current = settingsStore.settingsFlow.value
                        WebAuthConfig(
                            jwtEnabled = current.webServerJwtEnabled,
                            accessPassword = current.webServerAccessPassword,
                            requireHttps = current.webServerHttpsEnabled,
                        )
                    },
                )
                installApiRoutes(
                    chatService = chatService,
                    conversationRepo = conversationRepo,
                    folderRepo = folderRepo,
                    settingsStore = settingsStore,
                    filesManager = filesManager,
                    memoryRepo = memoryRepo,
                    providerManager = providerManager,
                    workspaceRepository = workspaceRepository,
                    terminalManager = terminalManager,
                    persistentBashSessionManager = persistentBashSessionManager,
                    favoriteRepo = favoriteRepo,
                    translationHandler = translationHandler,
                    context = context,
                    deviceService = deviceService,
                    deviceStorage = deviceStorage,
                    subagentPresetRepository = subagentPresetRepository,
                    skillManager = skillManager,
                    rootfsInstallService = rootfsInstallService,
                    fontDownloadService = fontDownloadService,
                    translationHistoryRepo = translationHistoryRepo,
                    memoryScheduler = memoryScheduler,
                )
            }

            // JSON 404 for /api paths that matched nothing — the SPA fallback in
            // the static route must not answer unknown API calls with index.html.
            route("/{...}") {
                handle {
                    throw NotFoundException("Not Found")
                }
            }
        }
    }
}

/**
 * The API surface shared by the JWT-authenticated and open variants. Installed
 * once per auth branch; both must keep calling installWebSettingsRoutes.
 */
private fun Route.installApiRoutes(
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
    filesManager: FilesManager,
    memoryRepo: MemoryRepository,
    providerManager: ProviderManager,
    workspaceRepository: WorkspaceRepository,
    terminalManager: WebWorkspaceTerminalManager,
    persistentBashSessionManager: WorkspacePersistentBashSessionManager,
    favoriteRepo: FavoriteRepository,
    translationHandler: TranslationHandler,
    context: Context,
    deviceService: WorkspaceDeviceDirectoryService?,
    deviceStorage: WorkspaceDeviceStorage?,
    subagentPresetRepository: SubagentPresetRepository?,
    skillManager: me.rerere.rikkihub.data.files.SkillManager? = null,
    rootfsInstallService: me.rerere.rikkihub.data.workspace.WorkspaceRootfsInstallService? = null,
    fontDownloadService: me.rerere.rikkihub.data.fonts.FontDownloadService? = null,
    translationHistoryRepo: me.rerere.rikkihub.data.repository.TranslationHistoryRepository? = null,
    memoryScheduler: me.rerere.rikkihub.data.ai.MemoryConsolidationScheduler? = null,
) {
    conversationRoutes(chatService, conversationRepo, folderRepo, settingsStore, favoriteRepo)
    folderRoutes(chatService, folderRepo, settingsStore)
    eventsRoutes(chatService, conversationRepo, folderRepo, settingsStore, workspaceRepository, subagentPresetRepository = subagentPresetRepository)
    settingsRoutes(settingsStore)
    installWebSettingsRoutes(
        settingsStore,
        conversationRepo,
        memoryRepo,
        filesManager,
        providerManager,
        workspaceRepository,
        terminalManager,
        persistentBashSessionManager,
        chatService,
        translationHandler,
        deviceService = deviceService,
        deviceStorage = deviceStorage,
        subagentPresetRepository = subagentPresetRepository,
        favoriteRepo = favoriteRepo,
        skillManager = skillManager,
        rootfsInstallService = rootfsInstallService,
        editorFonts = fontDownloadService?.let { me.rerere.rikkihub.data.fonts.EditorFontFiles.forContext(context, it) },
        translationHistoryRepo = translationHistoryRepo,
        memoryScheduler = memoryScheduler,
    )
    filesRoutes(filesManager, context)
    assetsRoutes(context)
}

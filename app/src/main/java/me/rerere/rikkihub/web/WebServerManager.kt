package me.rerere.rikkihub.web

import android.content.Context
import android.util.Log
import io.ktor.server.netty.NettyApplicationEngine
import me.rerere.rikkihub.web.tls.AndroidTlsMaterial
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.AppScope
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkihub.data.ai.TranslationHandler
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.FavoriteRepository
import me.rerere.rikkihub.data.repository.FolderRepository
import me.rerere.rikkihub.data.repository.MemoryRepository
import me.rerere.rikkihub.data.ai.tools.WorkspacePersistentBashSessionManager
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.web.routes.WebWorkspaceTerminalManager
import me.rerere.rikkihub.web.startWebServer
import java.net.ServerSocket

private const val TAG = "WebServerManager"
private const val HOST_ALL_INTERFACES = "0.0.0.0"
private const val HOST_LOOPBACK = "127.0.0.1"

data class WebServerState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val port: Int = 8080,
    val serviceName: String = DEFAULT_SERVICE_NAME,
    val localhostOnly: Boolean = false,
    val hostname: String? = null,
    val address: String? = null,
    val error: String? = null,
    val certificateFingerprint: String? = null,
    val certificateFilePath: String? = null,
    val secure: Boolean = false,
)

class WebServerManager(
    private val context: Context,
    private val appScope: AppScope,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val settingsStore: SettingsStore,
    private val filesManager: FilesManager,
    private val memoryRepo: MemoryRepository,
    private val providerManager: ProviderManager,
    private val workspaceRepository: WorkspaceRepository,
    private val terminalManager: WebWorkspaceTerminalManager,
    private val persistentBashSessionManager: WorkspacePersistentBashSessionManager,
    private val favoriteRepo: FavoriteRepository,
    private val translationHandler: TranslationHandler,
    private val deviceService: me.rerere.rikkihub.data.workspace.WorkspaceDeviceDirectoryService? = null,
    private val deviceStorage: me.rerere.rikkihub.data.workspace.WorkspaceDeviceStorage? = null,
    private val subagentPresetRepository: me.rerere.rikkihub.data.repository.SubagentPresetRepository? = null,
    private val skillManager: me.rerere.rikkihub.data.files.SkillManager? = null,
    private val rootfsInstallService: me.rerere.rikkihub.data.workspace.WorkspaceRootfsInstallService? = null,
    private val fontDownloadService: me.rerere.rikkihub.data.fonts.FontDownloadService? = null,
    private val translationHistoryRepo: me.rerere.rikkihub.data.repository.TranslationHistoryRepository? = null,
    private val memoryScheduler: me.rerere.rikkihub.data.ai.MemoryConsolidationScheduler? = null,
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val tlsMaterial = AndroidTlsMaterial(context)
    private val nsdRegistrar = NsdServiceRegistrar(context)

    // Serializes start/stop/restart: appScope runs on the main dispatcher, so
    // concurrent calls could race on `server` and leave two engines running.
    private val serverMutex = Mutex()

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    fun start(
        port: Int = 8080,
        serviceName: String = DEFAULT_SERVICE_NAME,
        localhostOnly: Boolean = false
    ) {
        appScope.launch {
            serverMutex.withLock {
                startLocked(port, serviceName, localhostOnly)
            }
        }
    }

    private suspend fun startLocked(
        port: Int,
        serviceName: String,
        localhostOnly: Boolean
    ) {
        if (server != null) {
            Log.w(TAG, "Server already running")
            return
        }

        // 仅本机模式绑定回环地址
        val host = if (localhostOnly) HOST_LOOPBACK else HOST_ALL_INTERFACES
        val baseState = WebServerState(
            port = port,
            serviceName = serviceName,
            localhostOnly = localhostOnly
        )
        try {
            _state.value = _state.value.copy(isLoading = true)
            Log.i(TAG, "Starting web server on $host:$port")
            // embeddedServer + engine start block; keep them off the main thread.
            val https = settingsStore.settingsFlow.value.webServerHttpsEnabled
            var material: AndroidTlsMaterial.Material? = null
            val started = withContext(Dispatchers.IO) {
                if (!isPortAvailable(port)) {
                    null
                } else {
                    if (https) material = tlsMaterial.load(serviceName)
                    startWebServer(port = port, host = host, tls = material?.config) {
                        configureWebApi(
                            context,
                            chatService,
                            conversationRepo,
                            folderRepo,
                            settingsStore,
                            filesManager,
                            memoryRepo,
                            providerManager,
                            workspaceRepository,
                            terminalManager,
                            persistentBashSessionManager,
                            favoriteRepo,
                            translationHandler,
                            deviceService = deviceService,
                            deviceStorage = deviceStorage,
                            subagentPresetRepository = subagentPresetRepository,
                            skillManager = skillManager,
                            rootfsInstallService = rootfsInstallService,
                            fontDownloadService = fontDownloadService,
                            translationHistoryRepo = translationHistoryRepo,
                            memoryScheduler = memoryScheduler,
                        )
                    }.start(wait = false)
                }
            }
            if (started == null) {
                Log.w(TAG, "Port $port is already in use")
                _state.value = baseState.copy(error = "Port $port is already in use")
                return
            }
            server = started

            _state.value = baseState.copy(isRunning = true, secure = https,
                certificateFingerprint = material?.fingerprint,
                certificateFilePath = material?.certificateFile?.absolutePath)
            // 仅局域网模式注册 mDNS
            if (!localhostOnly) {
                runCatching {
                    nsdRegistrar.register(
                        port = port,
                        serviceName = serviceName,
                        onRegistered = { info ->
                            _state.value = _state.value.copy(
                                serviceName = info.serviceName,
                                hostname = info.hostname,
                                address = info.address.hostAddress
                            )
                        }
                    )
                }.onFailure {
                    Log.w(TAG, "NSD register failed", it)
                }
            }
            Log.i(TAG, "Web server started successfully on $host:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start web server", e)
            _state.value = baseState.copy(error = e.message)
        }
    }

    fun reportError(message: String) {
        _state.value = _state.value.copy(isRunning = false, isLoading = false, error = message)
    }

    fun stop() {
        _state.value =
            _state.value.copy(isRunning = false, isLoading = true, hostname = null, address = null, error = null)
        appScope.launch {
            serverMutex.withLock {
                stopLocked()
            }
        }
    }

    private suspend fun stopLocked() {
        try {
            Log.i(TAG, "Stopping web server")
            // 先关终端会话, 让 PTY 在 server 停止前完成清理。
            // pty.close() 内有 SIGKILL 宽限 sleep, server.stop 也会阻塞至优雅期结束,
            // 都放到 IO 线程, 不占主线程。
            withContext(Dispatchers.IO) {
                runCatching { terminalManager.closeAll() }
                server?.stop(1000, 2000)
            }
            server = null
            runCatching {
                nsdRegistrar.unregister()
            }.onFailure {
                Log.w(TAG, "NSD unregister failed", it)
            }
            _state.value = _state.value.copy(isLoading = false)
            Log.i(TAG, "Web server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop web server", e)
            _state.value = _state.value.copy(isLoading = false, error = e.message)
        }
    }

    /**
     * Serial restart: the stop must finish before the start binds the port,
     * otherwise the new engine fails with "port already in use".
     */
    fun restart(
        port: Int = _state.value.port,
        serviceName: String = _state.value.serviceName,
        localhostOnly: Boolean = _state.value.localhostOnly
    ) {
        _state.value =
            _state.value.copy(isRunning = false, isLoading = true, hostname = null, address = null, error = null)
        appScope.launch {
            serverMutex.withLock {
                stopLocked()
                startLocked(port, serviceName, localhostOnly)
            }
        }
    }

    /** Rotation stops old TLS keys/sessions before generating the replacement trust root. */
    fun rotateCertificate(expectedFingerprint: String) {
        appScope.launch {
            serverMutex.withLock {
                val old = _state.value
                if (old.certificateFingerprint != expectedFingerprint) return@withLock
                stopLocked()
                withContext(Dispatchers.IO) { tlsMaterial.rotate() }
                if (old.isRunning) startLocked(old.port, old.serviceName, old.localhostOnly)
            }
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }
}

package me.rerere.rikkihub.di

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderManager
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.BuildConfig
import me.rerere.rikkihub.data.ai.AIRequestInterceptor
import me.rerere.rikkihub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkihub.data.ai.transformers.AssistantTemplateLoader
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import me.rerere.rikkihub.data.ai.GenerationLoop
import me.rerere.rikkihub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkihub.data.ai.MemoryConsolidator
import me.rerere.rikkihub.data.ai.generationLoopDreamRunner
import me.rerere.rikkihub.service.memoryDreamKeepAlive
import me.rerere.rikkihub.data.ai.TranslationHandler
import me.rerere.rikkihub.data.ai.polish.PromptPolishHandler
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoHandler
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoHandler
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceTuiqiaoCache
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceZaizaoCache
import me.rerere.rikkihub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkihub.data.codex.CodexAccountRepository
import me.rerere.rikkihub.data.codex.CodexCredentialStore
import me.rerere.rikkihub.data.codex.CodexOAuthManager
import me.rerere.rikkihub.data.codex.CodexProvider
import me.rerere.ai.provider.providers.antigravity.AntigravityChatApi
import me.rerere.ai.provider.providers.devin.DevinChatApi
import me.rerere.rikkihub.data.antigravity.AntigravityAccountRepository
import me.rerere.rikkihub.data.antigravity.AntigravityCredentialStore
import me.rerere.rikkihub.data.antigravity.AntigravityOAuthManager
import me.rerere.rikkihub.data.antigravity.AntigravityProvider
import me.rerere.rikkihub.data.devin.DevinAccountRepository
import me.rerere.rikkihub.data.devin.DevinCredentialStore
import me.rerere.rikkihub.data.devin.DevinOAuthManager
import me.rerere.rikkihub.data.devin.DevinProvider
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.sync.BackupManager
import me.rerere.rikkihub.data.db.AppDatabaseFactory
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.fts.MessageFtsManager
import me.rerere.rikkihub.data.ai.mcp.McpManager
import me.rerere.rikkihub.data.network.SettingsProxySelector
import me.rerere.rikkihub.data.network.SettingsProxyAuthenticator
import me.rerere.rikkihub.data.network.SettingsSocks5Authenticator
import me.rerere.rikkihub.data.sync.webdav.WebDavSync
import me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate
import me.rerere.search.SearchService
import me.rerere.rikkihub.data.sync.S3Sync
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.rikkihub.data.remote.RemoteFileStore

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    single {
        val context: Context = get()
        AppDatabaseFactory.create(context)
    }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().memoryConsolidationDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().workspaceDao()
    }

    single {
        get<AppDatabase>().workspaceDeviceMountDao()
    }

    single {
        get<AppDatabase>().subagentPresetLibraryDao()
    }

    single {
        get<AppDatabase>().folderDao()
    }

    single {
        get<AppDatabase>().translationHistoryDao()
    }

    single {
        get<AppDatabase>().mediaCreationDao()
    }

    single {
        RemoteFileStore(
            settingsStore = get(),
            httpClient = get(),
            scope = get<AppScope>()
        )
    }

    // 图像接口同步返回，一张高质量图可能要几分钟；共用的客户端读超时 10 分钟
    single {
        MediaGenerationManager(client = get())
    }

    single {
        MessageFtsManager(get())
    }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get()) }

    // T0 公共运行准入：workspace 级 gate/epoch/登记/cancel+join（纯内存，无 Context/DB）
    single { WorkspaceRuntimeGate() }

    single {
        GenerationLoop(
            context = get(),
            providerManager = get(),
            json = get(),
        )
    }

    single {
        MemoryConsolidator(
            runner = generationLoopDreamRunner(get()),
            memoryRepository = get(),
            conversationRepo = get(),
            consolidationDao = get(),
            settingsStore = get(),
        )
    }

    single {
        val settingsStore = get<SettingsStore>()
        MemoryConsolidationScheduler(
            scope = get<AppScope>(),
            worker = get<MemoryConsolidator>(),
            keepAlive = memoryDreamKeepAlive(get<Context>()),
            isAppVisible = { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
            config = { settingsStore.settingsFlow.value.memoryDream },
        )
    }

    single {
        TranslationHandler(providerManager = get())
    }

    single {
        TuiqiaoHandler(providerManager = get())
    }

    single {
        PromptPolishHandler(providerManager = get())
    }

    single {
        val handler = get<TuiqiaoHandler>()
        val settingsStore = get<SettingsStore>()
        WorkspaceTuiqiaoCache(scope = get<AppScope>()) { prepared -> handler.events(settingsStore.settingsFlow.value, prepared) }
    }

    single {
        ZaizaoHandler(providerManager = get())
    }

    single {
        val handler = get<ZaizaoHandler>()
        val settingsStore = get<SettingsStore>()
        val scope = get<AppScope>()
        WorkspaceZaizaoCache(
            scope = scope,
            settings = { settingsStore.settingsFlow.value },
            generate = handler::generate,
            remember = { requirement ->
                scope.launch { settingsStore.update { it.copy(zaizao = it.zaizao.remember(requirement)) } }
            },
        )
    }

    single<OkHttpClient> {
        val settingsStore: SettingsStore = get()
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        java.net.Authenticator.setDefault(SettingsSocks5Authenticator(settingsStore))
        val initialNetworkSetting = settingsStore.settingsFlow.value.networkSetting
        val appliedProxySetting = AtomicReference(
            Triple(
                initialNetworkSetting.proxyUrl,
                initialNetworkSetting.proxyUsername,
                initialNetworkSetting.proxyPassword,
            )
        )
        lateinit var client: OkHttpClient
        client = OkHttpClient.Builder()
            .proxySelector(SettingsProxySelector(settingsStore))
            .proxyAuthenticator(SettingsProxyAuthenticator(settingsStore))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val networkSetting = settingsStore.settingsFlow.value.networkSetting
                val currentProxySetting = Triple(
                    networkSetting.proxyUrl,
                    networkSetting.proxyUsername,
                    networkSetting.proxyPassword,
                )
                if (appliedProxySetting.getAndSet(currentProxySetting) != currentProxySetting) {
                    client.connectionPool.evictAll()
                }

                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                    val userAgent = settingsStore.settingsFlow.value.networkSetting.userAgent
                        .trim()
                        .ifEmpty { "Rikkihub-Android/${BuildConfig.VERSION_NAME}" }
                    requestBuilder.addHeader(HttpHeaders.UserAgent, userAgent)
                }

                chain.proceed(requestBuilder.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (
                    contentTypeHeader != null &&
                    contentTypeHeader.contains(";") &&
                    contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(AIRequestInterceptor())
            .addInterceptor(HttpLoggingInterceptor().apply {
                redactHeader("Proxy-Authorization")
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .build()
        client.also { SearchService.init(it, get()) }
    }

    single<OkHttpClient>(named("codex")) {
        // Derived from the main client so it inherits SettingsProxySelector and timeouts,
        // without the request/HTTP logging interceptors.
        get<OkHttpClient>().newChildClient()
    }

    single {
        CodexAccountRepository(
            store = CodexCredentialStore(context = get(), json = get()),
            client = get(named("codex")),
            json = get(),
        )
    }

    single {
        CodexOAuthManager(
            context = get(),
            scope = get<AppScope>(),
            client = get(named("codex")),
            repository = get(),
        )
    }

    single<OkHttpClient>(named("devin")) {
        get<OkHttpClient>().newChildClient()
    }

    single {
        DevinChatApi(get(named("devin")))
    }

    single {
        DevinAccountRepository(
            store = DevinCredentialStore(context = get(), json = get()),
            client = get(named("devin")),
            chatApi = get(),
            json = get(),
        )
    }

    single {
        DevinOAuthManager(
            context = get(),
            scope = get<AppScope>(),
            client = get(named("devin")),
            repository = get(),
            json = get(),
        )
    }

    single<OkHttpClient>(named("antigravity")) {
        // 官方客户端只走 HTTP/1.1（CPA 同样禁用 h2）
        get<OkHttpClient>().newChildClient().newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    single {
        AntigravityChatApi(get(named("antigravity")))
    }

    single {
        AntigravityAccountRepository(
            store = AntigravityCredentialStore(context = get(), json = get()),
            api = get(),
        )
    }

    single {
        AntigravityOAuthManager(
            context = get(),
            scope = get<AppScope>(),
            api = get(),
            repository = get(),
        )
    }

    single {
        val codexRepository: CodexAccountRepository = get()
        val devinChatApi: DevinChatApi = get()
        val devinRepository: DevinAccountRepository = get()
        val settingsStore: me.rerere.rikkihub.data.datastore.SettingsStore = get()
        ProviderManager(
            client = get(),
            context = get(),
            ccDisguiseConfigProvider = { settingsStore.settingsFlow.value.ccDisguiseConfig },
        ).also { pm ->
            pm.registerProvider(
                "codex",
                CodexProvider(
                    client = get(named("codex")),
                    repository = codexRepository,
                    json = get(),
                    scope = get(),
                )
            )
            pm.registerProvider(
                "devin",
                DevinProvider(
                    chatApi = devinChatApi,
                    repository = devinRepository,
                )
            )
            pm.registerProvider(
                "antigravity",
                AntigravityProvider(
                    api = get(),
                    repository = get(),
                    scope = get(),
                )
            )
        }
    }

    single { BackupManager(context = get(), database = get(), settingsStore = get(), json = get()) }

    single {
        WebDavSync(
            backupManager = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<HttpClient> {
        HttpClient(OkHttp) {
            engine {
                preconfigured = get<OkHttpClient>().newChildClient()
            }
        }
    }

    single {
        S3Sync(
            backupManager = get(),
            context = get(),
            httpClient = get()
        )
    }
}

/** Shares the main client's proxy selector/authenticator, timeouts and connection pool. */
private fun OkHttpClient.newChildClient(): OkHttpClient =
    newBuilder()
        .apply {
            interceptors().removeAll { it is HttpLoggingInterceptor }
            networkInterceptors().removeAll { it is RequestLoggingInterceptor }
        }
        .build()

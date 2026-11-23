package me.rerere.rikkihub.di

import android.content.Context
import android.os.Environment
import me.rerere.rikkihub.data.book.BookReplacer
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.data.book.transfer.BookImporter
import me.rerere.rikkihub.data.character.imageBytesToPng
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.data.files.FileManagerService
import me.rerere.rikkihub.ui.pages.files.FileManagerPicks
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.files.SkillManager
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.FavoriteRepository
import me.rerere.rikkihub.data.repository.FolderRepository
import me.rerere.rikkihub.data.repository.CharacterRepository
import me.rerere.rikkihub.data.repository.TranslationHistoryRepository
import me.rerere.rikkihub.data.repository.FilesRepository
import me.rerere.rikkihub.data.repository.GenMediaRepository
import me.rerere.rikkihub.data.repository.MemoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.data.files.FileManagerWorkspace
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WorkspaceUrlOpener
import me.rerere.rikkihub.data.repository.SubagentPresetRepository
import me.rerere.workspace.LocalWorkspaceBackend
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBackend
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceDeviceDirectoryGuard
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceUrlHandoff
import org.koin.dsl.module
import java.io.File
import me.rerere.rikkihub.data.repository.MediaCreationRepository

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        TranslationHistoryRepository(get())
    }

    single {
        CharacterRepository(dao = get(), root = java.io.File(get<Context>().filesDir, FileFolders.CHARACTERS))
    }

    single {
        val settingsStore = get<me.rerere.rikkihub.data.datastore.SettingsStore>()
        me.rerere.rikkihub.data.character.CharacterService(
            repository = get(),
            readSettings = { settingsStore.settingsFlow.value },
            updateSettings = { transform -> settingsStore.update(transform) },
            toPng = ::imageBytesToPng,
        )
    }

    single {
        MediaCreationRepository(context = get(), database = get(), dao = get(), json = get())
    }

    single {
        val context: Context = get()
        WorkspaceUrlOpener(
            context = context,
            dir = File(context.noBackupFilesDir.canonicalFile, "workspace_open_url").apply { mkdirs() },
        )
    }

    single {
        val context: Context = get()
        val filesRoot = context.filesDir.canonicalFile
        WorkspaceManager(
            baseDir = File(filesRoot, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            ),
            // 同一份挂载表既用于 PRoot 的 -b 参数, 也用于文件工具的路径解析, 避免两处漂移
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(filesRoot, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                ),
                WorkspaceBindMount(
                    source = me.rerere.rikkihub.data.files.BuiltinSkills.workspaceMountSource(context),
                    target = "/builtin_skills",
                    required = true,
                ),
                WorkspaceBindMount(
                    source = File(filesRoot, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                // 子代理日志与 /tool_outputs 共用暴露开关，不新增 DB 字段。
                WorkspaceBindMount(
                    source = File(filesRoot, FileFolders.SUBAGENT_RUNS).apply { mkdirs() },
                    target = "/subagent_runs",
                ),
                WorkspaceBindMount(
                    source = File(filesRoot, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
                // xdg-open inside the rootfs drops links here; WorkspaceUrlOpener opens them
                WorkspaceBindMount(
                    source = get<WorkspaceUrlOpener>().dir,
                    target = WorkspaceUrlHandoff.GUEST_DIR,
                ),
            ),
        )
    }

    single<WorkspaceBackend> {
        val context: Context = get()
        val manager: WorkspaceManager = get()
        LocalWorkspaceBackend(
            manager = manager,
            installer = RootfsInstaller(manager),
            nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            deviceGuard = runCatching {
                WorkspaceDeviceDirectoryGuard(Environment.getExternalStorageDirectory())
            }.getOrNull(),
            unavailableDeviceNote = File(context.filesDir.canonicalFile, "workspace-device-unavailable.txt"),
        )
    }

    single {
        WorkspaceRepository(
            dao = get(), manager = get(), settingsStore = get(),
            runtimeGate = get(), sessionInvalidation = get(),
            deviceDirectoryService = get(),
        )
    }

    single {
        SubagentPresetRepository(database = get(), workspaceRepository = get())
    }

    single {
        FilesManager(get(), get(), get(),
            retentionDao = get<me.rerere.rikkihub.data.db.AppDatabase>().attachmentRetentionDao())
    }

    single {
        val manager = get<WorkspaceManager>()
        // The library lists the workspaces (folder per workspace = its files area); kept warm so the
        // service can read it on any thread without touching the database.
        val workspaces = get<WorkspaceRepository>().listFlow()
            .map { list -> list.map { FileManagerWorkspace(it.name, manager.filesDir(it.root), it.updatedAt) } }
            .stateIn(get<AppScope>(), SharingStarted.Eagerly, emptyList())
        FileManagerService.create(context = get(), filesManager = get(), workspaces = { workspaces.value })
    }

    single { FileManagerPicks() }

    single {
        BookStore(
            repository = get(),
            cacheDir = File(get<Context>().cacheDir, "book_index"),
        )
    }

    single { BookImporter(store = get()) }

    single {
        BookReplacer(store = get(), undoDir = File(get<Context>().noBackupFilesDir, "book_undo"))
    }

    single {
        SkillManager(get(), get())
    }
}

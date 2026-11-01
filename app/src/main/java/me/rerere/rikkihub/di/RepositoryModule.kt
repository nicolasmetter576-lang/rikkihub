package me.rerere.rikkihub.di

import android.content.Context
import android.os.Environment
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.files.SkillManager
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.FavoriteRepository
import me.rerere.rikkihub.data.repository.FolderRepository
import me.rerere.rikkihub.data.repository.TranslationHistoryRepository
import me.rerere.rikkihub.data.repository.FilesRepository
import me.rerere.rikkihub.data.repository.GenMediaRepository
import me.rerere.rikkihub.data.repository.MemoryRepository
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.repository.SubagentPresetRepository
import me.rerere.workspace.LocalWorkspaceBackend
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBackend
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceDeviceDirectoryGuard
import me.rerere.workspace.WorkspaceManager
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
        MediaCreationRepository(context = get(), database = get(), dao = get(), json = get())
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
        SkillManager(get(), get())
    }
}

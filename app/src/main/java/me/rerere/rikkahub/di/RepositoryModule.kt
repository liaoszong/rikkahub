package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.db.media.ConversationMediaReferenceBackfillProcessor
import me.rerere.rikkahub.fork.pale.request.RequestLedgerRepository
import me.rerere.rikkahub.fork.pale.request.ChatProviderStepCoordinator
import me.rerere.rikkahub.fork.pale.request.ChatRequestReconciler
import me.rerere.rikkahub.fork.pale.request.ToolExecutionLedgerCoordinator
import me.rerere.rikkahub.fork.pale.request.ToolRequestReconciler
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File
import kotlin.uuid.Uuid

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        GenMediaRepository(
            dao = get(),
            filesRepository = get(),
            mediaReferenceBackfillScheduler = get<ConversationMediaReferenceBackfillProcessor>(),
        )
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    // Room 29 request evidence has one transactional write authority. Consumers receive the
    // repository rather than a DAO so state, billing boundary, lease, and audit never drift.
    single {
        RequestLedgerRepository(database = get())
    }

    single {
        ChatProviderStepCoordinator(repository = get(), json = get())
    }

    single {
        ToolExecutionLedgerCoordinator(repository = get(), json = get())
    }

    single {
        val conversationRepository: ConversationRepository = get()
        ChatRequestReconciler(
            requestRepository = get(),
            coordinator = get(),
            loadDurableMessage = { conversationId, messageId ->
                runCatching {
                    conversationRepository.getConversationById(Uuid.parse(conversationId))
                }.getOrNull()?.messageNodes
                    ?.asSequence()
                    ?.flatMap { it.messages.asSequence() }
                    ?.singleOrNull { it.id.toString() == messageId }
            },
        )
    }

    single {
        val conversationRepository: ConversationRepository = get()
        ToolRequestReconciler(
            repository = get(),
            coordinator = get(),
            loadDurableMessage = { conversationId, messageId ->
                runCatching {
                    conversationRepository.getConversationById(Uuid.parse(conversationId))
                }.getOrNull()?.messageNodes
                    ?.asSequence()
                    ?.flatMap { it.messages.asSequence() }
                    ?.singleOrNull { it.id.toString() == messageId }
            },
        )
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            ),
            // 同一份挂载表既用于 PRoot 的 -b 参数, 也用于文件工具的路径解析, 避免两处漂移
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        WorkspaceRepository(get(), get(), get(), get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }
}

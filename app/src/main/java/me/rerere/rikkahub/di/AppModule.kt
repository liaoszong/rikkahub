package me.rerere.rikkahub.di

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.imggen.ImageGenerationGateway
import me.rerere.rikkahub.data.imggen.MediaAssetRecovery
import me.rerere.rikkahub.data.imggen.ProviderImageGenerationGateway
import me.rerere.rikkahub.data.imggen.ChatImageGenerationForegroundController
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskController
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskCoordinator
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskStore
import me.rerere.rikkahub.data.imggen.SharedPreferencesChatImageGenerationTaskStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.AndroidChatImageGenerationForegroundController
import me.rerere.rikkahub.service.AndroidChatGenerationForegroundController
import me.rerere.rikkahub.service.ChatGenerationForegroundController
import me.rerere.rikkahub.service.ChatGenerationForegroundRegistry
import me.rerere.rikkahub.service.ChatImageGenerationForegroundReadiness
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.AppAnalytics
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.createAppAnalytics
import me.rerere.rikkahub.utils.SoundEffectPlayer
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    }

    single {
        AppScope()
    }

    // Resolved explicitly by RikkaHubApp only after startup restore commits.
    single {
        UpdateChecker(
            context = get(),
            client = get(),
            appScope = get(),
        )
    }

    single<ImageGenerationGateway> {
        ProviderImageGenerationGateway(
            settingsStore = get(),
            providerManager = get(),
            credentialStore = get(),
        )
    }

    single<ChatImageGenerationTaskStore> {
        SharedPreferencesChatImageGenerationTaskStore(context = get(), json = get())
    }

    single {
        ChatImageGenerationForegroundReadiness()
    }

    single {
        ChatGenerationForegroundRegistry()
    }

    single<ChatGenerationForegroundController> {
        AndroidChatGenerationForegroundController(context = get(), registry = get())
    }

    single<ChatImageGenerationForegroundController> {
        AndroidChatImageGenerationForegroundController(context = get(), readiness = get())
    }

    single {
        ChatImageGenerationTaskCoordinator(store = get(), foregroundController = get())
    }

    single<ChatImageGenerationTaskController> {
        get<ChatImageGenerationTaskCoordinator>()
    }

    single {
        MediaAssetRecovery(
            context = get(),
            filesManager = get(),
            genMediaRepository = get(),
            chatTaskStore = get(),
        )
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single<AppAnalytics> {
        createAppAnalytics()
    }

    single {
        SoundEffectPlayer(get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费。RikkaHubApp 在启动恢复
    // 成功后立即解析此单例，既保证及时订阅，也避免它在 restored settings 提交前运行。
    single {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
            foregroundRegistry = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            folderRepository = get(),
            generationForegroundController = get(),
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}

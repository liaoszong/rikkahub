package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.IOException
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.pale.product.PrivacyPolicy
import kotlinx.serialization.Transient
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.credential.CredentialSettingsProjection
import me.rerere.rikkahub.data.credential.CredentialSettingsProjectionIssue
import me.rerere.rikkahub.data.credential.CredentialSettingsProjectionResult
import me.rerere.rikkahub.data.credential.CredentialSettingsProjectionStore
import me.rerere.rikkahub.data.credential.CredentialSettingsResolveResult
import me.rerere.rikkahub.data.credential.CredentialSettingsAddress
import me.rerere.rikkahub.data.credential.CredentialSettingsSealResult
import me.rerere.rikkahub.data.credential.CredentialAudienceRebindIntent
import me.rerere.rikkahub.data.credential.CredentialAudienceRebindCandidate
import me.rerere.rikkahub.data.credential.CredentialProjectionCommitter
import me.rerere.rikkahub.data.credential.CredentialRefId
import me.rerere.rikkahub.data.credential.CredentialVaultProjectionStore
import me.rerere.rikkahub.data.credential.CredentialReadiness
import me.rerere.rikkahub.data.credential.CredentialUnavailableReason
import me.rerere.rikkahub.data.credential.CredentialReadinessController
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.logSafeError
import me.rerere.rikkahub.utils.logSafeFailure
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.net.URI
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

internal class CredentialSettingsUnavailableException(
    val issue: CredentialSettingsProjectionIssue,
) : IllegalStateException("Credential settings unavailable at ${issue.jsonPath}: ${issue::class.simpleName}")

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration()
        )
    }
)

class SettingsStore internal constructor(
    context: Context,
    scope: AppScope,
    private val credentialStore: CredentialVaultProjectionStore,
) : KoinComponent {
    private val credentialReadinessController = CredentialReadinessController()
    val credentialReadiness: StateFlow<CredentialReadiness> = credentialReadinessController.state

    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val DEFAULT_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")
    }

    private val dataStore = context.settingsStore
    private val credentialProjection = CredentialSettingsProjection(credentialStore)
    private val credentialCommitter = CredentialProjectionCommitter(credentialStore)
    private val credentialTransactionMutex = Mutex()

    val settingsFlowRaw = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { preferences ->
            Settings(
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = preferences[FAST_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) },
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                suggestionModelId = preferences[SUGGESTION_MODEL]?.let { Uuid.parse(it) },
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providers = JsonInstant.decodeFromString(preferences[PROVIDERS] ?: "[]"),
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}"),
                searchServices = preferences[SEARCH_SERVICES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                asrProviders = preferences[ASR_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
                modeInjections = preferences[MODE_INJECTIONS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] ?: true,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
            )
        }
        .map(::toRuntimeSettings)
        .map {
            val providers = mergeDefaultProviders(it.providers)
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants,
                ttsProviders = ttsProviders,
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            val cleanedSettings = settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = assistant.quickMessageIds.filter { id ->
                            id in validQuickMessageIds
                        }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId
                    ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                    ?: asrProviders.firstOrNull()?.id,
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
            )
            val selectedImageModel = cleanedSettings.resolveImageGenerationModel()
            if (selectedImageModel == null) {
                cleanedSettings
            } else {
                cleanedSettings.copy(imageGenerationModelId = selectedImageModel.id)
            }
        }
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }
        .flowOn(Dispatchers.IO)

    private fun toRuntimeSettings(persisted: Settings): Settings =
        when (val result = credentialProjection.toRuntime(JsonInstant.encodeToJsonElement(persisted))) {
            is CredentialSettingsProjectionResult.Success -> JsonInstant.decodeFromJsonElement<Settings>(result.settings)
                .copy(
                    credentialReferences = result.bindings.associate { it.jsonPath to it.reference },
                    credentialRevisions = result.bindings.mapNotNull { binding ->
                        binding.revision?.let { binding.jsonPath to it }
                    }.toMap(),
                    credentialReferencesBySlot = result.bindings.associate {
                        it.address.slotId().value to it.reference
                    },
                )
            is CredentialSettingsProjectionResult.Failure -> throw CredentialSettingsUnavailableException(result.issue)
        }

    private fun toPersistedSettings(runtime: Settings): PersistedSettingsProjection =
        when (val result = credentialProjection.toPersisted(JsonInstant.encodeToJsonElement(runtime))) {
            is CredentialSettingsProjectionResult.Success -> PersistedSettingsProjection(
                settings = JsonInstant.decodeFromJsonElement(result.settings),
                bindings = result.bindings,
            )
            is CredentialSettingsProjectionResult.Failure -> throw CredentialSettingsUnavailableException(result.issue)
        }

    private data class PersistedSettingsProjection(
        val settings: Settings,
        val bindings: List<me.rerere.rikkahub.data.credential.CredentialSettingsBinding>,
    )

    val settingsFlow = settingsFlowRaw
        .retryWhen { cause, _ ->
            if (cause !is CredentialSettingsUnavailableException) return@retryWhen false
            recordCredentialFailure(cause.issue)
            credentialReadiness.first { it == CredentialReadiness.Ready }
            true
        }
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    /** Waits for the startup credential boundary and fails closed instead of dispatching a request. */
    suspend fun awaitCredentialReady() {
        credentialReadinessController.awaitReady()
    }

    fun requireCredentialReady() {
        credentialReadinessController.requireReady()
    }

    suspend fun update(settings: Settings) = credentialTransactionMutex.withLock {
        updateLocked(settings)
    }

    private suspend fun updateLocked(
        settings: Settings,
        projectedOverride: PersistedSettingsProjection? = null,
        previousReferencesBySlot: Map<String, String> = settings.credentialReferencesBySlot,
        recoverBeforeProjection: Boolean = true,
    ) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        // Recover a vault-first transaction that did not reach DataStore before accepting another
        // write. The transient map is the proof of the snapshot currently held by the caller.
        if (recoverBeforeProjection) {
            credentialStore.rollbackUncommittedBindings(previousReferencesBySlot)
        }
        val projected = projectedOverride ?: toPersistedSettings(settings)
        credentialCommitter.commit(
            previousReferencesBySlot = previousReferencesBySlot,
            projectedBindings = projected.bindings,
        ) {
            dataStore.edit { preferences ->
            val settings = projected.settings
            preferences[VERSION] = 4
            preferences[DYNAMIC_COLOR] = settings.dynamicColor
            preferences[THEME_ID] = settings.themeId
            preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
            preferences[DEVELOPER_MODE] = settings.developerMode
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)

            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
            preferences[SELECT_MODEL] = settings.chatModelId.toString()
            preferences[FAST_MODEL] = settings.fastModelId.toString()
            settings.titleModelId?.let {
                preferences[TITLE_MODEL] = it.toString()
            } ?: preferences.remove(TITLE_MODEL)
            preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
            preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
            settings.suggestionModelId?.let {
                preferences[SUGGESTION_MODEL] = it.toString()
            } ?: preferences.remove(SUGGESTION_MODEL)
            preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
            preferences[TITLE_PROMPT] = settings.titlePrompt
            preferences[TRANSLATION_PROMPT] = settings.translatePrompt
            preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
            preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
            preferences[OCR_MODEL] = settings.ocrModelId.toString()
            preferences[OCR_PROMPT] = settings.ocrPrompt
            preferences[COMPRESS_MODEL] = settings.compressModelId.toString()
            preferences[COMPRESS_PROMPT] = settings.compressPrompt

            preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)

            preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
            preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
            preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)

            preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
            preferences[SEARCH_SELECTED] = settings.searchServiceSelected.coerceIn(0, settings.searchServices.size - 1)

            preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
            preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
            preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
            preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
            settings.selectedTTSProviderId?.let {
                preferences[SELECTED_TTS_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_TTS_PROVIDER)
            preferences[DEFAULT_TTS_PLAYBACK_SPEED] = settings.defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f)
            preferences[ASR_PROVIDERS] = JsonInstant.encodeToString(settings.asrProviders)
            settings.selectedASRProviderId?.let {
                preferences[SELECTED_ASR_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_ASR_PROVIDER)
            preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
            preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
            preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
            preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
            preferences[WEB_SERVER_PORT] = settings.webServerPort
            preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
            preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
            preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
            preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
            preferences[LAUNCH_COUNT] = settings.launchCount
            preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
            }
        }
        // Never publish the caller's stale transient maps. Re-resolving the exact committed
        // projection makes RequestLedger and every runtime consumer observe the new binding proof.
        settingsFlow.value = toRuntimeSettings(projected.settings)
    }

    /**
     * Persists an endpoint edit only after an explicit user-authorized credential rebind.
     *
     * The normal [update] path intentionally fails closed when an existing stable slot changes
     * audience. UI code must ask for the secret again, preserve the old binding proof, and call
     * this method with the replacement value. A resolved old secret must never be used to build
     * [intent].
     */
    internal suspend fun updateWithCredentialAudienceRebind(
        settings: Settings,
        intent: CredentialAudienceRebindIntent,
    ) = updateWithCredentialAudienceRebinds(settings, listOf(intent))

    /**
     * Atomically rebinds every credential affected by one audience edit. A provider can own an API
     * key, auth headers and custom-body secrets at the same time, so accepting a partial set would
     * leave the settings snapshot impossible to persist. Only each intent's freshly entered
     * [CredentialAudienceRebindIntent.replacementSecret] is sealed; values embedded in [settings]
     * are deliberately ignored for the matching slots.
     */
    internal suspend fun updateWithCredentialAudienceRebinds(
        settings: Settings,
        intents: List<CredentialAudienceRebindIntent>,
    ) = credentialTransactionMutex.withLock {
        require(!settings.init) { "Cannot rebind credential for dummy settings" }
        val baseline = settingsFlow.value
        require(!baseline.init) { "Credential settings are not ready" }
        credentialStore.rollbackUncommittedBindings(baseline.credentialReferencesBySlot)
        val candidates = credentialAudienceRebindCandidates(baseline, settings)
        val candidatesByAddress = candidates.associateBy { it.address }
        val intentsByAddress = intents.associateBy { it.address }
        require(intentsByAddress.size == intents.size) { "Duplicate credential audience rebind intent" }
        require(candidatesByAddress.keys == intentsByAddress.keys) {
            "Every credential affected by the audience edit must be explicitly rebound"
        }
        candidates.forEach { candidate ->
            val intent = intentsByAddress.getValue(candidate.address)
            require(intent.expectedReference == candidate.expectedReference) { "Stale credential reference proof" }
            require(intent.expectedRevision == candidate.expectedRevision) { "Stale credential revision proof" }
        }

        val consumed = mutableSetOf<CredentialSettingsAddress>()
        val transactionProjection = CredentialSettingsProjection(object : CredentialSettingsProjectionStore {
            override fun seal(
                address: CredentialSettingsAddress,
                secret: JsonElement,
            ): CredentialSettingsSealResult {
                val intent = intentsByAddress[address] ?: return credentialStore.seal(address, secret)
                check(consumed.add(address)) { "Credential slot appeared more than once in settings projection" }
                return credentialStore.rebindAudience(
                    address = address,
                    expectedReference = intent.expectedReference,
                    expectedRevision = intent.expectedRevision,
                    replacementSecret = intent.replacementSecret,
                )
            }

            override fun resolve(
                reference: String,
                address: me.rerere.rikkahub.data.credential.CredentialSettingsAddress,
            ): CredentialSettingsResolveResult = credentialStore.resolve(reference, address)
        })

        val projected = try {
            val value = when (val result = transactionProjection.toPersisted(JsonInstant.encodeToJsonElement(settings))) {
                is CredentialSettingsProjectionResult.Success -> PersistedSettingsProjection(
                    settings = JsonInstant.decodeFromJsonElement(result.settings),
                    bindings = result.bindings,
                )
                is CredentialSettingsProjectionResult.Failure -> throw CredentialSettingsUnavailableException(result.issue)
            }
            check(consumed == intentsByAddress.keys) { "Credential rebind target was not present in settings" }
            value
        } catch (failure: Throwable) {
            runCatching { credentialStore.rollbackUncommittedBindings(baseline.credentialReferencesBySlot) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
        updateLocked(
            settings = settings,
            projectedOverride = projected,
            previousReferencesBySlot = baseline.credentialReferencesBySlot,
            recoverBeforeProjection = false,
        )
    }

    /**
     * Computes changed-audience slots through the canonical projection itself. UI receives only
     * reference/revision proofs and paths; no resolved secret is placed in a candidate.
     */
    internal fun credentialAudienceRebindCandidates(
        oldSettings: Settings,
        newSettings: Settings,
    ): List<CredentialAudienceRebindCandidate> {
        val oldReferences = oldSettings.credentialReferencesBySlot
        val probe = CredentialSettingsProjection(object : CredentialSettingsProjectionStore {
            override fun seal(
                address: CredentialSettingsAddress,
                secret: JsonElement,
            ): CredentialSettingsSealResult {
                val oldReference = oldReferences[address.slotId().value]
                    ?: return CredentialSettingsSealResult.Stored(CredentialRefId.new().referenceString(), 1)
                val proof = credentialStore.inspectBinding(oldReference)
                    ?: return CredentialSettingsSealResult.Failed("Previous credential proof is unavailable")
                return CredentialSettingsSealResult.Stored(oldReference, proof.revision)
            }

            override fun resolve(
                reference: String,
                address: me.rerere.rikkahub.data.credential.CredentialSettingsAddress,
            ): CredentialSettingsResolveResult = credentialStore.resolve(reference, address)
        })
        val result = when (val projected = probe.toPersisted(JsonInstant.encodeToJsonElement(newSettings))) {
            is CredentialSettingsProjectionResult.Success -> projected
            is CredentialSettingsProjectionResult.Failure -> throw CredentialSettingsUnavailableException(projected.issue)
        }
        return result.bindings.mapNotNull { binding ->
            val oldReference = oldReferences[binding.address.slotId().value] ?: return@mapNotNull null
            val proof = credentialStore.inspectBinding(oldReference)
                ?: throw IllegalStateException("Previous credential proof is unavailable")
            if (proof.audience == binding.address.audience) return@mapNotNull null
            CredentialAudienceRebindCandidate(
                address = binding.address,
                expectedReference = oldReference,
                expectedRevision = proof.revision,
                jsonPath = binding.jsonPath,
            )
        }
    }

    /**
     * Startup barrier for Preferences v4. It is intentionally re-entrant: after a process death,
     * already-written envelopes are reused and the journal advances only after DataStore commits.
     */
    suspend fun migrateCredentialVault(): CredentialReadiness = withContext(Dispatchers.IO) {
        credentialTransactionMutex.withLock {
        credentialReadinessController.begin()
        try {
            val runtime = settingsFlowRaw.first()
            // Always reconcile the vault-first crash window before projecting. Even VERSION >= 4
            // can contain plaintext after an interrupted migration or newly classified secret, so
            // the entire secret-free projection is atomically rewritten and journal-cleaned.
            credentialStore.rollbackUncommittedBindings(runtime.credentialReferencesBySlot)
            updateLocked(runtime)
            CredentialReadiness.Ready.also { credentialReadinessController.ready() }
        } catch (failure: CredentialSettingsUnavailableException) {
            recordCredentialFailure(failure.issue)
            credentialReadiness.value
        } catch (failure: Throwable) {
            logSafeError(TAG, "credential", "migrate_vault", failure)
            CredentialReadiness.Unavailable(
                reason = CredentialUnavailableReason.MIGRATION_FAILED,
                retryable = true,
            ).also { credentialReadinessController.unavailable(it.reason, it.retryable) }
        }
        }
    }

    private fun recordCredentialFailure(issue: CredentialSettingsProjectionIssue) {
        val unavailable = when (issue) {
            is CredentialSettingsProjectionIssue.Locked -> {
                val reason = when (issue.reason) {
                    "DEVICE_LOCKED" -> CredentialUnavailableReason.DEVICE_LOCKED
                    "KEY_INVALIDATED" -> CredentialUnavailableReason.KEY_INVALIDATED
                    else -> CredentialUnavailableReason.KEY_UNAVAILABLE
                }
                CredentialReadiness.Unavailable(reason, retryable = reason != CredentialUnavailableReason.KEY_INVALIDATED)
            }
            is CredentialSettingsProjectionIssue.Missing -> CredentialReadiness.Unavailable(
                CredentialUnavailableReason.MISSING_ENTRY,
                retryable = false,
            )
            is CredentialSettingsProjectionIssue.Corrupt,
            is CredentialSettingsProjectionIssue.InvalidReference -> CredentialReadiness.Unavailable(
                CredentialUnavailableReason.CORRUPT_ENTRY,
                retryable = false,
            )
            is CredentialSettingsProjectionIssue.StoreFailed,
            is CredentialSettingsProjectionIssue.UnstableOwner -> CredentialReadiness.Unavailable(
                CredentialUnavailableReason.MIGRATION_FAILED,
                retryable = true,
            )
        }
        credentialReadinessController.unavailable(unavailable.reason, unavailable.retryable)
        logSafeFailure(TAG, "credential", "load_settings")
    }

    suspend fun update(fn: (Settings) -> Settings) {
        update(fn(settingsFlow.value))
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        dataStore.edit { preferences ->
            preferences[SELECT_ASSISTANT] = assistantId.toString()
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }
}

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    @Transient
    val credentialReferences: Map<String, String> = emptyMap(),
    @Transient
    val credentialRevisions: Map<String, Long> = emptyMap(),
    @Transient
    val credentialReferencesBySlot: Map<String, String> = emptyMap(),
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val agentPrivacyPolicy: PrivacyPolicy = PrivacyPolicy(),
    val displaySetting: DisplaySetting = DisplaySetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid? = null,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val enableSuggestion: Boolean = true,
    val suggestionModelId: Uuid? = null,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = true,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val showUpdates: Boolean = true,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val drawerCreativeToolsExpanded: Boolean = true,
    val drawerChatsExpanded: Boolean = true,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

/**
 * Resolve the model used by short background text tasks such as title generation.
 * The built-in `auto` route is useful for interactive chat, but is not a reliable
 * background fallback for users who only configured a third-party provider.
 */
fun Settings.resolveBackgroundTextModel(preferredId: Uuid?, fallbackId: Uuid?): Model? {
    val explicitCandidates = listOfNotNull(
        findModelById(preferredId),
        findModelById(fallbackId),
        getCurrentChatModel(),
    )
    val anchorModel = explicitCandidates.firstOrNull { it.modelId != "auto" }
    val anchorProvider = anchorModel?.findProvider(providers)
    val eligibleProviders = when {
        anchorProvider != null -> listOf(anchorProvider).filter { provider ->
            provider.isReadyForRequests(anchorModel)
        }
        else -> providers.filter { provider ->
            provider.models.any { model ->
                model.type == ModelType.CHAT && model.modelId != "auto" && provider.isReadyForRequests(model)
            }
        }.takeIf { it.size == 1 }.orEmpty()
    }
    if (eligibleProviders.isEmpty()) return null

    explicitCandidates.firstOrNull { model ->
        model.type == ModelType.CHAT &&
            model.modelId != "auto" &&
            model.findProvider(providers)?.let { provider ->
                provider in eligibleProviders && provider.isReadyForRequests(model)
            } == true
    }?.let { return it }

    return eligibleProviders.asSequence()
        .flatMap { provider -> provider.models.asSequence().map { provider to it } }
        .filter { (provider, model) ->
            model.type == ModelType.CHAT && model.modelId != "auto" && provider.isReadyForRequests(model)
        }
        .sortedBy { (_, model) ->
            when {
                model.modelId.contains("mini", ignoreCase = true) -> 0
                model.modelId.contains("luna", ignoreCase = true) -> 1
                model.modelId.contains("nano", ignoreCase = true) -> 2
                else -> 3
            }
        }
        .map { it.second }
        .firstOrNull()
}

/** Keep an explicit valid choice; otherwise choose the newest available image model. */
fun Settings.resolveImageGenerationModel(): Model? {
    findModelById(imageGenerationModelId)?.takeIf { model ->
        model.type == ModelType.IMAGE &&
            model.findProvider(providers)?.isReadyForRequests(model) == true
    }?.let { return it }

    return providers.asSequence()
        .flatMap { provider -> provider.models.asSequence().map { provider to it } }
        .filter { (provider, model) -> model.type == ModelType.IMAGE && provider.isReadyForRequests(model) }
        .map { it.second }
        .maxWithOrNull(
            compareBy<Model> { imageModelVersionScore(it.modelId) }
                .thenBy { if (it.modelId.contains("mini", ignoreCase = true)) 0 else 1 }
        )
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId?.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal enum class ProviderCredentialSource {
    API_KEY,
    MODEL_HEADER,
    SERVICE_ACCOUNT,
    LOCAL_NO_AUTH,
}

internal enum class ProviderReadinessIssue {
    DISABLED,
    MISSING_CREDENTIALS,
}

internal data class ProviderReadiness(
    val ready: Boolean,
    val credentialSource: ProviderCredentialSource? = null,
    val issue: ProviderReadinessIssue? = null,
)

internal fun ProviderSetting.requestReadiness(model: Model? = null): ProviderReadiness {
    if (!enabled) return ProviderReadiness(ready = false, issue = ProviderReadinessIssue.DISABLED)
    val supportedAuthHeaders = when (this) {
        is ProviderSetting.OpenAI -> setOf("authorization", "x-api-key")
        is ProviderSetting.Google -> setOf("authorization", "x-goog-api-key")
        is ProviderSetting.Claude -> setOf("authorization", "x-api-key")
    }
    val hasModelHeader = (model?.customHeaders ?: models.flatMap(Model::customHeaders))
        .any { it.value.isNotBlank() && it.name.trim().lowercase() in supportedAuthHeaders }
    if (hasModelHeader) {
        return ProviderReadiness(ready = true, credentialSource = ProviderCredentialSource.MODEL_HEADER)
    }
    return when (this) {
        is ProviderSetting.OpenAI -> when {
            apiKey.isNotBlank() -> ProviderReadiness(true, ProviderCredentialSource.API_KEY)
            baseUrl.isLocalEndpoint() -> ProviderReadiness(true, ProviderCredentialSource.LOCAL_NO_AUTH)
            else -> ProviderReadiness(false, issue = ProviderReadinessIssue.MISSING_CREDENTIALS)
        }
        is ProviderSetting.Google -> when {
            vertexAI && useServiceAccount -> {
                val complete = privateKey.isNotBlank() &&
                    serviceAccountEmail.isNotBlank() &&
                    projectId.isNotBlank() &&
                    location.isNotBlank()
                if (complete) {
                    ProviderReadiness(true, ProviderCredentialSource.SERVICE_ACCOUNT)
                } else {
                    ProviderReadiness(false, issue = ProviderReadinessIssue.MISSING_CREDENTIALS)
                }
            }
            apiKey.isNotBlank() -> ProviderReadiness(true, ProviderCredentialSource.API_KEY)
            baseUrl.isLocalEndpoint() -> ProviderReadiness(true, ProviderCredentialSource.LOCAL_NO_AUTH)
            else -> ProviderReadiness(false, issue = ProviderReadinessIssue.MISSING_CREDENTIALS)
        }
        is ProviderSetting.Claude -> when {
            apiKey.isNotBlank() -> ProviderReadiness(true, ProviderCredentialSource.API_KEY)
            baseUrl.isLocalEndpoint() -> ProviderReadiness(true, ProviderCredentialSource.LOCAL_NO_AUTH)
            else -> ProviderReadiness(false, issue = ProviderReadinessIssue.MISSING_CREDENTIALS)
        }
    }
}

private fun ProviderSetting.isReadyForRequests(model: Model? = null): Boolean = requestReadiness(model).ready

private fun String.isLocalEndpoint(): Boolean {
    val endpoint = runCatching { URI(trim()) }.getOrNull() ?: return false
    if (endpoint.scheme?.lowercase() !in setOf("http", "https")) return false
    val host = endpoint.host?.lowercase() ?: return false
    if (host == "localhost" || host == "::1" || host == "[::1]" || host == "0:0:0:0:0:0:0:1") return true
    if (host == "10.0.2.2" || host == "10.0.3.2" || host.startsWith("127.")) return true
    if (host.startsWith("10.") || host.startsWith("192.168.")) return true
    val secondOctet = host.split('.').getOrNull(1)?.toIntOrNull()
    return host.startsWith("172.") && secondOctet in 16..31
}

private fun imageModelVersionScore(modelId: String): Double {
    val normalized = modelId.lowercase()
    val version = Regex("""(?:gpt[-_ ]?image|image)[-_ ]?(\d+(?:\.\d+)?)""")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.toDoubleOrNull()
        ?: 0.0
    return version - if (normalized.contains("mini")) 0.001 else 0.0
}

private fun ProviderSetting.isManagedPalenikProvider(defaultProvider: ProviderSetting): Boolean =
    defaultProvider.id == PALENIK_PROVIDER_ID &&
        this is ProviderSetting.OpenAI &&
        managedBy == PALENIK_MANAGED_BY

internal fun mergeDefaultProviders(storedProviders: List<ProviderSetting>): List<ProviderSetting> {
    val initialProviders = storedProviders.ifEmpty { DEFAULT_PROVIDERS }
    val palenikCandidates = initialProviders.filter { provider ->
        provider.id == PALENIK_PROVIDER_ID ||
            (provider is ProviderSetting.OpenAI && provider.managedBy == PALENIK_MANAGED_BY)
    }
    val consolidatedPalenik = palenikCandidates
        .firstOrNull { it.id == PALENIK_PROVIDER_ID }
        ?.let { canonical ->
            palenikCandidates.filterNot { it === canonical }.fold(canonical, ::mergePalenikUserState)
        }
        ?: palenikCandidates.firstOrNull()?.let { first ->
            palenikCandidates.drop(1).fold(first, ::mergePalenikUserState)
        }
    var emittedPalenik = false
    val providers = initialProviders.mapNotNull { provider ->
        if (provider !in palenikCandidates) return@mapNotNull provider
        if (emittedPalenik) return@mapNotNull null
        emittedPalenik = true
        consolidatedPalenik
    }.toMutableList()
    DEFAULT_PROVIDERS.forEach { defaultProvider ->
        val existingIndex = providers.indexOfFirst { provider ->
            provider.id == defaultProvider.id || provider.isManagedPalenikProvider(defaultProvider)
        }
        if (existingIndex < 0) {
            providers.add(defaultProvider.copyProvider())
        } else if (defaultProvider.id == PALENIK_PROVIDER_ID) {
            providers[existingIndex] = mergePalenikProvider(
                existing = providers[existingIndex],
                defaults = defaultProvider,
            )
        }
    }
    return providers.map { provider ->
        val defaultProvider = DEFAULT_PROVIDERS.find { default ->
            default.id == provider.id || provider.isManagedPalenikProvider(default)
        }
        if (defaultProvider == null) {
            provider
        } else {
            provider.copyProvider(
                builtIn = defaultProvider.builtIn,
                description = defaultProvider.description,
                shortDescription = defaultProvider.shortDescription,
            )
        }
    }
}

private fun mergePalenikUserState(primary: ProviderSetting, duplicate: ProviderSetting): ProviderSetting {
    if (primary !is ProviderSetting.OpenAI || duplicate !is ProviderSetting.OpenAI) return primary
    val existingModelIds = primary.models.map { it.modelId.lowercase() }.toSet()
    return primary.copy(
        apiKey = primary.apiKey.ifBlank { duplicate.apiKey },
        models = primary.models + duplicate.models.filter { it.modelId.lowercase() !in existingModelIds },
        managedBy = PALENIK_MANAGED_BY,
    )
}

private fun mergePalenikProvider(
    existing: ProviderSetting,
    defaults: ProviderSetting,
): ProviderSetting {
    if (existing !is ProviderSetting.OpenAI || defaults !is ProviderSetting.OpenAI) return existing
    val defaultIds = defaults.models.map { it.modelId.lowercase() }.toSet()
    val mergedModels = defaults.models.map { defaultModel ->
        existing.models.firstOrNull { it.modelId.equals(defaultModel.modelId, ignoreCase = true) }
            ?.let { existingModel ->
                existingModel.copy(
                    displayName = existingModel.displayName.ifBlank { defaultModel.displayName },
                    type = defaultModel.type,
                    inputModalities = defaultModel.inputModalities,
                    outputModalities = defaultModel.outputModalities,
                    abilities = defaultModel.abilities,
                )
            }
            ?: defaultModel
    } + existing.models.filter { it.modelId.lowercase() !in defaultIds }

    return existing.copy(
        name = defaults.name,
        models = mergedModels,
        managedBy = PALENIK_MANAGED_BY,
        contextWindowTokensCap = defaults.contextWindowTokensCap,
        builtIn = true,
        description = defaults.description,
        shortDescription = defaults.shortDescription,
    )
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Date: {{cur_date}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent()
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)

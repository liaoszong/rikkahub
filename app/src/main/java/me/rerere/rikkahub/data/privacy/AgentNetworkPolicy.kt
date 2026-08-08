package me.rerere.rikkahub.data.privacy

import java.net.URI
import java.util.Locale
import me.rerere.ai.provider.ProviderSetting
import me.rerere.pale.product.PrivacyPolicy

object AgentNetworkPolicy {
    fun requireProviderAllowed(provider: ProviderSetting, policy: PrivacyPolicy) {
        if (policy.networkEnabled && !policy.localOnly) return
        val baseUrl = when (provider) {
            is ProviderSetting.OpenAI -> provider.baseUrl
            is ProviderSetting.Google -> provider.baseUrl
            is ProviderSetting.Claude -> provider.baseUrl
        }
        val host = runCatching { URI(baseUrl).host?.lowercase(Locale.ROOT) }.getOrNull()
        val isLocal = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (!policy.localOnly || !isLocal) {
            throw IllegalStateException("Agent network policy blocks this provider endpoint")
        }
    }
}

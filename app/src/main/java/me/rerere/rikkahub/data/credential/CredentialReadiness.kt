package me.rerere.rikkahub.data.credential

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Process-wide credential boundary state.
 *
 * [Initializing] and [Unavailable] are fail-closed states: code that can cross a network boundary
 * must call SettingsStore.awaitCredentialReady() before constructing or dispatching a request.
 * No secret, reference id, endpoint, or raw Keystore exception is exposed through this model.
 */
sealed interface CredentialReadiness {
    data object Initializing : CredentialReadiness
    data object Ready : CredentialReadiness

    data class Unavailable(
        val reason: CredentialUnavailableReason,
        val retryable: Boolean,
    ) : CredentialReadiness
}

enum class CredentialUnavailableReason {
    DEVICE_LOCKED,
    KEY_INVALIDATED,
    KEY_UNAVAILABLE,
    MISSING_ENTRY,
    CORRUPT_ENTRY,
    MIGRATION_FAILED,
}

class CredentialNetworkUnavailableException(
    val readiness: CredentialReadiness,
) : IllegalStateException(
    when (readiness) {
        CredentialReadiness.Initializing -> "Credentials are still initializing"
        CredentialReadiness.Ready -> "Credential network gate was used incorrectly"
        is CredentialReadiness.Unavailable -> "Credentials are unavailable: ${readiness.reason}"
    },
)

/** Small testable state machine shared by startup and every outbound network facade. */
internal class CredentialReadinessController {
    private val mutableState = MutableStateFlow<CredentialReadiness>(CredentialReadiness.Initializing)
    val state: StateFlow<CredentialReadiness> = mutableState.asStateFlow()

    fun begin() {
        mutableState.value = CredentialReadiness.Initializing
    }

    fun ready() {
        mutableState.value = CredentialReadiness.Ready
    }

    fun unavailable(reason: CredentialUnavailableReason, retryable: Boolean) {
        mutableState.value = CredentialReadiness.Unavailable(reason, retryable)
    }

    suspend fun awaitReady() {
        when (val current = state.first { it != CredentialReadiness.Initializing }) {
            CredentialReadiness.Ready -> Unit
            else -> throw CredentialNetworkUnavailableException(current)
        }
    }

    fun requireReady() {
        val current = state.value
        if (current != CredentialReadiness.Ready) throw CredentialNetworkUnavailableException(current)
    }
}

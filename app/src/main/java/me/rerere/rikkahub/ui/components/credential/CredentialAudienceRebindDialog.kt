package me.rerere.rikkahub.ui.components.credential

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.credential.CredentialAudienceRebindCandidate
import me.rerere.rikkahub.data.credential.CredentialAudienceRebindIntent
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.compose.koinInject

/**
 * One field whose credential audience changes with an edited endpoint.
 *
 * [key] is an opaque UI correlation key. The dialog deliberately receives no previous value:
 * copying a resolved secret into the new endpoint would defeat the audience boundary.
 */
internal data class CredentialReentryField(
    val key: String,
    val label: String,
)

/**
 * Requires a fresh value for every credential affected by an endpoint/audience edit.
 * Values live only in Compose state until the caller completes the vault transaction.
 */
@Composable
internal fun CredentialAudienceRebindDialog(
    fields: List<CredentialReentryField>,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit,
) {
    val replacements = remember(fields) {
        mutableStateMapOf<String, String>().apply {
            fields.forEach { put(it.key, "") }
        }
    }
    val complete = fields.isNotEmpty() && fields.all { replacements[it.key].orEmpty().isNotBlank() }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("重新输入凭据") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("连接地址已改变。为防止把旧服务器的密钥自动发送给新服务器，请重新输入以下凭据。")
                fields.forEach { field ->
                    OutlinedTextField(
                        value = replacements[field.key].orEmpty(),
                        onValueChange = { replacements[field.key] = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(field.label) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !saving,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text("取消")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(replacements.toMap()) },
                enabled = complete && !saving,
            ) {
                Text(if (saving) "保存中…" else "确认并保存")
            }
        },
    )
}

private data class PendingCredentialAudienceRebind(
    val settings: Settings,
    val candidates: List<CredentialAudienceRebindCandidate>,
)

/**
 * Returns a settings-save action that detects endpoint audience changes through the canonical
 * projection. Ordinary edits are persisted directly; an audience edit pauses and renders the
 * fresh-credential dialog before the complete batch is committed.
 */
@Composable
internal fun rememberCredentialAwareSettingsSave(
    onSaved: () -> Unit = {},
    onFailure: (Throwable) -> Unit = {},
): (oldSettings: Settings, newSettings: Settings) -> Unit {
    val settingsStore = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    val currentOnSaved by rememberUpdatedState(onSaved)
    val currentOnFailure by rememberUpdatedState(onFailure)
    var pending by remember { mutableStateOf<PendingCredentialAudienceRebind?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val active = pending
    if (active != null) {
        CredentialAudienceRebindDialog(
            fields = active.candidates.mapIndexed { index, candidate ->
                CredentialReentryField(
                    key = candidate.address.slotId().value,
                    label = credentialFieldLabel(candidate.jsonPath) +
                        if (active.candidates.count { it.jsonPath == candidate.jsonPath } > 1) " ${index + 1}" else "",
                )
            },
            saving = saving,
            error = error,
            onDismiss = {
                pending = null
                error = null
            },
            onConfirm = { replacements ->
                scope.launch {
                    saving = true
                    error = null
                    runCatching {
                        val intents = active.candidates.map { candidate ->
                            CredentialAudienceRebindIntent(
                                address = candidate.address,
                                expectedReference = candidate.expectedReference,
                                expectedRevision = candidate.expectedRevision,
                                replacementSecret = replacementJson(
                                    value = requireNotNull(replacements[candidate.address.slotId().value]) {
                                        "Missing replacement for ${candidate.jsonPath}"
                                    },
                                    kind = candidate.address.kind,
                                ),
                            )
                        }
                        settingsStore.updateWithCredentialAudienceRebinds(active.settings, intents)
                    }.onSuccess {
                        pending = null
                        currentOnSaved()
                    }.onFailure { failure ->
                        error = failure.message ?: "凭据保存失败"
                        currentOnFailure(failure)
                    }
                    saving = false
                }
            },
        )
    }

    return { oldSettings, newSettings ->
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    settingsStore.credentialAudienceRebindCandidates(oldSettings, newSettings)
                }
            }.onSuccess { candidates ->
                if (candidates.isEmpty()) {
                    runCatching { settingsStore.update(newSettings) }
                        .onSuccess { currentOnSaved() }
                        .onFailure(currentOnFailure)
                } else {
                    error = null
                    pending = PendingCredentialAudienceRebind(newSettings, candidates)
                }
            }.onFailure(currentOnFailure)
        }
    }
}

private fun replacementJson(value: String, kind: String) =
    if (kind == "body") {
        runCatching { Json.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
    } else {
        JsonPrimitive(value)
    }

internal fun credentialFieldLabel(jsonPath: String): String = when (
    jsonPath.substringAfterLast('.').lowercase()
) {
    "apikey" -> "API Key"
    "password" -> "密码"
    "username" -> "用户名"
    "accesskeyid" -> "Access Key ID"
    "secretaccesskey" -> "Secret Access Key"
    "accesstoken" -> "Access Token"
    "refreshtoken" -> "Refresh Token"
    "clientsecret" -> "Client Secret"
    "privatekey" -> "Private Key"
    "value", "second" -> "鉴权值（${jsonPath.substringBeforeLast('.').substringAfterLast('.')}）"
    else -> jsonPath.substringAfterLast('.')
}

package me.rerere.pale.media

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.util.UUID

@Serializable
enum class MediaKind {
    @SerialName("image") IMAGE,
    @SerialName("audio") AUDIO,
    @SerialName("video") VIDEO,
    @SerialName("document") DOCUMENT,
}

@Serializable
enum class MediaAssetLifecycle {
    @SerialName("reserved") RESERVED,
    @SerialName("active") ACTIVE,
    @SerialName("delete_pending") DELETE_PENDING,
    @SerialName("deleted") DELETED,
}

@Serializable
enum class MediaBlobState {
    @SerialName("staging") STAGING,
    @SerialName("available") AVAILABLE,
    @SerialName("missing") MISSING,
    @SerialName("corrupt") CORRUPT,
    @SerialName("quarantined") QUARANTINED,
    @SerialName("remote_only") REMOTE_ONLY,
}

@Serializable
enum class MediaReplicaKind {
    @SerialName("local_managed") LOCAL_MANAGED,
    @SerialName("external_uri") EXTERNAL_URI,
    @SerialName("remote_sync") REMOTE_SYNC,
    @SerialName("cache") CACHE,
}

@Serializable
enum class MediaBlobRole {
    @SerialName("original") ORIGINAL,
    @SerialName("preview") PREVIEW,
    @SerialName("thumbnail") THUMBNAIL,
    @SerialName("transcode") TRANSCODE,
}

@Serializable
enum class MediaRelationKind {
    @SerialName("edit_of") EDIT_OF,
    @SerialName("derived_from") DERIVED_FROM,
    @SerialName("reference_input") REFERENCE_INPUT,
}

@Serializable
enum class PrivacyScope {
    @SerialName("private") PRIVATE,
    @SerialName("sync_allowed") SYNC_ALLOWED,
    @SerialName("share_allowed") SHARE_ALLOWED,
}

@Serializable
enum class RetentionPolicy {
    @SerialName("conversation") CONVERSATION,
    @SerialName("library") LIBRARY,
    @SerialName("temporary") TEMPORARY,
}

/**
 * Stable identifiers cross Room rows, message payloads and future sync envelopes.
 *
 * New logical identities are random UUIDs. Identities derived from an already stable
 * owner are name-based UUIDs so a crash/replay cannot create a second relation,
 * replica or reference. Content-addressed blob ids are the one deliberate exception.
 */
object MediaStableIds {
    private val stableIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    fun newId(): String = UUID.randomUUID().toString()

    fun requireValid(value: String, label: String): String {
        require(stableIdPattern.matches(value)) {
            "$label must be 1..160 stable-id characters"
        }
        return value
    }

    fun blobIdForSha256(sha256: String?): String? {
        val normalized = sha256?.trim()?.lowercase() ?: return null
        return normalized.takeIf(sha256Pattern::matches)?.let { "sha256:$it" }
    }

    fun derived(namespace: String, vararg stableParts: String): String {
        requireValid(namespace, "Stable id namespace")
        require(stableParts.isNotEmpty()) { "At least one stable id part is required" }
        stableParts.forEachIndexed { index, part -> requireValid(part, "Stable id part $index") }
        return UUID.nameUUIDFromBytes(
            buildString {
                append(namespace)
                stableParts.forEach { part ->
                    append('\u0000')
                    append(part)
                }
            }.toByteArray(StandardCharsets.UTF_8),
        ).toString()
    }
}

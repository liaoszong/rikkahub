package me.rerere.pale.media

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

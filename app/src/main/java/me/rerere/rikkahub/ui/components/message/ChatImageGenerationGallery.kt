package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.imggen.ChatImageGenerationSlot
import me.rerere.rikkahub.data.imggen.ChatImageGenerationState
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskController
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskPhase
import me.rerere.rikkahub.data.imggen.ChatImageGenerationTaskRecord
import me.rerere.rikkahub.data.imggen.ChatImageSlotStatus
import me.rerere.rikkahub.data.imggen.ImageGenerationFailureKind
import me.rerere.rikkahub.data.imggen.withFallbackImages
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.modifier.shimmer
import org.koin.compose.koinInject

@Composable
fun ChatImageGenerationGallery(
    toolCallId: String,
    state: ChatImageGenerationState?,
    fallbackImages: List<UIMessagePart.Image> = emptyList(),
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val taskController = koinInject<ChatImageGenerationTaskController>()
    val durableTasks by taskController.tasks.collectAsStateWithLifecycle()
    val recoveredState = remember(toolCallId, state, fallbackImages) {
        state.withFallbackImages(toolCallId, fallbackImages)
    }
    val legacyFallback = state == null && recoveredState != null
    val durableTask = durableTasks[toolCallId]
        ?: recoveredState?.requestId?.let(durableTasks::get)
    val resolvedState = remember(recoveredState, durableTask) {
        recoveredState?.reconcileTerminalTask(durableTask)
    }
    val resolvedActive = active && (durableTask?.isActive ?: true)
    var collapsed by rememberSaveable(toolCallId) { mutableStateOf(false) }
    var selectedSlotIndex by rememberSaveable(toolCallId) { mutableIntStateOf(0) }
    var now by rememberSaveable(toolCallId) { mutableLongStateOf(System.currentTimeMillis()) }
    var failedImageUrls by remember(toolCallId) { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(resolvedActive, resolvedState?.finishedAtEpochMillis) {
        while (resolvedActive && resolvedState?.finishedAtEpochMillis == null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val succeeded = resolvedState?.slots.orEmpty().filter {
        it.status == ChatImageSlotStatus.SUCCEEDED && !it.imageUrl.isNullOrBlank()
    }
    val availableSucceeded = succeeded.filter { it.imageUrl !in failedImageUrls }
    LaunchedEffect(availableSucceeded.map { it.index }) {
        if (availableSucceeded.isNotEmpty() && availableSucceeded.none { it.index == selectedSlotIndex }) {
            selectedSlotIndex = availableSucceeded.first().index
        }
    }

    val elapsedMillis = resolvedState?.let {
        (it.finishedAtEpochMillis ?: now) - it.startedAtEpochMillis
    }?.coerceAtLeast(0L) ?: 0L

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = galleryStatusText(
                    state = resolvedState,
                    active = resolvedActive,
                    elapsedMillis = elapsedMillis,
                    legacyFallback = legacyFallback,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (resolvedState != null) {
                TextButton(onClick = { collapsed = !collapsed }) {
                    Text(stringResource(if (collapsed) R.string.chat_image_generation_expand else R.string.chat_image_generation_collapse))
                }
            }
        }

        if (!collapsed) {
            val selected = resolvedState?.slots?.firstOrNull { it.index == selectedSlotIndex }
                ?.takeIf {
                    it.status == ChatImageSlotStatus.SUCCEEDED && it.imageUrl !in failedImageUrls
                }
                ?: availableSucceeded.firstOrNull()
            AnimatedContent(
                targetState = selected?.imageUrl,
                transitionSpec = { androidx.compose.animation.fadeIn(tween(280)) togetherWith androidx.compose.animation.fadeOut(tween(180)) },
                label = "generated-image-main",
            ) { imageUrl ->
                if (imageUrl.isNullOrBlank()) {
                    PendingMainImage(
                        elapsedMillis = elapsedMillis,
                        aspectRatio = resolvedState.imageAspectRatio(),
                        state = resolvedState,
                        active = resolvedActive,
                        missingFile = succeeded.isNotEmpty() && availableSucceeded.isEmpty(),
                    )
                } else {
                    val previewImages = availableSucceeded.mapNotNull { it.imageUrl }
                    ZoomableAsyncImage(
                        model = imageUrl,
                        contentDescription = stringResource(R.string.chat_image_generation_main_description),
                        previewImages = previewImages,
                        previewIndex = previewImages.indexOf(imageUrl).coerceAtLeast(0),
                        onLoadSuccess = {
                            failedImageUrls = failedImageUrls - imageUrl
                        },
                        onLoadError = {
                            failedImageUrls = failedImageUrls + imageUrl
                        },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(resolvedState.imageAspectRatio())
                            .clip(RoundedCornerShape(18.dp)),
                    )
                }
            }

            val slots = resolvedState?.slots.orEmpty()
            if (slots.size > 1) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(slots, key = ChatImageGenerationSlot::index) { slot ->
                        GalleryThumbnail(
                            slot = slot,
                            selected = slot.index == selectedSlotIndex,
                            onClick = {
                                if (slot.status == ChatImageSlotStatus.SUCCEEDED) {
                                    selectedSlotIndex = slot.index
                                }
                            },
                        )
                    }
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                items(resolvedState?.slots.orEmpty().take(4), key = ChatImageGenerationSlot::index) { slot ->
                    GalleryThumbnail(slot = slot, selected = false, onClick = { collapsed = false }, compact = true)
                }
            }
        }
    }
}

@Composable
private fun PendingMainImage(
    elapsedMillis: Long,
    aspectRatio: Float,
    state: ChatImageGenerationState?,
    active: Boolean,
    missingFile: Boolean,
) {
    val failedSlot = state?.slots?.firstOrNull { it.status == ChatImageSlotStatus.FAILED }
    val cancelled = state?.slots?.any { it.status == ChatImageSlotStatus.CANCELLED } == true
    val loading = active && state?.isTerminal != true && !missingFile
    val message = when {
        missingFile -> stringResource(R.string.chat_image_generation_file_missing)
        failedSlot?.error?.isNotBlank() == true -> failedSlot.error
        failedSlot != null -> stringResource(R.string.chat_image_generation_failed)
        cancelled -> stringResource(R.string.chat_image_generation_cancelled)
        loading -> stringResource(
            R.string.chat_image_generation_elapsed,
            formatElapsed(elapsedMillis),
        )
        else -> stringResource(R.string.chat_image_generation_unavailable)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (loading) Modifier.aspectRatio(aspectRatio) else Modifier.height(148.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .shimmer(isLoading = loading),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun GalleryThumbnail(
    slot: ChatImageGenerationSlot,
    selected: Boolean,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(if (compact) 9.dp else 12.dp)
    Surface(
        modifier = Modifier
            .size(if (compact) 48.dp else 62.dp)
            .clickable(onClick = onClick),
        shape = shape,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        color = when (slot.status) {
            ChatImageSlotStatus.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.76f)
        },
    ) {
        val imageUrl = slot.imageUrl
        if (slot.status == ChatImageSlotStatus.SUCCEEDED && !imageUrl.isNullOrBlank()) {
            ZoomableAsyncImage(
                model = imageUrl,
                contentDescription = stringResource(R.string.chat_image_generation_item_description, slot.index + 1),
                onClick = onClick,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (slot.status == ChatImageSlotStatus.RUNNING) Modifier.shimmer(isLoading = true)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (slot.status) {
                        ChatImageSlotStatus.FAILED -> stringResource(R.string.chat_image_generation_failed)
                        ChatImageSlotStatus.CANCELLED -> stringResource(R.string.chat_image_generation_cancelled)
                        ChatImageSlotStatus.RUNNING -> "${slot.index + 1}"
                        else -> "${slot.index + 1}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The durable task ledger is authoritative after a process/service interruption. The
 * conversation checkpoint can legitimately be one emission behind, so project its
 * terminal state instead of leaving an eternal spinner or offering an implicit retry.
 */
internal fun ChatImageGenerationState.reconcileTerminalTask(
    task: ChatImageGenerationTaskRecord?,
): ChatImageGenerationState {
    if (isTerminal || task == null || task.isActive) return this

    val terminalStatus = if (task.phase == ChatImageGenerationTaskPhase.CANCELLED) {
        ChatImageSlotStatus.CANCELLED
    } else {
        ChatImageSlotStatus.FAILED
    }
    val failureKind = when (task.phase) {
        ChatImageGenerationTaskPhase.CANCELLED -> ImageGenerationFailureKind.USER_CANCELLED
        ChatImageGenerationTaskPhase.INTERRUPTED -> ImageGenerationFailureKind.PROCESS_INTERRUPTED
        ChatImageGenerationTaskPhase.COMPLETED -> ImageGenerationFailureKind.DATABASE_WRITE
        else -> task.errorKind ?: ImageGenerationFailureKind.UNKNOWN
    }
    val errorMessage = task.errorMessage ?: when (task.phase) {
        ChatImageGenerationTaskPhase.COMPLETED ->
            "The generated image is in the image library, but the chat checkpoint did not finish."
        ChatImageGenerationTaskPhase.CANCELLED -> "Image generation was cancelled."
        else -> "Image generation was interrupted and was not retried."
    }
    return copy(
        finishedAtEpochMillis = finishedAtEpochMillis ?: task.finishedAtEpochMillis,
        slots = slots.map { slot ->
            if (slot.status == ChatImageSlotStatus.QUEUED || slot.status == ChatImageSlotStatus.RUNNING) {
                slot.copy(
                    status = terminalStatus,
                    error = errorMessage,
                    finishedAtEpochMillis = task.finishedAtEpochMillis,
                    failureKind = failureKind,
                )
            } else {
                slot
            }
        },
    )
}

@Composable
private fun galleryStatusText(
    state: ChatImageGenerationState?,
    active: Boolean,
    elapsedMillis: Long,
    legacyFallback: Boolean,
): String {
    if (state == null) return stringResource(
        if (active) R.string.chat_image_generation_preparing else R.string.chat_image_generation_unavailable
    )
    val total = state.slots.size
    val success = state.succeededCount
    return when {
        legacyFallback -> stringResource(R.string.chat_image_generation_legacy_completed, success)
        !state.isTerminal && active -> stringResource(
            R.string.chat_image_generation_running,
            success,
            total,
            formatElapsed(elapsedMillis),
        )
        !state.isTerminal -> stringResource(
            R.string.chat_image_generation_interrupted,
            success,
            total,
            formatElapsed(elapsedMillis),
        )
        state.slots.any { it.status == ChatImageSlotStatus.CANCELLED } -> stringResource(
            R.string.chat_image_generation_interrupted,
            success,
            total,
            formatElapsed(elapsedMillis),
        )
        state.failedCount > 0 -> stringResource(
            R.string.chat_image_generation_partial,
            success,
            total,
            state.failedCount,
            formatElapsed(elapsedMillis),
        )
        else -> stringResource(
            R.string.chat_image_generation_completed,
            success,
            formatElapsed(elapsedMillis),
        )
    }
}

private fun formatElapsed(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun ChatImageGenerationState?.imageAspectRatio(): Float {
    val value = this?.size.orEmpty()
    val width = value.substringBefore('x').toFloatOrNull()
    val height = value.substringAfter('x', "").toFloatOrNull()
    return if (width != null && height != null && height > 0f) {
        (width / height).coerceIn(0.68f, 1.55f)
    } else {
        1f
    }
}

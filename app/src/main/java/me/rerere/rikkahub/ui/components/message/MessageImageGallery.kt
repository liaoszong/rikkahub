package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage

/**
 * Compact conversation gallery: one large preview and, for multiple outputs,
 * a small selectable strip. This keeps four variants to roughly one message
 * height instead of laying four full-size bitmaps down the conversation.
 */
@Composable
fun MessageImageGallery(
    images: List<String>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val validImages = remember(images) { images.filter { it.isNotBlank() }.distinct() }
    if (validImages.isEmpty()) return

    var selectedIndex by remember(validImages) { mutableIntStateOf(0) }
    val selected = validImages[selectedIndex.coerceIn(validImages.indices)]
    val shape = RoundedCornerShape(14.dp)

    if (compact) {
        Row(
            modifier = modifier
                .widthIn(max = 300.dp)
                .height(84.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            validImages.take(4).forEachIndexed { index, image ->
                Box(modifier = Modifier.weight(1f)) {
                    ZoomableAsyncImage(
                        model = image,
                        contentDescription = null,
                        previewImages = validImages,
                        previewIndex = index,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    if (index == 3 && validImages.size > 4) {
                        RemainingImageCount(
                            count = validImages.size - 4,
                            modifier = Modifier.align(Alignment.BottomEnd),
                        )
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZoomableAsyncImage(
            model = selected,
            contentDescription = null,
            previewImages = validImages,
            previewIndex = selectedIndex,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .aspectRatio(if (validImages.size == 1) 4f / 3f else 16f / 10f)
                .clip(shape),
        )

        if (validImages.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                validImages.take(4).forEachIndexed { index, image ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = if (selectedIndex == index) {
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            null
                        },
                    ) {
                        Box {
                            ZoomableAsyncImage(
                                model = image,
                                contentDescription = null,
                                previewImages = validImages,
                                previewIndex = index,
                                onClick = { selectedIndex = index },
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (index == 3 && validImages.size > 4) {
                                RemainingImageCount(
                                    count = validImages.size - 4,
                                    modifier = Modifier.align(Alignment.BottomEnd),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemainingImageCount(count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.68f),
        shape = RoundedCornerShape(topStart = 8.dp),
    ) {
        Text(
            text = "+$count",
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
        )
    }
}

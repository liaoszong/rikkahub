package me.rerere.rikkahub.ui.pages.imggen

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File

/**
 * Global library for images produced by conversations and older standalone generation tasks.
 * Image creation intentionally lives in conversations; this page only browses durable results.
 */
@Composable
fun ImageLibraryPage(
    modifier: Modifier = Modifier,
    vm: ImageLibraryVM = koinViewModel(),
) {
    val error by vm.error.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    var selectedTab by remember { mutableStateOf(0) }

    LaunchedEffect(error) {
        error?.let { message ->
            toaster.show(message = message, type = ToastType.Error)
            vm.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.asset_library_title)) },
                navigationIcon = { BackButton() },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.asset_library_images)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.asset_library_attachments)) },
                )
            }
            if (selectedTab == 0) {
                ImageLibraryContent(vm = vm, modifier = Modifier.weight(1f))
            } else {
                AttachmentLibraryContent(vm = vm, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AttachmentLibraryContent(
    vm: ImageLibraryVM,
    modifier: Modifier = Modifier,
) {
    val attachments = vm.attachments.collectAsLazyPagingItems()
    val refreshState = attachments.loadState.refresh
    PullToRefreshBox(
        isRefreshing = refreshState is LoadState.Loading,
        onRefresh = attachments::refresh,
        state = rememberPullToRefreshState(),
        modifier = modifier,
    ) {
        when {
            refreshState is LoadState.Loading && attachments.itemCount == 0 -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            refreshState is LoadState.Error && attachments.itemCount == 0 -> EmptyLibrary(
                message = refreshState.error.message ?: stringResource(R.string.asset_library_no_attachments),
                actionLabel = stringResource(R.string.asset_library_retry),
                onAction = attachments::retry,
            )
            attachments.itemCount == 0 -> EmptyLibrary(
                message = stringResource(R.string.asset_library_no_attachments),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    count = attachments.itemCount,
                    key = { index -> attachments[index]?.assetId ?: "attachment-$index" },
                    contentType = { "AttachmentLibraryItem" },
                ) { index ->
                    attachments[index]?.let { asset ->
                        AttachmentLibraryCard(asset = asset, onRemove = { vm.hideAsset(asset) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageLibraryContent(
    vm: ImageLibraryVM,
    modifier: Modifier = Modifier,
) {
    val images = vm.generatedImages.collectAsLazyPagingItems()
    val refreshState = images.loadState.refresh

    PullToRefreshBox(
        isRefreshing = refreshState is LoadState.Loading,
        onRefresh = images::refresh,
        state = rememberPullToRefreshState(),
        modifier = modifier,
    ) {
        when {
            refreshState is LoadState.Loading && images.itemCount == 0 -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            refreshState is LoadState.Error && images.itemCount == 0 -> {
                EmptyLibrary(
                    message = refreshState.error.message
                        ?: stringResource(R.string.imggen_page_no_generated_images),
                    actionLabel = "Retry",
                    onAction = images::retry,
                )
            }

            images.itemCount == 0 -> {
                EmptyLibrary(message = stringResource(R.string.imggen_page_no_generated_images))
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        count = images.itemCount,
                        key = images.itemKey { it.assetId },
                        contentType = images.itemContentType { "ImageLibraryItem" },
                    ) { index ->
                        images[index]?.let { image ->
                            ImageLibraryCard(
                                image = image,
                                onRemove = { vm.hideImage(image) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = HugeIcons.Image03,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun ImageLibraryCard(
    image: ImageLibraryItem,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val filesManager: FilesManager = koinInject()
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var showPreview by remember(image.assetId) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            if (image.fileExists) {
                AsyncImage(
                    model = File(image.filePath),
                    contentDescription = stringResource(R.string.chat_image_generation_main_description),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clickable { showPreview = true },
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.asset_library_file_missing),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = image.model,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Text(
                    text = image.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(
                        enabled = image.fileExists,
                        onClick = {
                            clipboardManager.setText(AnnotatedString(image.prompt))
                            toaster.show(
                                message = "Prompt copied to clipboard",
                                type = ToastType.Success,
                            )
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Copy01,
                            contentDescription = "Copy prompt",
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    IconButton(
                        enabled = image.fileExists,
                        onClick = {
                            runCatching {
                                shareImage(
                                    context = context,
                                    file = File(image.filePath),
                                    mimeType = image.mimeType,
                                )
                            }
                                .onFailure { error ->
                                    toaster.show(
                                        message = buildString {
                                            append(resources.getString(R.string.error_title_operation))
                                            error.message?.let { append(": ").append(it) }
                                        },
                                        type = ToastType.Error,
                                    )
                                }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Share08,
                            contentDescription = stringResource(R.string.common_share),
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    IconButton(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    filesManager.saveMessageImage(context, "file://${image.filePath}")
                                }.onSuccess {
                                    toaster.show(
                                        message = resources.getString(R.string.imggen_page_image_saved_success),
                                        type = ToastType.Success,
                                    )
                                }.onFailure { error ->
                                    toaster.show(
                                        message = resources.getString(
                                            R.string.imggen_page_save_failed,
                                            error.message,
                                        ),
                                        type = ToastType.Error,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.FloppyDisk,
                            contentDescription = stringResource(R.string.imggen_page_save),
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.imggen_page_delete),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (showPreview && image.fileExists) {
        ImagePreviewDialog(
            images = listOf(image.filePath),
            onDismissRequest = { showPreview = false },
        )
    }
}

@Composable
private fun AttachmentLibraryCard(
    asset: ImageLibraryItem,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = HugeIcons.File02,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = if (asset.fileExists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(asset.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text(
                    if (asset.fileExists) {
                        "${asset.mimeType} · ${formatAssetSize(asset.sizeBytes)}"
                    } else {
                        stringResource(R.string.asset_library_file_missing)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (asset.fileExists) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            IconButton(
                enabled = asset.fileExists,
                onClick = {
                    runCatching { shareAsset(context, File(asset.filePath), asset.mimeType) }
                        .onFailure { error ->
                            toaster.show(
                                message = buildString {
                                    append(resources.getString(R.string.error_title_operation))
                                    error.message?.let { append(": ").append(it) }
                                },
                                type = ToastType.Error,
                            )
                        }
                },
            ) {
                Icon(HugeIcons.Share08, stringResource(R.string.common_share))
            }
            IconButton(onClick = onRemove) {
                Icon(
                    HugeIcons.Delete01,
                    stringResource(R.string.imggen_page_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun formatAssetSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun shareImage(context: Context, file: File, mimeType: String) {
    require(file.isFile) { "Image file is unavailable" }
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType.takeIf { it.startsWith("image/") } ?: "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.common_share)))
}

private fun shareAsset(context: Context, file: File, mimeType: String) {
    require(file.isFile) { "Attachment file is unavailable" }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType.ifBlank { "application/octet-stream" }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.common_share)))
}

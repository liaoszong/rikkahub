package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.DownloadCircle02
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.SystemUpdate02
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.utils.AppUpdateState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.UpdateInfo
import me.rerere.rikkahub.utils.openUrl

@Composable
fun AppUpdateDialog(
    updateChecker: UpdateChecker,
    onDismissRequest: () -> Unit,
) {
    val state by updateChecker.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 380.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp,
        ) {
            Box {
                Icon(
                    imageVector = HugeIcons.Rocket01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .graphicsLayer {
                            translationX = 44.dp.toPx()
                            translationY = (-28).dp.toPx()
                            rotationZ = -12f
                        }
                        .size(180.dp)
                        .alpha(0.055f),
                )

                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    UpdateHeader(state)
                    UpdateBody(state)

                    when (val current = state) {
                        is AppUpdateState.Available -> {
                            Button(
                                onClick = { updateChecker.startDownload(current.info) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(HugeIcons.DownloadCircle02, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.update_dialog_update_now))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = {
                                        updateChecker.ignoreVersion(current.info.version)
                                        onDismissRequest()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    Text(stringResource(R.string.update_dialog_ignore_version))
                                }
                                current.info.releaseUrl?.let { url ->
                                    TextButton(
                                        onClick = { context.openUrl(url) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    ) {
                                        Text(stringResource(R.string.update_dialog_view_details))
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = onDismissRequest,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    Text(stringResource(R.string.update_dialog_later))
                                }
                            }
                        }

                        is AppUpdateState.Downloading -> {
                            Text(
                                text = stringResource(R.string.update_dialog_background_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = updateChecker::cancelDownload) {
                                    Text(stringResource(R.string.update_dialog_cancel_download))
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = onDismissRequest) {
                                    Text(stringResource(R.string.update_dialog_continue_background))
                                }
                            }
                        }

                        is AppUpdateState.ReadyToInstall -> {
                            Button(
                                onClick = updateChecker::installDownloadedApk,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(HugeIcons.SystemUpdate02, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.update_dialog_install))
                            }
                            TextButton(onClick = onDismissRequest, modifier = Modifier.align(Alignment.End)) {
                                Text(stringResource(R.string.update_dialog_later))
                            }
                        }

                        is AppUpdateState.Failed -> {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = onDismissRequest) {
                                    Text(stringResource(R.string.update_dialog_later))
                                }
                                Spacer(Modifier.weight(1f))
                                Button(onClick = { updateChecker.checkUpdate() }) {
                                    Text(stringResource(R.string.update_dialog_retry))
                                }
                            }
                        }

                        AppUpdateState.Checking,
                        is AppUpdateState.Verifying,
                        AppUpdateState.UpToDate -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateHeader(state: AppUpdateState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                imageVector = HugeIcons.Sparkles,
                contentDescription = null,
                modifier = Modifier.padding(10.dp).size(22.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = stringResource(R.string.update_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            updateInfoOf(state)?.let { info ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VersionChip("v${BuildConfig.VERSION_NAME}", emphasized = false)
                    Text("→", color = MaterialTheme.colorScheme.outline)
                    VersionChip("v${info.version}", emphasized = true)
                }
            }
        }
    }
}

@Composable
private fun UpdateBody(state: AppUpdateState) {
    when (state) {
        AppUpdateState.Checking -> StatusBlock(
            text = stringResource(R.string.update_dialog_checking),
            loading = true,
        )

        AppUpdateState.UpToDate -> StatusBlock(stringResource(R.string.update_dialog_up_to_date))

        is AppUpdateState.Available -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.update_dialog_changelog),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            MarkdownBlock(
                content = state.info.changelog,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }

        is AppUpdateState.Downloading -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "${state.progress}%",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Light,
            )
            LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = state.download?.name ?: stringResource(R.string.update_dialog_downloading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is AppUpdateState.Verifying -> StatusBlock(
            text = stringResource(R.string.update_dialog_verifying),
            loading = true,
        )

        is AppUpdateState.ReadyToInstall -> StatusBlock(stringResource(R.string.update_dialog_ready))

        is AppUpdateState.Failed -> StatusBlock(
            text = state.message,
            error = true,
        )
    }
}

@Composable
private fun StatusBlock(text: String, loading: Boolean = false, error: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (loading) CircularProgressIndicator()
        Text(
            text = text,
            textAlign = TextAlign.Center,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VersionChip(text: String, emphasized: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = if (emphasized) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun updateInfoOf(state: AppUpdateState): UpdateInfo? = when (state) {
    is AppUpdateState.Available -> state.info
    is AppUpdateState.Downloading -> state.info
    is AppUpdateState.Verifying -> state.info
    is AppUpdateState.ReadyToInstall -> state.info
    else -> null
}

package me.rerere.rikkahub.ui.pages.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.rerere.pale.id.RequestId
import me.rerere.pale.continuity.ResumeInput
import me.rerere.pale.continuity.ResumePlanner
import me.rerere.pale.product.TaskProjector
import me.rerere.pale.request.BillableBoundary
import me.rerere.pale.request.RequestState
import me.rerere.rikkahub.fork.pale.request.RequestLedgerEntity
import me.rerere.rikkahub.fork.pale.request.RequestLedgerRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import kotlin.uuid.Uuid
import org.koin.compose.koinInject

@Composable
fun AgentTaskCenterPage(repository: RequestLedgerRepository = koinInject()) {
    val navController = LocalNavController.current
    val taskFlow = remember(repository) {
        repository.observeRecentRequests()
            .combine(repository.observeProviderReplayRequestIds()) { requests, replayIds ->
                requests.map { it.toTaskProjection(hasProviderReplay = it.requestId in replayIds) }
            }
    }
    val tasks by taskFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val diagnostics = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("任务中心") }, navigationIcon = { BackButton() }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (tasks.isEmpty()) {
                item { Text("暂无长任务或可恢复请求", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(tasks, key = { it.requestId }) { task ->
                Card(
                    modifier = Modifier.clickable {
                        if (task.requestId in diagnostics) {
                            diagnostics.remove(task.requestId)
                        } else {
                            scope.launch {
                                val requestId = RequestId(task.requestId)
                                val manifests = repository.getContextManifests(requestId)
                                val replays = repository.getProviderReplayEnvelopes(requestId)
                                diagnostics[task.requestId] = buildString {
                                    if (manifests.isEmpty()) append("暂无 Context Manifest")
                                    manifests.lastOrNull()?.let { manifest ->
                                        append("Context Manifest · ")
                                        append(manifest["compilerVersion"]?.toString()?.trim('"') ?: "unknown")
                                        append("\n")
                                        append("included=").append(manifest["includedTokens"] ?: "?")
                                        append(" · excluded=").append(manifest["excludedTokens"] ?: "?")
                                        append(" · entries=").append(
                                            (manifest["entries"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
                                        )
                                    }
                                    if (replays.isNotEmpty()) {
                                        append("\nProvider replay · envelopes=").append(replays.size)
                                        append(" · blocks=").append(replays.sumOf { it.blocks.size })
                                    }
                                }
                            }
                        }
                    }
                ) {
                    ListItem(
                        headlineContent = { Text(task.title) },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(task.state.name.lowercase().replace('_', ' '))
                                    if (task.cost?.mayDuplicateOnRetry == true) append(" · 重试可能重复计费")
                                    task.resumeAction?.let { append(" · ").append(it.name.lowercase()) }
                                    task.diagnosticCode?.let { append(" · ").append(it) }
                                }
                            )
                        },
                        trailingContent = {
                            if (task.conversationId != null) {
                                TextButton(onClick = {
                                    val conversationId = task.conversationId ?: return@TextButton
                                    runCatching { Uuid.parse(conversationId) }.getOrNull()?.let {
                                        navigateToChatPage(navController, it)
                                    }
                                }) { Text("打开") }
                            } else {
                                Text("${task.elapsedMillis / 1000}s")
                            }
                        },
                    )
                    diagnostics[task.requestId]?.let { detail ->
                        Text(
                            text = detail,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun RequestLedgerEntity.toTaskProjection(hasProviderReplay: Boolean): me.rerere.pale.product.TaskProjection {
    val state = RequestState.valueOf(requestState.uppercase(Locale.ROOT))
    val boundary = BillableBoundary.valueOf(billableBoundary.uppercase(Locale.ROOT))
    val resume = if (state == RequestState.INTERRUPTED || state == RequestState.UNKNOWN_OUTCOME) {
        ResumePlanner.plan(
            ResumeInput(
                requestState = state,
                billableBoundary = boundary,
                hasCommittedInputs = boundary == BillableBoundary.RESULT_RECEIVED || boundary == BillableBoundary.RESULT_COMMITTED,
                hasUncommittedDurableOutputs = state == RequestState.COMMITTING,
                hasProviderReplay = hasProviderReplay,
                waitingForUserOrPermission = false,
            )
        )
    } else null
    return TaskProjector.project(
        requestId = requestId,
        title = when (requestKind) {
            "chat_generation" -> "回答生成"
            "tool_call", "mcp_tool_call", "workspace_tool" -> "工具执行"
            "image_generation", "image_generation_group" -> "图像生成"
            else -> requestKind.replace('_', ' ')
        },
        requestState = state,
        boundary = boundary,
        nowMillis = System.currentTimeMillis(),
        startedAt = dispatchAt ?: createdAt,
        updatedAt = updatedAt,
        resumeAction = resume,
        errorCode = errorCode ?: unknownOutcomeReason,
        conversationId = conversationId,
    )
}

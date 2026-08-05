package me.rerere.rikkahub.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CoroutineUtils"

fun <T> Flow<T>.toMutableStateFlow(
    scope: CoroutineScope,
    initial: T
): MutableStateFlow<T> {
    val stateFlow = MutableStateFlow(initial)
    scope.launch {
        runCatching {
            this@toMutableStateFlow.collect { value ->
                stateFlow.value = value
            }
        }.onFailure {
            logSafeError(TAG, "coroutine", "collect_flow", it)

            Runtime.getRuntime().halt(1)
        }
    }
    return stateFlow
}

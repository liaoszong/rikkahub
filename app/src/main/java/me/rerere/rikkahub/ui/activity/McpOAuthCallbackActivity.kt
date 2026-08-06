package me.rerere.rikkahub.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.startup.StartupBootstrapGate
import me.rerere.rikkahub.startup.StartupBootstrapState
import org.koin.android.ext.android.inject

/**
 * 透明 Activity，用于接收 MCP OAuth 授权完成后的 deep link 回调
 * (rikkahub://mcp-oauth-callback?code=...&state=...)，解析后经 [AppEventBus] 转发。
 */
class McpOAuthCallbackActivity : ComponentActivity() {
    private val eventBus by inject<AppEventBus>()
    private val appScope by inject<AppScope>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        lifecycleScope.launch {
            val bootstrapState = StartupBootstrapGate.state
                .filter {
                    it === StartupBootstrapState.Ready ||
                        it === StartupBootstrapState.SafeMode ||
                        it is StartupBootstrapState.Failed
                }
                .first()
            if (uri != null && bootstrapState === StartupBootstrapState.Ready) {
                // 使用 AppScope 发送，避免 finish() 取消协程导致事件丢失
                appScope.launch {
                    eventBus.emit(
                        AppEvent.McpOAuthCallback(
                            state = uri.getQueryParameter("state"),
                            code = uri.getQueryParameter("code"),
                            error = uri.getQueryParameter("error"),
                        )
                    )
                }
            }
            finish()
        }
    }
}

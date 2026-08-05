package me.rerere.rikkahub.data.ai.mcp

import me.rerere.common.android.Logging

sealed class McpStatus {
    data object Idle : McpStatus()
    data object Connecting : McpStatus()
    data object Connected : McpStatus()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : McpStatus()

    /**
     * 连接/同步出错。
     *
     * @param message 简短摘要，用于列表内联展示
     * @param detail 经过隐私边界处理的结构化错误信息，用于展开查看与复制；无异常来源时为 null
     */
    data class Error(val message: String, val detail: String? = null) : McpStatus() {
        companion object {
            fun from(throwable: Throwable, fallbackMessage: String? = null): Error {
                val errorClass = throwable.javaClass.simpleName.ifBlank { "UnknownError" }
                val summary = fallbackMessage
                    ?.takeIf { it == "OAuth authorization failed" }
                    ?: "MCP operation failed ($errorClass)"
                return Error(
                    message = summary,
                    detail = Logging.safeErrorMessage(
                        domain = "mcp",
                        operation = "remote_operation",
                        error = throwable,
                    ),
                )
            }
        }
    }

    /** 服务器返回 401，需要用户完成 OAuth 授权。 */
    data object NeedsAuthorization : McpStatus()

    /** 正在进行 OAuth 授权流程（等待浏览器回调 / 交换令牌）。 */
    data object Authorizing : McpStatus()
}

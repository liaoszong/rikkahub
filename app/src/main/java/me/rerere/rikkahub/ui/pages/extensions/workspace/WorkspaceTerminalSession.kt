package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.net.toUri
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.common.android.Logging
import me.rerere.common.android.SafeLogLevel
import me.rerere.common.android.SafeLogOutcome
import me.rerere.workspace.RootfsPatchOptions
import me.rerere.workspace.RootfsPatcher
import java.io.File

internal fun createWorkspaceTerminalSession(
    context: Context,
    root: String,
    client: TerminalSessionClient,
): TerminalSession {
    val appContext = context.applicationContext
    val workspaceDir = File(File(appContext.filesDir, "workspaces"), root)
    val filesDir = File(workspaceDir, "files")
    val linuxDir = File(workspaceDir, "linux")
    val tempDir = File(workspaceDir, "tmp")
    val skillsDir = File(appContext.filesDir, FileFolders.SKILLS).apply { mkdirs() }
    val nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir)
    val proot = File(nativeLibraryDir, "libproot_exec.so")
    val loader = File(nativeLibraryDir, "libproot_loader.so")

    val args = mutableListOf(
        "--root-id",
        "--link2symlink",
        "--kill-on-exit",
        "-r",
        linuxDir.absolutePath,
        "-w",
        WORKSPACE_DIR,
        "-b",
        "${filesDir.absolutePath}:$WORKSPACE_DIR",
        "-b",
        "${skillsDir.absolutePath}:$SKILLS_DIR",
    )
    listOf("/dev", "/proc", "/sys").forEach { path ->
        if (File(path).exists()) {
            args += "-b"
            args += path
        }
    }
    args += listOf(
        "/usr/bin/env",
        "-i",
        "HOME=/root",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "LC_ALL=C.UTF-8",
        "USER=root",
        "SHELL=/bin/bash",
        "/bin/bash",
    )

    val env = arrayOf(
        "PROOT_LOADER=${loader.absolutePath}",
        "PROOT_TMP_DIR=${tempDir.absolutePath}",
        "TMPDIR=${tempDir.absolutePath}",
    )

    return TerminalSession(
        proot.absolutePath,
        filesDir.absolutePath,
        args.toTypedArray(),
        env,
        2_000,
        client,
    ).apply {
        mSessionName = root
    }
}

internal fun prepareWorkspaceTerminalSession(context: Context, root: String) {
    val appContext = context.applicationContext
    val workspaceDir = File(File(appContext.filesDir, "workspaces"), root)
    val linuxDir = File(workspaceDir, "linux")
    File(workspaceDir, "files").mkdirs()
    File(workspaceDir, "tmp").mkdirs()
    File(appContext.filesDir, FileFolders.SKILLS).mkdirs()
    RootfsPatcher().patch(
        linuxDir,
        RootfsPatchOptions(nameservers = appContext.activeDnsServers())
    )
}

internal fun workspaceRootfsReady(context: Context, root: String): Boolean {
    val linuxDir = File(File(File(context.applicationContext.filesDir, "workspaces"), root), "linux")
    return linuxDir.isDirectory && File(linuxDir, "bin/sh").isFile
}

internal class WorkspaceTerminalSessionClient(
    private val context: Context,
    private val onFinished: () -> Unit,
) : TerminalSessionClient {
    var terminalView: TerminalView? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
        onFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: return
        val bytes = text.toByteArray()
        session.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        terminalView?.invalidate()
    }

    override fun getTerminalCursorStyle(): Int =
        TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) {
        logTerminalEvent("terminal_error", SafeLogOutcome.FAILED, SafeLogLevel.ERROR)
    }

    override fun logWarn(tag: String, message: String) {
        logTerminalEvent("terminal_warning", SafeLogOutcome.FAILED, SafeLogLevel.WARN)
    }

    override fun logInfo(tag: String, message: String) {
        logTerminalEvent("terminal_info", SafeLogOutcome.SUCCEEDED, SafeLogLevel.INFO)
    }

    override fun logDebug(tag: String, message: String) {
        logTerminalEvent("terminal_debug", SafeLogOutcome.SUCCEEDED, SafeLogLevel.DEBUG)
    }

    override fun logVerbose(tag: String, message: String) {
        logTerminalEvent("terminal_verbose", SafeLogOutcome.SUCCEEDED, SafeLogLevel.DEBUG)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        logTerminalError("terminal_error", e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        logTerminalError("terminal_error", e)
    }
}

internal class WorkspaceTerminalViewClient(
    private val context: Context,
) : TerminalViewClient {
    var terminalView: TerminalView? = null
    var controlDown: Boolean = false
    var altDown: Boolean = false

    override fun onScale(scale: Float): Float = scale.coerceIn(0.8f, 1.25f)

    override fun onSingleTapUp(e: MotionEvent) {
        if (openUrlAtTap(e)) return
        focusAndShowKeyboard()
    }

    /**
     * 检测点击位置是否落在一个 URL 上, 是则用浏览器打开并返回 true.
     * TerminalView 0.118.0 没有内置链接点击, 这里基于 getColumnAndRow() + 屏幕缓冲文本自行实现,
     * 并通过 getLineWrap() 还原被软换行拆开的长 URL.
     */
    private fun openUrlAtTap(e: MotionEvent): Boolean {
        val view = terminalView ?: return false
        if (view.isSelectingText) return false
        val emulator = view.mEmulator ?: return false
        val screen = emulator.getScreen()
        val columns = emulator.mColumns
        val columnAndRow = view.getColumnAndRow(e, true)
        val column = columnAndRow[0]
        val row = columnAndRow[1]
        val rows = emulator.mRows
        val minAccessibleRow = -screen.activeTranscriptRows
        val maxAccessibleRow = rows - 1
        if (column < 0 || column >= columns) return false
        if (row < minAccessibleRow || row > maxAccessibleRow) return false

        // 向上/向下扩展到完整逻辑行(被软换行拆开的行 mLineWrap 为 true).
        // 限制最多扩展 URL_MAX_WRAP_ROWS 行: 真实 URL 跨不了这么多行, 同时避免连续无换行的
        // 长输出导致单次点击遍历整个 transcript.
        val minRow = (row - URL_MAX_WRAP_ROWS).coerceAtLeast(minAccessibleRow)
        val maxRow = (row + URL_MAX_WRAP_ROWS).coerceAtMost(maxAccessibleRow)
        var startRow = row
        while (startRow > minRow && screen.getLineWrap(startRow - 1)) startRow--
        var endRow = row
        while (endRow < maxRow && screen.getLineWrap(endRow)) endRow++

        val line = StringBuilder()
        var tapIndex = -1
        for (r in startRow..endRow) {
            if (r == row) {
                // 用 [0, column] 这段文本的长度精确换算点击字符在本行内的下标, 避免宽字符错位
                tapIndex = line.length + (screen.getSelectedText(0, r, column, r).length - 1).coerceAtLeast(0)
            }
            line.append(screen.getSelectedText(0, r, columns - 1, r))
        }
        if (tapIndex < 0) return false

        val match = URL_REGEX.findAll(line).firstOrNull { tapIndex in it.range } ?: return false
        val url = match.value.trimEnd(*URL_TRAILING_TRIM)
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrElse {
            logTerminalError("open_terminal_url", it, warning = true)
            false
        }
    }

    fun focusAndShowKeyboard() {
        val view = terminalView ?: return
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view.post {
            view.requestFocus()
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = controlDown

    override fun readAltKey(): Boolean = altDown

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) {
        logTerminalEvent("terminal_view_error", SafeLogOutcome.FAILED, SafeLogLevel.ERROR)
    }

    override fun logWarn(tag: String, message: String) {
        logTerminalEvent("terminal_view_warning", SafeLogOutcome.FAILED, SafeLogLevel.WARN)
    }

    override fun logInfo(tag: String, message: String) {
        logTerminalEvent("terminal_view_info", SafeLogOutcome.SUCCEEDED, SafeLogLevel.INFO)
    }

    override fun logDebug(tag: String, message: String) {
        logTerminalEvent("terminal_view_debug", SafeLogOutcome.SUCCEEDED, SafeLogLevel.DEBUG)
    }

    override fun logVerbose(tag: String, message: String) {
        logTerminalEvent("terminal_view_verbose", SafeLogOutcome.SUCCEEDED, SafeLogLevel.DEBUG)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        logTerminalError("terminal_view_error", e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        logTerminalError("terminal_view_error", e)
    }
}

private const val WORKSPACE_DIR = "/workspace"
private const val SKILLS_DIR = "/skills"
private const val TERMINAL_LOG_TAG = "WorkspaceTerminal"

private fun logTerminalEvent(
    operation: String,
    outcome: SafeLogOutcome,
    level: SafeLogLevel,
) {
    Logging.logOperationToLogcat(
        tag = TERMINAL_LOG_TAG,
        domain = "workspace_terminal",
        operation = operation,
        outcome = outcome,
        level = level,
        persist = false,
    )
}

private fun logTerminalError(operation: String, error: Throwable, warning: Boolean = false) {
    Logging.logErrorToLogcat(
        tag = TERMINAL_LOG_TAG,
        domain = "workspace_terminal",
        operation = operation,
        error = error,
        level = if (warning) SafeLogLevel.WARN else SafeLogLevel.ERROR,
        persist = false,
    )
}

// 一个 URL 最多还原跨越的软换行行数(向上/向下各算), 足够覆盖任意真实 URL
private const val URL_MAX_WRAP_ROWS = 50

private val URL_REGEX =
    Regex("""(https?|ftp)://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""", RegexOption.IGNORE_CASE)

// 终端里 URL 后面常跟标点(行尾句号、被括号包裹等), 打开前去掉这些结尾字符
private val URL_TRAILING_TRIM = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')

private fun Context.activeDnsServers(): List<String> {
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
    val network = connectivityManager.activeNetwork ?: return emptyList()
    return connectivityManager.getLinkProperties(network)
        ?.dnsServers
        ?.mapNotNull { it.hostAddress }
        .orEmpty()
}

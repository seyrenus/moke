package com.briqt.moke.ui

import android.content.Context
import com.briqt.moke.R
import com.briqt.moke.terminal.sftp.GitDiff
import com.briqt.moke.terminal.sftp.RemoteEntry
import com.briqt.moke.terminal.sftp.RemotePath
import com.briqt.moke.terminal.sftp.SftpSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 查看目标：查看器打开的是什么。 */
sealed interface ViewerTarget {
    /** 文件内容（文件页点文件进入）。 */
    data class File(val entry: RemoteEntry) : ViewerTarget

    /** [dir]（进入时的浏览目录）所在仓库的未提交改动（文件页 ⋮ 进入）。 */
    data class RepoDiff(val dir: String) : ViewerTarget

    /** 单个文件的改动（文件长按进入）。 */
    data class FileDiff(val entry: RemoteEntry) : ViewerTarget
}

/** 查看器内容：文件内容或 diff，二者必居其一。 */
sealed interface ViewerContent {
    data class TextFile(
        val path: String,
        /** 二进制时不渲染 [lines]，UI 给下载引导。 */
        val binary: Boolean,
        val lines: List<String>,
        val sizeBytes: Long,
        /** true = 文件比取回的大，只显示了头部。 */
        val truncated: Boolean,
    ) : ViewerContent

    /** [notices] 为顶部横幅（截断、未展开的未跟踪文件数等）。 */
    data class Diff(val files: List<GitDiff.DiffFile>, val truncated: Boolean, val notices: List<String>) : ViewerContent
}

/** 查看器一屏状态。 */
data class ViewerUiState(
    val target: ViewerTarget? = null,
    val loading: Boolean = false,
    val error: String = "",
    val content: ViewerContent? = null,
)

/**
 * 文件查看器（文件内容 + git diff 两种内容类型）的取数与状态。
 *
 * 复用文件页那条 SFTP 连接（经 [sessionProvider] 取 `FilesController` 的会话；离开文件页
 * 连接即断，因此查看器随文件页存活）。取数经 [fetchMutex] 串行：sshj 不保证并发安全，而
 * "换目标"只取消协程不打断 IO，靠互斥保证同一时刻只有一个使用者在连接上。
 */
class FileViewerController(
    context: Context,
    private val scope: CoroutineScope,
    private val sessionProvider: () -> SftpSession?,
) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private var job: Job? = null
    private val fetchMutex = Mutex()

    fun open(target: ViewerTarget) {
        job?.cancel()
        _state.value = ViewerUiState(target = target, loading = true)
        job = scope.launch {
            try {
                val content = fetchMutex.withLock {
                    withContext(Dispatchers.IO) { fetch(target) }
                }
                // 快速换目标时旧取数不得覆盖新界面：只有目标仍一致才落状态。
                if (_state.value.target == target) {
                    _state.update { it.copy(loading = false, content = content, error = "") }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) return@launch
                if (_state.value.target == target) {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = t.message?.takeIf { m -> m.isNotBlank() } ?: t.javaClass.simpleName,
                        )
                    }
                }
            }
        }
    }

    fun refresh() {
        _state.value.target?.let { open(it) }
    }

    fun close() {
        job?.cancel()
        job = null
        _state.value = ViewerUiState()
    }

    // ---------- 取数 ----------

    private fun fetch(target: ViewerTarget): ViewerContent {
        val s = sessionProvider()
            ?: throw IllegalStateException(appContext.getString(R.string.viewer_disconnected))
        return when (target) {
            is ViewerTarget.File -> fetchFile(s, target.entry)
            is ViewerTarget.RepoDiff -> fetchRepoDiff(s, target.dir)
            is ViewerTarget.FileDiff -> fetchFileDiff(s, target.entry)
        }
    }

    private fun fetchFile(s: SftpSession, entry: RemoteEntry): ViewerContent {
        val head = s.readHead(entry.path, TEXT_CAP_BYTES)
        val binary = ViewerText.isBinary(head.bytes)
        return ViewerContent.TextFile(
            path = entry.path,
            binary = binary,
            lines = if (binary) emptyList() else ViewerText.splitLines(ViewerText.decodeUtf8(head.bytes)),
            sizeBytes = entry.size,
            truncated = !head.complete,
        )
    }

    private fun fetchRepoDiff(s: SftpSession, dir: String): ViewerContent {
        val root = repoRoot(s, dir)
        val diff = s.exec(git(root, "diff HEAD"))
            ?: throw IllegalStateException(appContext.getString(R.string.viewer_exec_failed))
        val status = s.exec(git(root, "status --porcelain -uall"), STATUS_CAP)

        val notices = mutableListOf<String>()
        if (diff.truncated) notices += appContext.getString(R.string.viewer_diff_truncated)

        val parsed = GitDiff.parse(diff.stdout)
        val untracked = if (status != null) GitDiff.untrackedPaths(status.stdout) else emptyList()
        // 只合成前 N 个：.gitignore 缺失时（如 node_modules 未忽略）未跟踪树可能巨大，逐个读会打爆。
        val synthesized = untracked.take(MAX_UNTRACKED).map { rel ->
            val head = s.readHead(RemotePath.join(root, rel), UNTRACKED_CAP)
            GitDiff.untrackedFile(
                path = rel,
                content = ViewerText.decodeUtf8(head.bytes),
                isBinary = ViewerText.isBinary(head.bytes),
                truncated = !head.complete,
            )
        }
        if (untracked.size > MAX_UNTRACKED) {
            notices += appContext.getString(R.string.viewer_untracked_more, untracked.size - MAX_UNTRACKED)
        }
        return ViewerContent.Diff(parsed + synthesized, diff.truncated, notices)
    }

    private fun fetchFileDiff(s: SftpSession, entry: RemoteEntry): ViewerContent {
        val root = repoRoot(s, RemotePath.parent(entry.path))
        val rel = GitDiff.relativeTo(root, entry.path) ?: RemotePath.name(entry.path)

        val st = s.exec(git(root, "status --porcelain -uall -- ${q(rel)}"), STATUS_CAP)
            ?: throw IllegalStateException(appContext.getString(R.string.viewer_exec_failed))
        val first = st.stdout.lineSequence().firstOrNull { it.isNotBlank() }
            ?: return ViewerContent.Diff(emptyList(), truncated = false, notices = emptyList())   // 无未提交改动

        return if (first.startsWith("?? ")) {
            val head = s.readHead(entry.path, UNTRACKED_CAP)
            val file = GitDiff.untrackedFile(
                path = rel,
                content = ViewerText.decodeUtf8(head.bytes),
                isBinary = ViewerText.isBinary(head.bytes),
                truncated = !head.complete,
            )
            ViewerContent.Diff(
                listOf(file),
                truncated = false,
                notices = if (!head.complete) listOf(appContext.getString(R.string.viewer_diff_truncated)) else emptyList(),
            )
        } else {
            val d = s.exec(git(root, "diff HEAD -- ${q(rel)}"))
                ?: throw IllegalStateException(appContext.getString(R.string.viewer_exec_failed))
            ViewerContent.Diff(
                GitDiff.parse(d.stdout),
                d.truncated,
                if (d.truncated) listOf(appContext.getString(R.string.viewer_diff_truncated)) else emptyList(),
            )
        }
    }

    /** [dir] 所在仓库根；不是仓库（或远端无 git）时抛带本地化说明的异常。 */
    private fun repoRoot(s: SftpSession, dir: String): String {
        val r = s.exec(git(dir, "rev-parse --show-toplevel"))
            ?: throw IllegalStateException(appContext.getString(R.string.viewer_exec_failed))
        if (r.exitStatus != 0 || r.stdout.isBlank()) {
            throw IllegalStateException(appContext.getString(R.string.viewer_not_a_repo))
        }
        return r.stdout.lineSequence().first().trim()
    }

    // ---------- 远端 git 命令 ----------

    /** 组装远端 git 命令：`-C` 定位 + 关闭路径引用（非 ASCII 文件名按原样 UTF-8 输出，解析端才不用解八进制转义）。 */
    private fun git(dir: String, args: String): String =
        "git -C ${q(dir)} -c core.quotepath=off $args"

    /**
     * 双引号 shell 引用。POSIX 双引号内需转义 \\ " ` $；Windows cmd 不认这些转义，但路径里出现
     * 这类字符属病态场景，接受（与 AGENTS.md 对 Windows 主机的有限兼容口径一致）。
     */
    private fun q(s: String): String =
        "\"" + s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("`", "\\`")
            .replace("$", "\\$") + "\""

    companion object {
        /** 文件内容头部上限（设计共识：2MB，超出提示下载）。 */
        const val TEXT_CAP_BYTES = 2 * 1024 * 1024

        /** 未跟踪文件合成的内容上限。 */
        const val UNTRACKED_CAP = 256 * 1024

        /** status 输出上限。 */
        const val STATUS_CAP = 256 * 1024

        /** 最多合成多少个未跟踪文件的全新增节。 */
        const val MAX_UNTRACKED = 20
    }
}

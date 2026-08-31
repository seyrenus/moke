package com.briqt.moke.terminal.sftp

/**
 * unified diff 的解析与合成（纯逻辑，无 IO / 无 Android 依赖，全部可单测）。
 *
 * 解析对象是 `git -c core.quotepath=off diff …` 的 stdout；合成的场景只有一个：
 * 未跟踪文件不在任何 git diff 输出里，由调用方读文件内容后按「全新增」合成（见
 * `untrackedFile`）——不用 `git diff --no-index /dev/null` 是因为 Windows 主机没有
 * /dev/null（见 AGENTS.md：startupCommand 需照顾 cmd/powershell）。
 */
object GitDiff {

    /** 单行 diff 的呈现类型。 */
    enum class DiffLineKind { ADD, DEL, CONTEXT, HUNK, META }

    /** 一行 diff。[text] 不含 +/-/空格 前缀（META 行为原文）。 */
    data class DiffLine(val kind: DiffLineKind, val text: String)

    /** 一个 hunk：`@@ -a,b +c,d @@ 上下文` 整行作为 [header] 原样展示。 */
    data class DiffHunk(val header: String, val lines: List<DiffLine>)

    /**
     * 一个文件的改动集合。[path] 为 b 侧（新）路径，删除文件时与 [oldPath] 相同；
     * [isBinary] 时 [hunks] 为空，不渲染行。
     */
    data class DiffFile(
        val path: String,
        val oldPath: String,
        val isNew: Boolean,
        val isDeleted: Boolean,
        val isBinary: Boolean,
        val hunks: List<DiffHunk>,
        val added: Int,
        val deleted: Int,
    )

    /**
     * 解析 unified diff 文本。对残缺输出宽容：截断的最后一节（见 `SftpSession.exec` 的
     * 截断语义）按已解析到的内容呈现，不抛错。
     */
    fun parse(text: String): List<DiffFile> {
        val files = mutableListOf<DiffFile>()
        var cur: Builder? = null
        var hunk: MutableList<DiffLine>? = null

        fun flush() {
            cur?.let { files += it.build(hunk) }
            cur = null
            hunk = null
        }

        for (raw in text.split('\n')) {
            val line = raw.removeSuffix("\r")
            when {
                // 1) 新文件开始：hunk 内容行不可能以 "diff --git " 打头，最安全。
                line.startsWith("diff --git ") -> {
                    flush()
                    cur = Builder()
                }
                cur == null -> Unit   // 头部之前的杂散行（git 版本差异/告警），忽略

                // 2) hunk 边界：内容行都带 ' '/'+/'-' 前缀，行首裸 "@@" 只能是 hunk 头。
                line.startsWith("@@") -> {
                    hunk?.let { cur!!.hunks += DiffHunk(cur!!.lastHeader ?: "", it) }
                    hunk = mutableListOf()
                    cur!!.lastHeader = line
                }

                // 3) hunk 内容：必须先于文件级元数据判定——删除内容为 "-- x" 的行会渲染成
                //    "--- x"，与 `--- a/path` 头同名，只能靠"已在 hunk 内"消歧。
                hunk != null -> when {
                    line.startsWith('+') -> hunk!!.add(DiffLine(DiffLineKind.ADD, line.substring(1)))
                    line.startsWith('-') -> hunk!!.add(DiffLine(DiffLineKind.DEL, line.substring(1)))
                    line.startsWith('\\') -> hunk!!.add(DiffLine(DiffLineKind.META, line))   // \ No newline at end of file
                    else -> {
                        // 空上下文行 git 可能给 " " 也可能给 ""；其余未知前缀按上下文兜底。
                        hunk!!.add(DiffLine(DiffLineKind.CONTEXT, line.removePrefix(" ")))
                    }
                }

                // 4) 文件级元数据（只在 hunk 外出现）。
                line.startsWith("new file mode") -> cur!!.isNew = true
                line.startsWith("deleted file mode") -> cur!!.isDeleted = true
                line.startsWith("rename from ") -> cur!!.renameFrom = unquote(line.removePrefix("rename from "))
                line.startsWith("rename to ") -> cur!!.renameTo = unquote(line.removePrefix("rename to "))
                line.startsWith("Binary files ") || line == "GIT binary patch" -> cur!!.isBinary = true
                line.startsWith("--- ") -> cur!!.oldPath = pathOf(line.removePrefix("--- "))
                line.startsWith("+++ ") -> cur!!.newPath = pathOf(line.removePrefix("+++ "))
            }
        }
        flush()
        return files
    }

    /** `git status --porcelain -uall` 输出里的未跟踪文件路径（`?? ` 行，相对仓库根）。 */
    fun untrackedPaths(porcelain: String): List<String> =
        porcelain.split('\n').mapNotNull { raw ->
            val line = raw.removeSuffix("\r")
            if (!line.startsWith("?? ")) return@mapNotNull null
            unquote(line.removePrefix("?? "))
        }.filter { it.isNotEmpty() }

    /** 未跟踪文件的「全新增」合成：整个文件内容都算新增。[isBinary] 由调用方嗅探后传入。 */
    fun untrackedFile(path: String, content: String, isBinary: Boolean, truncated: Boolean): DiffFile {
        if (isBinary) {
            return DiffFile(path, path, isNew = true, isDeleted = false, isBinary = true, hunks = emptyList(), added = 0, deleted = 0)
        }
        val lines = content.split('\n').toMutableList()
        if (lines.lastOrNull()?.isEmpty() == true) lines.removeAt(lines.lastIndex)   // 结尾换行的产物
        val diffLines = lines.map { DiffLine(DiffLineKind.ADD, it.removeSuffix("\r")) }
        val hunk = DiffHunk("@@ -0,0 +1,${diffLines.size} @@", diffLines)
        return DiffFile(
            path = path, oldPath = path, isNew = true, isDeleted = false,
            isBinary = false,
            hunks = listOf(hunk) + if (truncated) listOf(DiffHunk(META_TRUNCATED, emptyList())) else emptyList(),
            added = diffLines.size, deleted = 0,
        )
    }

    /** 截断占位 hunk 的伪头部，渲染层据此追加省略提示。 */
    const val META_TRUNCATED = "@@ …"

    /** 绝对路径 [absolute] 对仓库根 [root] 的相对路径；不在其下时返回 null。 */
    fun relativeTo(root: String, absolute: String): String? {
        val r = root.trimEnd('/')
        val a = absolute.trimEnd('/')
        if (r == "/") return a.removePrefix("/")
        return if (a.startsWith("$r/") && a.length > r.length + 1) a.substring(r.length + 1) else null
    }

    /** 去掉 `a/`、`b/` 前缀；`/dev/null` 原样返回（调用方以空串语义处理）。 */
    private fun pathOf(s: String): String {
        val u = unquote(s.trim())
        return if (u == "/dev/null") "" else u.removePrefix("a/").removePrefix("b/")
    }

    /**
     * git 对含引号/控制字符的路径仍会用 C 风格引号包住（quotepath=off 只管非 ASCII）。
     * 带引号则解一层：常见转义原样映射；连续八进制转义是原始字节，按 UTF-8 组装。
     */
    fun unquote(s: String): String {
        if (s.length < 2 || !s.startsWith("\"") || !s.endsWith("\"")) return s
        val body = s.substring(1, s.length - 1)
        val sb = StringBuilder(body.length)
        val pending = java.io.ByteArrayOutputStream()
        fun flushPending() {
            if (pending.size() > 0) {
                sb.append(pending.toString("UTF-8"))
                pending.reset()
            }
        }
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i + 1 >= body.length) { flushPending(); sb.append(c); i++; continue }
            when (val n = body[i + 1]) {
                'a' -> { flushPending(); sb.append('\u0007'); i += 2 }
                'b' -> { flushPending(); sb.append('\b'); i += 2 }
                'f' -> { flushPending(); sb.append('\u000C'); i += 2 }
                'n' -> { flushPending(); sb.append('\n'); i += 2 }
                'r' -> { flushPending(); sb.append('\r'); i += 2 }
                't' -> { flushPending(); sb.append('\t'); i += 2 }
                'v' -> { flushPending(); sb.append('\u000B'); i += 2 }
                '\\' -> { flushPending(); sb.append('\\'); i += 2 }
                '"' -> { flushPending(); sb.append('"'); i += 2 }
                in '0'..'3' -> {
                    // 八进制最多 3 位（git 对原始字节按 \NNN 输出）
                    var j = i + 1
                    var v = 0
                    while (j < body.length && j < i + 4 && body[j] in '0'..'7') { v = v * 8 + (body[j] - '0'); j++ }
                    pending.write(v)
                    i = j
                }
                else -> { flushPending(); sb.append(c); i++ }
            }
        }
        flushPending()
        return sb.toString()
    }

    /** 解析期累积器。 */
    private class Builder {
        var oldPath: String = ""
        var newPath: String = ""
        var renameFrom: String = ""
        var renameTo: String = ""
        var isNew = false
        var isDeleted = false
        var isBinary = false
        val hunks = mutableListOf<DiffHunk>()
        var lastHeader: String? = null

        /** 收尾：纯重命名（无 ---/+++）用 rename 字段兜底路径。 */
        fun build(currentHunk: List<DiffLine>?): DiffFile {
            val hs = hunks.toMutableList()
            if (currentHunk != null && lastHeader != null) hs += DiffHunk(lastHeader!!, currentHunk)
            val old = oldPath.ifBlank { renameFrom }
            val new = newPath.ifBlank { renameTo }
            val path = new.ifBlank { old }
            var added = 0
            var deleted = 0
            for (h in hs) for (l in h.lines) when (l.kind) {
                DiffLineKind.ADD -> added++
                DiffLineKind.DEL -> deleted++
                else -> Unit
            }
            return DiffFile(path, old, isNew, isDeleted, isBinary, hs, added, deleted)
        }
    }
}

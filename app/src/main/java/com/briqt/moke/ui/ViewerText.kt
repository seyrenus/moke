package com.briqt.moke.ui

/**
 * 文件查看器的文本处理（纯逻辑，无 IO / 无 Android 依赖，全部可单测）。
 *
 * v1 口径（见 CONTEXT.md「文件查看器」与设计共识）：仅按 UTF-8 解码（非法字节显示替换符）；
 * 前 8KB 含 NUL 字节判定为二进制（git 同款启发式）；语法高亮为正则级近似，不追求精确。
 */
object ViewerText {

    /** 二进制判定扫描的字节数：只看头部，与截断口径一致。 */
    const val BINARY_SCAN_BYTES = 8 * 1024

    fun isBinary(bytes: ByteArray, scanLimit: Int = BINARY_SCAN_BYTES): Boolean {
        val n = minOf(bytes.size, scanLimit)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }

    /** UTF-8 解码； malformed 字节由平台替换为 U+FFFD，内容不丢。 */
    fun decodeUtf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    /** 按 `\n` 分行并剥掉行尾 `\r`；末尾换行不产生空尾行。 */
    fun splitLines(text: String): List<String> {
        val lines = text.split('\n').toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        return lines.map { it.removeSuffix("\r") }
    }
}

/** 高亮分类。 */
enum class SpanKind { COMMENT, STRING, NUMBER, KEYWORD }

/** 一段高亮区间（[start, end)，字符下标，作用于单行文本）。 */
data class CodeSpan(val start: Int, val end: Int, val kind: SpanKind)

/**
 * 正则级语法高亮：字符串 / 注释 / 数字 / 关键字四类，逐行独立处理。
 * 判定优先级 字符串 > 注释 > 数字 > 关键字（字符串里的 `#` 不当注释，注释里的数字不着色）。
 * 多行注释只对含开头的行着色，续行不追——「不追求精确」的既定取舍。
 */
object CodeHighlight {

    /** 常见语言关键字并集（C/Java/Kotlin/JS/TS/Python/Go/Rust/Shell/Ruby/PHP），区分大小写。 */
    val KEYWORDS: Set<String> = setOf(
        // 通用流程
        "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
        "return", "goto", "in", "is", "as", "try", "catch", "finally", "throw", "throws", "yield",
        "await", "async", "match", "with", "elif", "except", "pass", "raise", "from", "import",
        "export", "package", "use", "fn", "func", "function", "def", "lambda", "end", "then",
        "begin", "rescue", "ensure", "elsif", "unless", "loop", "until", "select", "defer", "go",
        // 声明与类型
        "class", "struct", "interface", "enum", "trait", "impl", "object", "type", "typedef",
        "namespace", "module", "abstract", "final", "open", "sealed", "data", "static", "const",
        "val", "var", "let", "fun", "public", "private", "protected", "internal", "override",
        "virtual", "inline", "operator", "suspend", "companion", "init", "constructor", "new",
        "this", "self", "super", "base", "extends", "implements", "mut", "pub",
        "unsafe", "where", "by", "get", "set", "delegate", "lateinit", "lazy", "field", "event",
        // 字面与修饰
        "true", "false", "null", "nil", "none", "undefined", "void", "unit", "echo", "print",
        "puts", "require", "include", "using", "extern", "volatile", "register", "sizeof",
        "instanceof", "typeof", "nameof", "not", "and", "or", "xor", "del", "global",
        "nonlocal", "assert", "readonly", "declare",
    )

    // 引号必须闭合才成字符串：多行字符串的续行放弃高亮可接受，而「未闭合吞到行尾」会把
    // it's 这类撇号行整行染错。
    private val STRING_RE = Regex(
        "\"(?:[^\"\\\\\\n]|\\\\.)*\"" +      // 双引号
        "|'(?:[^'\\\\\\n]|\\\\.)*'" +        // 单引号
        "|`(?:[^`\\\\]|\\\\.)*`"             // 反引号（JS 模板 / shell）
    )
    private val BLOCK_COMMENT_RE = Regex("/\\*.*?\\*/|/\\*.*$")
    private val LINE_COMMENT_MARK_RE = Regex("//|#")
    private val NUMBER_RE = Regex("\\b0[xX][0-9a-fA-F_]+\\b|\\b\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b")
    private val WORD_RE = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** C 预处理指令：`#include` 一类不是注释，出现这些词时整行的 `#` 不按注释着色。 */
    private val PREPROCESSOR = setOf(
        "include", "define", "undef", "if", "ifdef", "ifndef", "elif", "else", "endif",
        "pragma", "error", "warning", "import", "line",
    )

    fun highlight(line: String, keywords: Set<String> = KEYWORDS): List<CodeSpan> {
        val spans = mutableListOf<CodeSpan>()

        fun claimed(start: Int, end: Int): Boolean =
            spans.any { start < it.end && end > it.start }

        for (m in STRING_RE.findAll(line)) spans += CodeSpan(m.range.first, m.range.last + 1, SpanKind.STRING)

        // `#` 注释与 C 预处理器指令同形，用「# 后紧跟指令词」排除。
        val trimmed = line.trimStart()
        val isPreprocessor = trimmed.startsWith("#") &&
            trimmed.drop(1).trimStart().takeWhile { it.isLetter() }.lowercase() in PREPROCESSOR
        // 行注释：找到第一个**不在字符串里**的 // 或 #，从那里到行尾都是注释。
        // 字符串里的 # 先跳过，后面的真注释仍要认（`x = "a # b"  # tail` 只认 tail）。
        for (m in LINE_COMMENT_MARK_RE.findAll(line)) {
            if (claimed(m.range.first, m.range.first + 1)) continue
            if (line[m.range.first] == '#' && isPreprocessor) continue
            spans += CodeSpan(m.range.first, line.length, SpanKind.COMMENT)
            break
        }
        for (m in BLOCK_COMMENT_RE.findAll(line)) {
            if (!claimed(m.range.first, m.range.first + 1)) {
                spans += CodeSpan(m.range.first, m.range.last + 1, SpanKind.COMMENT)
            }
        }
        for (m in NUMBER_RE.findAll(line)) {
            if (!claimed(m.range.first, m.range.first + 1)) {
                spans += CodeSpan(m.range.first, m.range.last + 1, SpanKind.NUMBER)
            }
        }
        for (m in WORD_RE.findAll(line)) {
            if (m.value in keywords && !claimed(m.range.first, m.range.first + 1)) {
                spans += CodeSpan(m.range.first, m.range.last + 1, SpanKind.KEYWORD)
            }
        }
        return spans.sortedBy { it.start }
    }
}

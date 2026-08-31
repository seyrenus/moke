package com.briqt.moke.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerTextTest {

    @Test
    fun `含 NUL 字节判为二进制`() {
        assertTrue(ViewerText.isBinary(byteArrayOf(0x50, 0x4B, 0x00, 0x01)))   // zip 头
        assertFalse(ViewerText.isBinary("hello 中文".toByteArray()))
        // NUL 在扫描窗口之外不判二进制（与截断口径一致）
        val lateNul = ByteArray(100) { 'a'.code.toByte() }.also { it[50] = 0 }
        assertFalse(ViewerText.isBinary(lateNul, scanLimit = 8))
        assertTrue(ViewerText.isBinary(lateNul))
    }

    @Test
    fun `UTF-8 解码非法字节变替换符`() {
        assertEquals("中文", ViewerText.decodeUtf8("中文".toByteArray()))
        // GBK 的「中」是 D6 D0，按 UTF-8 非法 → U+FFFD，不抛异常、内容不丢
        val decoded = ViewerText.decodeUtf8(byteArrayOf(0xD6.toByte(), 0xD0.toByte()))
        assertEquals("\uFFFD", decoded)
    }

    @Test
    fun `分行剥 CR 且末尾换行不产生空尾行`() {
        assertEquals(listOf("a", "b"), ViewerText.splitLines("a\nb\n"))
        assertEquals(listOf("a", "b"), ViewerText.splitLines("a\r\nb"))
        assertEquals(listOf("a", ""), ViewerText.splitLines("a\n\n"))
        assertEquals(emptyList<String>(), ViewerText.splitLines(""))
        assertEquals(listOf("x"), ViewerText.splitLines("x"))
    }

    // ---------- 高亮 ----------

    private fun kindsOf(line: String): List<Pair<SpanKind, String>> =
        CodeHighlight.highlight(line).map { it.kind to line.substring(it.start, it.end) }

    @Test
    fun `关键字与注释着色`() {
        val spans = kindsOf("def foo():  # tail")
        assertTrue(SpanKind.KEYWORD to "def" in spans)
        assertTrue(SpanKind.COMMENT to "# tail" in spans)
        assertFalse(spans.any { it.second == "foo" && it.first == SpanKind.KEYWORD })
    }

    @Test
    fun `字符串里的井号不是注释`() {
        val spans = kindsOf("x = \"a # b\"  # tail")
        assertTrue(SpanKind.STRING to "\"a # b\"" in spans)
        assertTrue(SpanKind.COMMENT to "# tail" in spans)
        // 字符串内部的 # 不得开启注释
        assertFalse(spans.any { it.kind == SpanKind.COMMENT && it.second.startsWith("# b") })
    }

    @Test
    fun `撇号不吞行尾`() {
        // it's 的撇号后面没有闭合引号，不得把行尾染成字符串
        val spans = kindsOf("it's here")
        assertFalse(spans.any { it.kind == SpanKind.STRING })
    }

    @Test
    fun `注释里的数字与关键字不再着色`() {
        val source = "return 42 // return 42"
        val spans = kindsOf(source)
        val commentSpan = spans.last { it.first == SpanKind.COMMENT }
        assertEquals("// return 42", commentSpan.second)
        // 只有着色区间里的数字/关键字计入：注释内的 return/42 不再着色
        assertEquals(1, spans.count { it.first == SpanKind.NUMBER })
        assertEquals(1, spans.count { it.first == SpanKind.KEYWORD })
    }

    @Test
    fun `数字含十六进制与小数`() {
        val spans = kindsOf("x = 0xFF + 3.14 + a1")
        assertTrue(SpanKind.NUMBER to "0xFF" in spans)
        assertTrue(SpanKind.NUMBER to "3.14" in spans)
        // a1 是标识符，其中的 1 不是数字
        assertFalse(spans.any { it.first == SpanKind.NUMBER && it.second == "1" })
    }

    @Test
    fun `预处理指令的井号不按注释`() {
        val spans = kindsOf("#include <stdio.h>")
        assertFalse(spans.any { it.kind == SpanKind.COMMENT })
    }

    @Test
    fun `块注释单行成对`() {
        val spans = kindsOf("x = 1 /* inline */ + 2")
        assertTrue(spans.any { it.kind == SpanKind.COMMENT && it.second == "/* inline */" })
    }
}

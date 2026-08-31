package com.briqt.moke.terminal.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitDiffTest {

    @Test
    fun `多文件 diff 解析出路径与增删计数`() {
        val files = GitDiff.parse(
            """
            diff --git a/foo.txt b/foo.txt
            index 83db48f..bf269f4 100644
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,4 +1,4 @@
             context
            -removed line
            +added line
             context2
            diff --git a/bar.txt b/bar.txt
            new file mode 100644
            index 0000000..e69de29
            --- /dev/null
            +++ b/bar.txt
            @@ -0,0 +1,2 @@
            +hello
            +world
            """.trimIndent(),
        )

        assertEquals(2, files.size)

        val foo = files[0]
        assertEquals("foo.txt", foo.path)
        assertEquals(1, foo.added)
        assertEquals(1, foo.deleted)
        assertFalse(foo.isNew)
        assertEquals(1, foo.hunks.size)
        assertTrue(foo.hunks[0].header.startsWith("@@ -1,4 +1,4 @@"))
        assertEquals(
            listOf(
                GitDiff.DiffLineKind.CONTEXT to "context",
                GitDiff.DiffLineKind.DEL to "removed line",
                GitDiff.DiffLineKind.ADD to "added line",
                GitDiff.DiffLineKind.CONTEXT to "context2",
            ),
            foo.hunks[0].lines.map { it.kind to it.text },
        )

        val bar = files[1]
        assertEquals("bar.txt", bar.path)
        assertTrue(bar.isNew)
        assertEquals(2, bar.added)
        assertTrue(bar.hunks[0].lines.all { it.kind == GitDiff.DiffLineKind.ADD })
    }

    @Test
    fun `删除文件取 a 侧路径`() {
        val files = GitDiff.parse(
            """
            diff --git a/gone.txt b/gone.txt
            deleted file mode 100644
            index e69de29..0000000
            --- a/gone.txt
            +++ /dev/null
            @@ -1 +0,0 @@
            -bye
            """.trimIndent(),
        )
        val gone = files.single()
        assertTrue(gone.isDeleted)
        assertEquals("gone.txt", gone.path)
        assertEquals(1, gone.deleted)
    }

    @Test
    fun `hunk 内的 --- 行是内容不是文件头`() {
        // 删除内容为 "-- x" 的行会渲染成 "--- x"，与 `--- a/path` 头同形；
        // 已在 hunk 内时必须按内容算（unified diff 解析的经典歧义）。
        val files = GitDiff.parse(
            """
            diff --git a/md.md b/md.md
            --- a/md.md
            +++ b/md.md
            @@ -1,2 +1,3 @@
             a
            --- x
            +++ y
            """.trimIndent(),
        )
        val md = files.single()
        assertEquals("md.md", md.path)   // 文件头只出现一次
        assertEquals(
            listOf(
                GitDiff.DiffLineKind.CONTEXT to "a",
                GitDiff.DiffLineKind.DEL to "-- x",
                GitDiff.DiffLineKind.ADD to "+ y",
            ),
            md.hunks.single().lines.map { it.kind to it.text },
        )
    }

    @Test
    fun `二进制与纯重命名没有 hunk`() {
        val files = GitDiff.parse(
            """
            diff --git a/img.png b/img.png
            index 123..456 100644
            Binary files a/img.png and b/img.png differ
            diff --git a/old.txt b/new.txt
            similarity index 90%
            rename from old.txt
            rename to new.txt
            """.trimIndent(),
        )
        assertEquals(2, files.size)
        assertTrue(files[0].isBinary)
        assertEquals("img.png", files[0].path)
        assertTrue(files[0].hunks.isEmpty())
        assertEquals("new.txt", files[1].path)
        assertEquals("old.txt", files[1].oldPath)
    }

    @Test
    fun `截断的最后一节按已解析内容容忍`() {
        val files = GitDiff.parse(
            """
            diff --git a/ok.txt b/ok.txt
            --- a/ok.txt
            +++ b/ok.txt
            @@ -1 +1 @@
            -old
            +new
            diff --git a/cut.txt b/cut.txt
            --- a/cut.txt
            +++ b/cut
            """.trimIndent(),
        )
        assertEquals(2, files.size)
        assertEquals("new", files[0].hunks.single().lines.first { it.kind == GitDiff.DiffLineKind.ADD }.text)
        assertEquals("cut.txt", files[1].path)
    }

    @Test
    fun `porcelain 里的未跟踪行取路径并解引号`() {
        val paths = GitDiff.untrackedPaths(
            """
            ?? a.txt
             M b.txt
            ?? "with space.txt"
            ?? "\346\226\207.txt"
            ?? dir/
            R  old -> new
            """.trimIndent(),
        )
        assertEquals(listOf("a.txt", "with space.txt", "文.txt", "dir/"), paths)
    }

    @Test
    fun `未跟踪文件合成为全新增`() {
        val f = GitDiff.untrackedFile("n.txt", "one\n\ntwo\n", isBinary = false, truncated = false)
        assertTrue(f.isNew)
        assertEquals("n.txt", f.path)
        assertEquals(3, f.added)
        assertEquals(
            listOf("one", "", "two"),
            f.hunks.single().lines.map { it.text },
        )
        assertTrue(f.hunks.single().lines.all { it.kind == GitDiff.DiffLineKind.ADD })
    }

    @Test
    fun `未跟踪截断与二进制合成`() {
        val truncated = GitDiff.untrackedFile("big.txt", "a\nb", isBinary = false, truncated = true)
        assertEquals(2, truncated.hunks.size)
        assertEquals(GitDiff.META_TRUNCATED, truncated.hunks[1].header)

        val binary = GitDiff.untrackedFile("img.bin", "", isBinary = true, truncated = false)
        assertTrue(binary.isBinary)
        assertTrue(binary.hunks.isEmpty())
    }

    @Test
    fun `相对路径判定`() {
        assertEquals("sub/x.txt", GitDiff.relativeTo("/srv/repo", "/srv/repo/sub/x.txt"))
        assertEquals("x.txt", GitDiff.relativeTo("/srv/repo", "/srv/repo/x.txt"))
        assertNull(GitDiff.relativeTo("/srv/repo", "/srv/other/x.txt"))
        assertNull(GitDiff.relativeTo("/srv/repo", "/srv/repo"))
        assertEquals("etc/x", GitDiff.relativeTo("/", "/etc/x"))
    }

    @Test
    fun `unquote 处理引号转义与八进制`() {
        assertEquals("plain.txt", GitDiff.unquote("plain.txt"))
        assertEquals("a\"b", GitDiff.unquote("\"a\\\"b\""))
        // \346\226\207 是「文」的 UTF-8 三字节，应组装回原字而不是三个 mojibake 字符
        assertEquals("文", GitDiff.unquote("\"\\346\\226\\207\""))
        assertEquals("文.txt", GitDiff.unquote("\"\\346\\226\\207.txt\""))
    }
}

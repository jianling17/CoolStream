package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 长文阅读器链路的两块纯逻辑：路径识别 + 文件读取。
 *
 * 路径识别的边界最要紧 —— 正则写松了，满屏 URL 都会变成「可点开的文档卡片」。
 */
class FileCardTest {

    // ---------- fileCardsOf：该认的 ----------

    @Test
    fun path_plainTxt_found() {
        val cards = fileCardsOf("写好了，文件在 /storage/emulated/0/Documents/CoolStream_Workspace/report.txt 里")
        assertEquals(1, cards.size)
        assertEquals("/storage/emulated/0/Documents/CoolStream_Workspace/report.txt", cards[0].path)
        assertEquals("report.txt", cards[0].name)
    }

    @Test
    fun path_markdown_found() {
        val cards = fileCardsOf("见 /sdcard/notes/plan.md")
        assertEquals(1, cards.size)
        assertEquals("plan.md", cards[0].name)
    }

    @Test
    fun path_allTextExtensions_found() {
        val raw = "/a/b/one.txt /a/b/two.md /a/b/three.log /a/b/four.csv /a/b/five.json"
        assertEquals(5, fileCardsOf(raw).size)
    }

    @Test
    fun path_twoDifferent_bothKept() {
        val cards = fileCardsOf("/sdcard/a.txt 和 /sdcard/b.txt")
        assertEquals(2, cards.size)
        assertEquals("a.txt", cards[0].name)
        assertEquals("b.txt", cards[1].name)
    }

    // ---------- fileCardsOf：不该认的 ----------

    @Test
    fun path_url_notMatched() {
        assertTrue(fileCardsOf("来源：https://example.com/docs/readme.txt").isEmpty())
    }

    @Test
    fun path_relative_notMatched() {
        assertTrue(fileCardsOf("放在 notes/readme.txt 里").isEmpty())
    }

    @Test
    fun path_sourceCode_notMatched() {
        assertTrue(fileCardsOf("改的是 /src/main/java/Foo.kt").isEmpty())
    }

    @Test
    fun path_noSlash_empty() {
        assertTrue(fileCardsOf("纯文本没有任何路径").isEmpty())
        assertTrue(fileCardsOf("").isEmpty())
    }

    @Test
    fun path_tooShort_skipped() {
        // "/x.md" 只有 5 个字符，基本是噪音
        assertTrue(fileCardsOf("看 /x.md").isEmpty())
    }

    @Test
    fun path_duplicate_deduped() {
        val raw = "/sdcard/a.txt 已经写好，请打开 /sdcard/a.txt 查看"
        assertEquals(1, fileCardsOf(raw).size)
    }

    // ---------- readLongTextFile ----------

    @Test
    fun read_normal_returnsContent() {
        val f = File.createTempFile("coolstream-test", ".txt")
        try {
            f.writeText("第一行" + 10.toChar() + "第二行")
            assertEquals("第一行" + 10.toChar() + "第二行", readLongTextFile(f.absolutePath))
        } finally {
            f.delete()
        }
    }

    @Test
    fun read_missing_throws() {
        val boom = runCatching { readLongTextFile("/definitely/not/here.txt") }
        assertTrue(boom.isFailure)
        assertTrue(boom.exceptionOrNull()!!.message!!.contains("文件不存在"))
    }

    @Test
    fun read_directory_throws() {
        val dir = File.createTempFile("coolstream-dir", "")
        try {
            dir.delete()
            dir.mkdirs()
            assertTrue(runCatching { readLongTextFile(dir.absolutePath) }.isFailure)
        } finally {
            dir.delete()
        }
    }
}

package com.example.mychat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 阶段三：超长工具结果落盘。
 * 只测 persistOversizeToolOutput 这一个纯函数，不碰网络、不碰 UI。
 */
class ToolOutputPersistTest {

    private val originalWorkspace = currentWorkspace

    @After
    fun restoreWorkspace() {
        currentWorkspace = originalWorkspace
    }

    private fun tempWorkspace(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "coolstream-persist-" + System.nanoTime())
        dir.mkdirs()
        currentWorkspace = dir.absolutePath
        return dir
    }

    private fun savedFiles(dir: File): List<File> =
        File(dir, TOOL_TEMP_DIR).listFiles()?.filter { it.isFile } ?: emptyList()

    @Test
    fun shortOutputUntouched() {
        val short = "abc".repeat(100)
        assertEquals(short, persistOversizeToolOutput("read_file", short))
    }

    @Test
    fun emptyOutputUntouched() {
        assertEquals("", persistOversizeToolOutput("read_file", ""))
    }

    @Test
    fun exactlyAtLimitUntouched() {
        val text = "y".repeat(TOOL_INLINE_LIMIT)
        assertEquals(text, persistOversizeToolOutput("read_file", text))
    }

    @Test
    fun oversizeWritesFileAndTruncates() {
        val dir = tempWorkspace()
        val text = "z".repeat(TOOL_INLINE_LIMIT + 500)
        val out = persistOversizeToolOutput("read_file", text)
        assertTrue(out.length < text.length)
        assertTrue(out.startsWith(text.take(TOOL_INLINE_HEAD)))
        assertTrue(out.contains("完整内容已保存到"))
        assertTrue(out.contains(text.length.toString()))
        val saved = savedFiles(dir)
        assertEquals(1, saved.size)
        assertEquals(text, saved[0].readText())
        dir.deleteRecursively()
    }

    @Test
    fun oversizeKeepsTailOutOfPrompt() {
        val dir = tempWorkspace()
        val marker = "尾部标记不该出现在回传文本里"
        val text = "a".repeat(TOOL_INLINE_LIMIT) + marker
        val out = persistOversizeToolOutput("run_shell", text)
        assertTrue(!out.contains(marker))
        dir.deleteRecursively()
    }

    @Test
    fun blankWorkspaceFallsBackToHeadOnly() {
        currentWorkspace = ""
        val text = "w".repeat(TOOL_INLINE_LIMIT + 1)
        val out = persistOversizeToolOutput("read_file", text)
        assertTrue(out.startsWith(text.take(TOOL_INLINE_HEAD)))
        assertTrue(out.contains("未设置工作区"))
        assertTrue(out.length < text.length)
    }

    @Test
    fun toolNameIsSanitizedIntoFileName() {
        val dir = tempWorkspace()
        persistOversizeToolOutput("../evil name", "q".repeat(TOOL_INLINE_LIMIT + 1))
        val saved = savedFiles(dir)
        assertEquals(1, saved.size)
        assertTrue(!saved[0].name.contains(" "))
        assertTrue(!saved[0].name.contains(".."))
        assertTrue(saved[0].name.endsWith(".txt"))
        dir.deleteRecursively()
    }

    @Test
    fun pruneKeepsOnlyLatestFiles() {
        val dir = tempWorkspace()
        val tempDir = File(dir, TOOL_TEMP_DIR)
        tempDir.mkdirs()
        for (i in 1..TOOL_TEMP_KEEP + 10) {
            val f = File(tempDir, "old-" + i + ".txt")
            f.writeText("x")
            f.setLastModified(i.toLong() * 1000L)
        }
        persistOversizeToolOutput("read_file", "r".repeat(TOOL_INLINE_LIMIT + 1))
        val count = savedFiles(dir).size
        assertTrue(count <= TOOL_TEMP_KEEP)
        assertTrue(count >= 1)
        dir.deleteRecursively()
    }
}

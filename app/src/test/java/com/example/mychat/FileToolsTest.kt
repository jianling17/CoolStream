package com.example.mychat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 文件类工具的单元测试（跑在电脑 JVM 上，不需要真机）。
 *
 * 为什么要在意这一层：
 *   write_file / replace_in_file 直接动用户的真实文件，改错一次就是数据事故。
 *   这几条用例把「什么情况下应该拒绝修改」钉死了 —— 以后谁动这块代码，
 *   跑一遍就知道有没有把安全边界改坏。
 *
 * 运行：./gradlew testDebugUnitTest
 */
class FileToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 用例里的「工作区」根目录 */
    private lateinit var root: File

    @Before
    fun setUp() {
        root = tmp.newFolder("ws")
        currentWorkspace = root.absolutePath
    }

    @After
    fun tearDown() {
        // 别把工作区状态漏给下一个用例
        currentWorkspace = ""
    }

    // ---------------------------------------------------------------- 辅助

    private fun write(name: String, content: String): File {
        val f = File(root, name)
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
        return f
    }

    // ================================================================
    // replace_in_file：唯一匹配 → 真的只改那一处
    // ================================================================

    @Test
    fun replace_changes_only_the_matched_occurrence() {
        val f = write(
            "a.kt",
            """
            fun greet() {
                println("Hello")
            }

            fun other() {
                println("Hello")
            }
            """.trimIndent()
        )

        // search 带了函数签名做上下文，所以只可能匹配到第一处
        val result = replaceInFile(
            f.absolutePath,
            "fun greet() {\n    println(\"Hello\")",
            "fun greet() {\n    println(\"Hi\")",
            replaceAll = false
        )

        assertTrue(result.output, result.ok)
        val text = f.readText()
        assertTrue("greet 应该被改成 Hi", text.contains("fun greet() {\n    println(\"Hi\")"))
        assertTrue("other 必须原样保留", text.contains("fun other() {\n    println(\"Hello\")"))
    }

    @Test
    fun empty_replace_deletes_the_matched_text() {
        val f = write("del.txt", "keep DELETE_ME keep")
        val result = replaceInFile(f.absolutePath, "DELETE_ME ", "", replaceAll = false)
        assertTrue(result.output, result.ok)
        assertEquals("keep keep", f.readText())
    }

    // ================================================================
    // 安全边界：该拒绝的时候一定拒绝，且不改动文件
    // ================================================================

    @Test
    fun ambiguous_match_is_refused_and_file_is_untouched() {
        val original = """
            fun a() { println("Hello") }
            fun b() { println("Hello") }
        """.trimIndent()
        val f = write("ambiguous.kt", original)

        val result = replaceInFile(f.absolutePath, "println(\"Hello\")", "println(\"Hi\")", false)

        assertFalse("出现两处时必须拒绝", result.ok)
        assertTrue(result.output, result.output.contains("出现了 2 次"))
        assertEquals("拒绝之后文件一个字都不能变", original, f.readText())
    }

    @Test
    fun missing_search_is_refused_and_file_is_untouched() {
        val f = write("missing.txt", "hello world")
        val result = replaceInFile(f.absolutePath, "goodbye", "hi", false)

        assertFalse(result.ok)
        assertTrue(result.output, result.output.contains("找不到"))
        assertEquals("hello world", f.readText())
    }

    @Test
    fun whitespace_only_mismatch_gives_the_indentation_hint() {
        // 文件里是多行缩进写法，模型给了压成一行的版本 —— 只差空白
        val f = write("indent.kt", "fun a() {\n    println(1)\n}")
        val result = replaceInFile(f.absolutePath, "fun a() { println(1) }", "fun a() {}", false)

        assertFalse(result.ok)
        assertTrue(
            "应该提示是空白差异，而不是让模型瞎猜",
            result.output.contains("忽略空白差异")
        )
    }

    @Test
    fun binary_file_is_refused() {
        val f = File(root, "pic.bin")
        // 带 NUL 字节 → 按二进制处理
        f.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x01))
        val result = replaceInFile(f.absolutePath, "PK", "XX", false)

        assertFalse(result.ok)
        assertTrue(result.output, result.output.contains("二进制"))
    }

    @Test
    fun empty_search_is_refused() {
        val f = write("e.txt", "abc")
        val result = replaceInFile(f.absolutePath, "", "x", false)
        assertFalse(result.ok)
        assertTrue(result.output, result.output.contains("search 是空的"))
    }

    // ================================================================
    // 工作区：三种越界方式都必须拦住
    // ================================================================

    @Test
    fun path_outside_workspace_is_blocked() {
        val outside = tmp.newFolder("outside1")
        val f = File(outside, "x.txt").apply { writeText("abc") }

        val result = replaceInFile(f.absolutePath, "abc", "xyz", false)

        assertFalse("工作区外必须拒绝", result.ok)
        assertTrue(result.output, result.output.contains("工作区越界"))
        assertEquals("abc", f.readText())
    }

    @Test
    fun dot_dot_traversal_is_blocked() {
        // 关键回归：老实现用 File.absolutePath 判断，不解析 ".."，
        // 这条路径在字符串层面以工作区开头，会被误判成"在工作区内"。
        // 换成 canonicalPath 之后才能真正拦住。
        val outside = tmp.newFolder("outside2")
        val f = File(outside, "y.txt").apply { writeText("abc") }

        val sneaky = File(root, "../${outside.name}/y.txt").absolutePath
        assertTrue("这条路径里确实带 ..", sneaky.contains(".."))

        val result = replaceInFile(sneaky, "abc", "xyz", false)

        assertFalse(".. 穿越必须拒绝", result.ok)
        assertTrue(result.output, result.output.contains("工作区越界"))
        assertEquals("abc", f.readText())
    }

    @Test
    fun workspace_unset_means_no_restriction() {
        currentWorkspace = ""
        val anywhere = tmp.newFolder("free")
        val f = File(anywhere, "z.txt").apply { writeText("abc") }

        val result = replaceInFile(f.absolutePath, "abc", "xyz", false)

        assertTrue(result.output, result.ok)
        assertEquals("xyz", f.readText())
    }

    // ================================================================
    // 备份与 replace_all
    // ================================================================

    @Test
    fun overwriting_leaves_a_bak_copy_of_the_original() {
        val f = write("bak.txt", "old content")
        val result = replaceInFile(f.absolutePath, "old", "new", false)

        assertTrue(result.output, result.ok)
        val bak = File(f.absolutePath + ".bak")
        assertTrue("覆盖前应该留一份 .bak", bak.exists())
        assertEquals("old content", bak.readText())
        assertEquals("new content", f.readText())
    }

    @Test
    fun replace_all_replaces_every_occurrence() {
        val f = write("all.txt", "X X X")
        val result = replaceInFile(f.absolutePath, "X", "Y", replaceAll = true)

        assertTrue(result.output, result.ok)
        assertEquals("Y Y Y", f.readText())
        assertTrue(result.output, result.output.contains("共替换 3 处"))
    }

    // ================================================================
    // write_file
    // ================================================================

    @Test
    fun write_file_creates_missing_parent_directories() {
        val target = File(root, "sub/deep/n.txt").absolutePath
        val result = writeTextFile(target, "hi")

        assertTrue(result.output, result.ok)
        assertEquals("hi", File(target).readText())
    }

    @Test
    fun write_file_outside_workspace_is_blocked() {
        val outside = tmp.newFolder("outside3")
        val target = File(outside, "w.txt").absolutePath

        val result = writeTextFile(target, "x")

        assertFalse(result.ok)
        assertTrue(result.output, result.output.contains("工作区越界"))
        assertFalse("被拦下就不该创建文件", File(target).exists())
    }

    // ================================================================
    // 小工具
    // ================================================================

    @Test
    fun count_occurrences_does_not_overlap() {
        // "aaaa" 里的 "aa" 只能算 2 次（位置 0 和 2），不是 3 次
        assertEquals(2, countOccurrences("aaaa", "aa"))
        assertEquals(0, countOccurrences("abc", ""))
        assertEquals(1, countOccurrences("abc", "abc"))
    }

    @Test
    fun line_number_at_counts_from_one() {
        val text = "line1\nline2\nline3"
        assertEquals(1, lineNumberAt(text, 0))
        assertEquals(2, lineNumberAt(text, 6))   // 指向 "line2" 的开头
        assertEquals(3, lineNumberAt(text, 12))  // 指向 "line3" 的开头
    }
}

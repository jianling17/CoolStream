package com.example.mychat

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Agent 预设 + PTC 沙箱的单元测试。
 *
 * 这些测试跑在普通 JVM 上（不需要真机、不需要模拟器），
 * 因为它们打的是「纯逻辑 + JS 执行」这两层 —— 恰恰是最容易写错又最难靠肉眼发现的地方。
 *
 * ⚠️ 测试里 SystemClock.elapsedRealtime() 恒为 0（unitTests.isReturnDefaultValues = true），
 *    所以沙箱的超时中断在这里【测不出来】，要验证超时得在真机上跑。
 *    但「Java 类被彻底封锁」这条能测 —— 而且必须测，它是整个 PTC 的安全底线。
 */
class AgentPresetTest {

    private lateinit var tmpDir: File
    private var savedWorkspace: String = ""
    private var savedPreset: AgentPreset = AgentPreset.STANDARD
    private var savedTools: Set<String> = emptySet()

    @Before
    fun setUp() {
        tmpDir = File.createTempFile("ptc-test", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        savedWorkspace = currentWorkspace
        savedPreset = AppSettings.preset
        savedTools = AppSettings.enabledTools
        currentWorkspace = tmpDir.absolutePath
    }

    @After
    fun tearDown() {
        currentWorkspace = savedWorkspace
        AppSettings.preset = savedPreset
        AppSettings.enabledTools = savedTools
        tmpDir.deleteRecursively()
    }

    // ==================== 预设 → tools 数组 ====================

    @Test
    fun `标准模式按开关过滤工具`() {
        AppSettings.preset = AgentPreset.STANDARD
        AppSettings.enabledTools = setOf("read_file", "write_file")

        val tools = buildToolsArray()
        assertEquals(2, tools.length())
        val names = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }
        assertTrue(names.contains("read_file"))
        assertTrue(names.contains("write_file"))
        assertFalse(names.contains("run_shell"))
    }

    @Test
    fun `PTC模式只发一个run_code`() {
        AppSettings.preset = AgentPreset.PTC
        // 故意把六个工具全关掉：run_code 本身不是"插件"，不该被开关影响
        AppSettings.enabledTools = emptySet()

        val tools = buildToolsArray()
        assertEquals(1, tools.length())
        val fn = tools.getJSONObject(0).getJSONObject("function")
        assertEquals("run_code", fn.getString("name"))
        // schema 里必须真的有 code 这个必填参数，否则模型会传错
        val required = fn.getJSONObject("parameters").getJSONArray("required")
        assertEquals("code", required.getString(0))
    }

    @Test
    fun `极简模式一个工具都不发`() {
        AppSettings.preset = AgentPreset.MINIMAL
        AppSettings.enabledTools = ALL_TOOL_NAMES.toSet()

        assertEquals(0, buildToolsArray().length())
    }

    @Test
    fun `未知预设字符串回退到标准`() {
        assertEquals(AgentPreset.STANDARD, AgentPreset.of("不存在的模式"))
        assertEquals(AgentPreset.STANDARD, AgentPreset.of(null))
        assertEquals(AgentPreset.PTC, AgentPreset.of("ptc"))
    }

    // ==================== PTC 沙箱 ====================

    @Test
    fun `沙箱能执行JS并拿到print输出`() {
        val r = runCode("print('hello from ptc'); 1 + 1")
        assertTrue("应当成功，实际：${r.output}", r.ok)
        assertTrue(r.output.contains("hello from ptc"))
        // 最后一行表达式的值要作为返回值带回
        assertTrue(r.output.contains("返回值：2"))
    }

    @Test
    fun `沙箱里的工具真的能落盘并读回`() {
        val target = File(tmpDir, "a.txt")
        val code = """
            var w = tools.writeFile('${target.absolutePath.replace("\\", "/")}', 'BANANA-7742');
            if (!w.ok) { print('写失败: ' + w.output); }
            var r = tools.readFile('${target.absolutePath.replace("\\", "/")}');
            r.output
        """.trimIndent()

        val r = runCode(code)
        assertTrue("应当成功，实际：${r.output}", r.ok)
        assertTrue("脚本里应能看到读回的内容，实际：${r.output}", r.output.contains("BANANA-7742"))
        // 磁盘上也必须真的有
        assertTrue(target.exists())
        assertEquals("BANANA-7742", target.readText())
        // 内部调用统计应当出现两次
        assertTrue(r.output.contains("内部工具调用：2 次"))
    }

    @Test
    fun `被关闭的工具在沙箱里返回ok为false`() {
        AppSettings.enabledTools = setOf("write_file")   // 故意不含 read_file

        val r = runCode(
            """
            var r = tools.readFile('/whatever.txt');
            r.ok ? 'BAD-没拦住' : 'BLOCKED'
            """.trimIndent()
        )
        assertTrue(r.ok)                                   // 脚本本身没崩
        assertTrue("应当被拦下，实际：${r.output}", r.output.contains("BLOCKED"))
        assertFalse(r.output.contains("BAD-没拦住"))
    }

    /**
     * ★ 整个 PTC 的安全底线：ClassShutter 全禁之后，脚本碰不到任何 Java 类。
     *
     * 这里用 getProperty('user.home') 做探针 —— 不加 ClassShutter 的话它会
     * 老老实实返回用户主目录，加了就被拒。用一个无害的只读调用，
     * 而不是 Runtime.exec，是为了万一拦截失效也不会在测试机上真跑命令。
     */
    @Test
    fun `沙箱无法访问Java类`() {
        val r = runCode("Packages.java.lang.System.getProperty('user.home')")
        assertFalse("Java 类必须被彻底封锁，实际却执行成功了：${r.output}", r.ok)
        assertTrue(
            "错误信息应指向 Java 访问被拒，实际：${r.output}",
            r.output.contains("Java", ignoreCase = true) ||
                r.output.contains("prohibited", ignoreCase = true) ||
                r.output.contains("ClassShutter", ignoreCase = true)
        )
    }

    @Test
    fun `沙箱没有require和fetch`() {
        val r = runCode("typeof require")
        assertTrue(r.ok)
        // typeof 一个不存在的标识符不会抛错，返回 "undefined"
        assertTrue(r.output.contains("undefined"))

        val r2 = runCode("require('fs')")
        assertFalse("require 不该可用", r2.ok)
    }

    @Test
    fun `空代码和超长代码被挡在门外`() {
        assertFalse(runCode("").ok)
        assertFalse(runCode("   \n  ").ok)
        assertFalse(runCode("a".repeat(200_001)).ok)
    }

    @Test
    fun `脚本抛异常时ok为false但保留已发生的副作用`() {
        val target = File(tmpDir, "b.txt")
        val path = target.absolutePath.replace("\\", "/")
        val r = runCode(
            """
            tools.writeFile('$path', 'written-before-crash');
            throw new Error('boom');
            """.trimIndent()
        )
        assertFalse(r.ok)
        assertTrue(r.output.contains("boom"))
        // 关键：抛异常之前写下的文件不该被"回滚"——如实告诉模型已经发生了什么
        assertTrue("写操作应已生效", target.exists())
    }

    @Test
    fun `executeTool能识别run_code分支`() {
        val args = JSONObject().put("code", "print('via executeTool'); 'ok'").toString()
        val r = executeTool("run_code", args)
        assertTrue(r.ok)
        assertTrue(r.output.contains("via executeTool"))
    }

    @Test
    fun `缺少code参数时给出明确错误`() {
        val r = executeTool("run_code", JSONObject().toString())
        assertFalse(r.ok)
        assertTrue(r.output.contains("code"))
    }

    @Test
    fun `工作区越界在PTC模式下一样被拦`() {
        // 工作区是 tmpDir，去读 /etc 之类的地方应当被 workspaceViolation 拦下
        val outside = if (File.separatorChar == '\\') "C:/Windows/win.ini" else "/etc/hostname"
        val r = runCode("tools.readFile('$outside').ok ? 'BAD' : 'BLOCKED'")
        assertTrue(r.ok)
        assertTrue("工作区限制在 PTC 下必须继续生效，实际：${r.output}", r.output.contains("BLOCKED"))
    }

    @Test
    fun `返回的对象能被读出一层内容`() {
        val r = runCode("({ files: 3, ok: true })")
        assertTrue(r.ok)
        assertNotNull(r.output)
        assertTrue("对象应展开一层，实际：${r.output}", r.output.contains("files"))
    }

    // ==================== 执行前检查（方言护栏） ====================

    @Test
    fun `for const of 被拦下并提示改成let`() {
        val r = runCode("const a = [1,2]; let t = 0; for (const v of a) { t += v; } t")
        assertFalse("必须以语法错误拦下，而不是放过去", r.ok)
        assertTrue("应提示具体行号和改法，实际：${r.output}", r.output.contains("for (let"))
        assertTrue("应带上行号", r.output.contains("第 1 行"))
    }

    @Test
    fun `模板字符串插值被拦下`() {
        // 这是最危险的一条：引擎不报错，只是把 ${} 原样留下，算出来是错的
        val r = runCode("var x = 1; var s = `v=${'$'}{x}`; s")
        assertFalse("插值失效必须拦住，不能让它算出错值", r.ok)
        assertTrue("应提示用拼接，实际：${r.output}", r.output.contains("拼接"))
    }

    @Test
    fun `可选链和空值合并被拦下`() {
        assertFalse(runCode("var o = {a:1}; o?.a").ok)
        assertFalse(runCode("var z = null; z ?? 5").ok)
        assertFalse(runCode("var a = [1]; var b = [...a]; b.length").ok)
    }

    /**
     * ★ 护栏不能太激进：字符串和注释里出现的符号是普通文本，不该误报。
     *   这条测试保证 maskJsLiterals 真的起作用。
     */
    @Test
    fun `字符串和注释里的符号不误报`() {
        // 双引号里带 ?? 和 ...
        assertTrue(runCode("""var s = "a ?? b ... c"; s.length""").ok)
        // 单引号里带可选链
        assertTrue(runCode("""var s = 'x?.y'; s.length""").ok)
        // 行注释里带展开运算符
        assertTrue(runCode("// spread ... and ?? here\nvar n = 1; n").ok)
        // 块注释
        assertTrue(runCode("/* for (const x of y) */ var n = 2; n").ok)
    }

    @Test
    fun `合规的现代写法能正常跑`() {
        val r = runCode(
            """
            const files = ['a', 'b', 'c'];
            let out = [];
            for (let f of files) { out.push(f.toUpperCase()); }
            out.join('-')
            """.trimIndent()
        )
        assertTrue("应当通过，实际：${r.output}", r.ok)
        assertTrue(r.output.contains("A-B-C"))
    }
}

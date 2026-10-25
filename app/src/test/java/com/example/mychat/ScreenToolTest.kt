package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * screen_see / screen_tap 的护栏测试（纯 JVM，不需要真机和无障碍服务）。
 *
 * 这一层能测的只有「服务没连上时不许崩、必须给出可执行的引导」——
 * 真正的节点树遍历要靠真机（见 README 的验证记录）。
 * 但恰恰是这个分支最容易在线上被撞到：用户没开无障碍，模型每轮都在调这两个工具。
 * 一旦这里抛异常或返回空字符串，整轮对话就会被一个 NullPointerException 打断。
 *
 * 运行：./gradlew testDebugUnitTest
 */
class ScreenToolTest {

    // ---- 常量守卫：数字被改小会让模型看不到界面，改大则会撑爆上下文 ----

    @Test
    fun `返回字数上限固定为 2000`() {
        assertEquals(2000, SCREEN_SEE_MAX_CHARS)
    }

    @Test
    fun `快照超时固定为 2 秒`() {
        assertEquals(2000L, SCREEN_SEE_TIMEOUT_MS)
    }

    // ---- screen_tap 的参数校验 ----

    @Test
    fun `空文字被拒绝且提示缺 text`() {
        val r = screenTap("")
        assertFalse(r.ok)
        assertTrue(r.output.contains("缺少 text"))
    }

    @Test
    fun `纯空白也会被 trim 后拒绝`() {
        val r = screenTap("   ")
        assertFalse(r.ok)
        assertTrue(r.output.contains("缺少 text"))
    }

    // ---- 服务未连接时的降级（JVM 里 peek() 永远是 null） ----

    @Test
    fun `服务未连接时 screen_see 不崩且给出引导`() {
        val r = screenSee()
        assertFalse(r.ok)
        assertTrue(r.output.contains("无障碍服务当前没有连接"))
        assertTrue(r.output.contains("无障碍自动化"))
    }

    @Test
    fun `服务未连接时 screen_tap 不崩且给出引导`() {
        val r = screenTap("确定")
        assertFalse(r.ok)
        assertTrue(r.output.contains("无障碍服务当前没有连接"))
    }

    // ---- 分派器接线：工具名真的能路由到实现 ----

    @Test
    fun `executeTool 能把 screen_see 路由到实现`() {
        val r = executeTool("screen_see", "{}")
        assertFalse(r.ok)
        assertTrue(r.output.contains("无障碍服务"))
    }

    @Test
    fun `executeTool 能把 screen_tap 的参数传下去`() {
        val r = executeTool("screen_tap", "{\"text\":\"设置\"}")
        assertFalse(r.ok)
        assertTrue(r.output.contains("无障碍服务"))
    }
}

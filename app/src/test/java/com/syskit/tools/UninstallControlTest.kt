package com.syskit.tools

import com.syskit.tools.ops.ApkInstaller
import com.syskit.tools.ops.EmbeddedMagisk
import com.syskit.tools.ops.RateLimiter
import com.syskit.tools.ops.UninstallControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 卸载管控决策 / 限速器 / 安装通道选择 / 内嵌 Magisk 判定 —— 纯逻辑 */
class UninstallControlTest {

    // ---------- 决策矩阵 ----------

    @Test
    fun `禁止模式下所有卸载一律拒绝`() {
        assertEquals(UninstallControl.Action.DENY, UninstallControl.decide(UninstallControl.Mode.BLOCK_ALL, screenOn = true, rateAllowed = true))
        assertEquals(UninstallControl.Action.DENY, UninstallControl.decide(UninstallControl.Mode.BLOCK_ALL, screenOn = false, rateAllowed = false))
    }

    @Test
    fun `接管模式下亮屏且未超速则弹窗`() {
        assertEquals(UninstallControl.Action.ASK, UninstallControl.decide(UninstallControl.Mode.TAKEOVER, screenOn = true, rateAllowed = true))
    }

    @Test
    fun `接管模式下熄屏自动拒绝`() {
        assertEquals(UninstallControl.Action.DENY, UninstallControl.decide(UninstallControl.Mode.TAKEOVER, screenOn = false, rateAllowed = true))
    }

    @Test
    fun `接管模式下超频自动拒绝`() {
        assertEquals(UninstallControl.Action.DENY, UninstallControl.decide(UninstallControl.Mode.TAKEOVER, screenOn = true, rateAllowed = false))
    }

    @Test
    fun `关闭模式不应产生决策（fail-closed）`() {
        assertEquals(UninstallControl.Action.DENY, UninstallControl.decide(UninstallControl.Mode.OFF, screenOn = true, rateAllowed = true))
    }

    // ---------- 限速器 ----------

    private val W = 60_000L
    private val MAX = 3

    @Test
    fun `窗口内前 max 次允许`() {
        var s = RateLimiter.State()
        for (t in 0 until MAX) {
            val r = RateLimiter.evaluate(s, now = t * 1000L, windowMs = W, max = MAX)
            assertTrue("第 ${t + 1} 次应允许", r.allowed)
            s = r.next
        }
    }

    @Test
    fun `窗口内超过 max 次拒绝且尝试仍被记录`() {
        var s = RateLimiter.State()
        for (t in 0 until MAX) s = RateLimiter.evaluate(s, now = t * 1000L, windowMs = W, max = MAX).next
        val r = RateLimiter.evaluate(s, now = MAX * 1000L, windowMs = W, max = MAX)
        assertFalse("第 ${MAX + 1} 次应拒绝", r.allowed)
        assertEquals("被拒尝试也应计入窗口", MAX + 1, r.next.times.size)
    }

    @Test
    fun `窗口滑动后重新允许`() {
        var s = RateLimiter.State()
        for (t in 0 until MAX) s = RateLimiter.evaluate(s, now = t * 1000L, windowMs = W, max = MAX).next
        val r = RateLimiter.evaluate(s, now = W + 1, windowMs = W, max = MAX)
        assertTrue("窗口滑出后应允许", r.allowed)
    }

    @Test
    fun `一次性大量卸载触发限速`() {
        // 模拟批量脚本：1 秒内连发 8 次
        var s = RateLimiter.State()
        var denied = 0
        for (t in 0 until 8) {
            val r = RateLimiter.evaluate(s, now = t * 100L, windowMs = W, max = MAX)
            if (!r.allowed) denied++
            s = r.next
        }
        assertEquals(5, denied)
    }

    // ---------- 安装通道选择 ----------

    @Test
    fun `有 root 会话走静默安装`() {
        assertEquals(ApkInstaller.Path.ROOT_SILENT, ApkInstaller.choosePath(hasRootSession = true, hasInstallPackages = false))
    }

    @Test
    fun `特权安装权限走静默安装`() {
        assertEquals(ApkInstaller.Path.PRIV_SILENT, ApkInstaller.choosePath(hasRootSession = false, hasInstallPackages = true))
    }

    @Test
    fun `无特权走系统确认会话`() {
        assertEquals(ApkInstaller.Path.CONFIRM_SESSION, ApkInstaller.choosePath(hasRootSession = false, hasInstallPackages = false))
    }

    // ---------- 内嵌 Magisk 显示判定 ----------

    @Test
    fun `干净设备显示内嵌 Magisk 入口`() {
        assertTrue(EmbeddedMagisk.shouldShowEmbedded(magiskInstalled = false, hasRoot = false, isPrivApp = false))
    }

    @Test
    fun `已装 Magisk 隐藏入口`() {
        assertFalse(EmbeddedMagisk.shouldShowEmbedded(magiskInstalled = true, hasRoot = false, isPrivApp = false))
    }

    @Test
    fun `已有 root 隐藏入口`() {
        assertFalse(EmbeddedMagisk.shouldShowEmbedded(magiskInstalled = false, hasRoot = true, isPrivApp = false))
    }

    @Test
    fun `配套模块已部署（priv-app）隐藏入口`() {
        assertFalse(EmbeddedMagisk.shouldShowEmbedded(magiskInstalled = false, hasRoot = false, isPrivApp = true))
    }

    // ---------- Magisk 模块 zip 解析 ----------

    @Test
    fun `根目录模块清单解析出模块 id`() {
        val info = EmbeddedMagisk.parseModuleZip(
            entries = listOf("module.prop", "system/bin/pm", "customize.sh"),
            readText = { if (it == "module.prop") "id=syskit\nname=SysKit\nversion=v1.0.0" else null }
        )
        assertNotNull(info)
        assertEquals("syskit", info!!.id)
        assertEquals("", info.prefix)
    }

    @Test
    fun `单层目录嵌套的模块清单也能解析`() {
        val info = EmbeddedMagisk.parseModuleZip(
            entries = listOf("SomeModule/module.prop", "SomeModule/system/x"),
            readText = { if (it == "SomeModule/module.prop") "id=loser776\nversion=v2" else null }
        )
        assertNotNull(info)
        assertEquals("loser776", info!!.id)
        assertEquals("SomeModule/", info.prefix)
    }

    @Test
    fun `缺少模块清单或非法 id 返回 null`() {
        assertNull(EmbeddedMagisk.parseModuleZip(listOf("a.txt"), readText = { null }))
        assertNull(
            EmbeddedMagisk.parseModuleZip(
                listOf("module.prop"),
                readText = { "id=bad id!!" }
            )
        )
        assertNull(
            EmbeddedMagisk.parseModuleZip(
                listOf("module.prop"),
                readText = { "name=无 id 字段" }
            )
        )
    }

    // ---------- su 授权记录解析（宽松双格式） ----------

    @Test
    fun `解析 key=value 行`() {
        val rows = EmbeddedMagisk.parsePolicies("uid=10001|policy=2|until=0\nuid=10002|policy=0|until=0")
        assertEquals(2, rows.size)
        assertEquals(10001, rows[0].uid)
        assertEquals(2, rows[0].policy)
    }

    @Test
    fun `解析管道分隔行`() {
        val rows = EmbeddedMagisk.parsePolicies("10077|2|0\n10088|0|0")
        assertEquals(2, rows.size)
        assertEquals(10077, rows[0].uid)
        assertEquals(0, rows[1].policy)
    }

    @Test
    fun `空输出返回空列表`() {
        assertTrue(EmbeddedMagisk.parsePolicies("").isEmpty())
        assertTrue(EmbeddedMagisk.parsePolicies("no data here").isEmpty())
    }
}

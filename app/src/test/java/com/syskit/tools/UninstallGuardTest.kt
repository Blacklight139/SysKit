package com.syskit.tools

import com.syskit.tools.ops.UninstallGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 卸载守卫纯逻辑：输入校验 / 决策 / 事件过滤 / 恢复计划 / 文件协议 */
class UninstallGuardTest {

    // ---------- 包名校验（root 命令注入防线） ----------

    @Test
    fun `合法包名通过校验`() {
        assertTrue(UninstallGuard.validPkg("com.android.vending"))
        assertTrue(UninstallGuard.validPkg("a.b"))
        assertTrue(UninstallGuard.validPkg("com.xiaomi.market"))
    }

    @Test
    fun `包含 shell 元字符的包名被拒绝`() {
        assertFalse(UninstallGuard.validPkg("com.a;reboot"))
        assertFalse(UninstallGuard.validPkg("com.a && id"))
        assertFalse(UninstallGuard.validPkg("com.a\$(id)"))
        assertFalse(UninstallGuard.validPkg("com.a|cat"))
        assertFalse(UninstallGuard.validPkg("com a"))
        assertFalse(UninstallGuard.validPkg("com.a\nb"))
        assertFalse(UninstallGuard.validPkg(""))
        assertFalse(UninstallGuard.validPkg("."))
        assertFalse(UninstallGuard.validPkg("com.a'x"))
    }

    @Test
    fun `超长包名被拒绝`() {
        assertFalse(UninstallGuard.validPkg("a".repeat(201)))
        assertTrue(UninstallGuard.validPkg("a".repeat(200)))
    }

    // ---------- 请求 ID 校验 ----------

    @Test
    fun `合法请求 ID 通过校验`() {
        assertTrue(UninstallGuard.validId("12345_1698000000"))
        assertTrue(UninstallGuard.validId("7"))
    }

    @Test
    fun `非法请求 ID 被拒绝`() {
        assertFalse(UninstallGuard.validId("123;rm"))
        assertFalse(UninstallGuard.validId("abc"))
        assertFalse(UninstallGuard.validId("a b"))
        assertFalse(UninstallGuard.validId(""))
        assertFalse(UninstallGuard.validId("1.2"))
    }

    // ---------- 决策：亮屏询问，熄屏自动拦截 ----------

    @Test
    fun `熄屏自动拦截`() {
        assertEquals(UninstallGuard.Action.BLOCK, UninstallGuard.decide(screenOn = false))
    }

    @Test
    fun `亮屏弹出对话框询问`() {
        assertEquals(UninstallGuard.Action.ASK, UninstallGuard.decide(screenOn = true))
    }

    // ---------- REMOVED 事件过滤 ----------

    @Test
    fun `升级替换事件不处理`() {
        assertFalse(UninstallGuard.shouldReactTo("com.a", selfPkg = "com.syskit.tools", replacing = true, allowedUntil = 0L, now = 100L))
    }

    @Test
    fun `自身卸载不处理`() {
        assertFalse(UninstallGuard.shouldReactTo("com.syskit.tools", selfPkg = "com.syskit.tools", replacing = false, allowedUntil = 0L, now = 100L))
    }

    @Test
    fun `刚授权过的卸载不处理`() {
        assertFalse(UninstallGuard.shouldReactTo("com.a", selfPkg = "com.syskit.tools", replacing = false, allowedUntil = 200L, now = 100L))
    }

    @Test
    fun `普通卸载需要处理`() {
        assertTrue(UninstallGuard.shouldReactTo("com.a", selfPkg = "com.syskit.tools", replacing = false, allowedUntil = 0L, now = 100L))
    }

    // ---------- 恢复计划 ----------

    @Test
    fun `系统应用优先 install-existing 恢复`() {
        val plan = UninstallGuard.restorePlan("com.a", hasBackup = false)
        assertNotNull(plan)
        assertEquals("pm install-existing com.a", plan!!.primary)
        assertNull(plan.fallback)
    }

    @Test
    fun `有备份时回退为 install-multiple 重装`() {
        val plan = UninstallGuard.restorePlan("com.a", hasBackup = true)
        assertNotNull(plan)
        assertEquals("pm install-existing com.a", plan!!.primary)
        assertTrue(plan.fallback!!.contains("backups/com.a"))
        assertTrue(plan.fallback!!.contains("pm install-multiple -r"))
    }

    @Test
    fun `非法包名不生成恢复命令`() {
        assertNull(UninstallGuard.restorePlan("com.a;reboot", hasBackup = true))
    }

    // ---------- 文件协议 ----------

    @Test
    fun `请求与响应文件名`() {
        assertEquals("req_123_456", UninstallGuard.reqFileName("123_456"))
        assertEquals("resp_123_456", UninstallGuard.respFileName("123_456"))
        assertNull(UninstallGuard.respFileName("abc;rm"))
    }

    @Test
    fun `轮询命令包含目录扫描与清理`() {
        val cmd = UninstallGuard.pollCmd()
        assertTrue(cmd.contains("/data/local/tmp/syskit"))
        assertTrue(cmd.contains("req_"))
    }

    @Test
    fun `响应命令只写入固定字面量`() {
        assertEquals(
            "printf ALLOW > /data/local/tmp/syskit/resp_1_2",
            UninstallGuard.respCmd("1_2", allow = true)
        )
        assertEquals(
            "printf DENY > /data/local/tmp/syskit/resp_1_2",
            UninstallGuard.respCmd("1_2", allow = false)
        )
        assertNull(UninstallGuard.respCmd("1;rm", allow = false))
    }

    @Test
    fun `解析轮询行`() {
        val req = UninstallGuard.parsePollLine("SYSKIT_REQ|req_123_456|com.android.vending")
        assertNotNull(req)
        assertEquals("123_456", req!!.id)
        assertEquals("com.android.vending", req.pkg)
    }

    @Test
    fun `非法轮询行被忽略`() {
        assertNull(UninstallGuard.parsePollLine("SYSKIT_REQ|req_123|com.a;reboot"))
        assertNull(UninstallGuard.parsePollLine("random output"))
        assertNull(UninstallGuard.parsePollLine("SYSKIT_REQ|badprefix|com.a"))
    }

    // ---------- 批量保护（接管系统卸载：频繁/一次性大量卸载自动禁止） ----------

    @Test
    fun `三次尝试触发批量保护`() {
        UninstallGuard.resetBulk()
        val now = 1_000_000L
        assertFalse(UninstallGuard.recordAttempt(now))
        assertFalse(UninstallGuard.recordAttempt(now + 1_000))
        assertTrue(UninstallGuard.recordAttempt(now + 2_000))
        assertTrue(UninstallGuard.isBulkBlocked(now + 2_000))
    }

    @Test
    fun `两次尝试不触发`() {
        UninstallGuard.resetBulk()
        val now = 2_000_000L
        assertFalse(UninstallGuard.recordAttempt(now))
        assertFalse(UninstallGuard.recordAttempt(now + 5_000))
        assertFalse(UninstallGuard.isBulkBlocked(now + 5_000))
    }

    @Test
    fun `超出时间窗的尝试不累计`() {
        UninstallGuard.resetBulk()
        val now = 3_000_000L
        assertFalse(UninstallGuard.recordAttempt(now))
        assertFalse(UninstallGuard.recordAttempt(now + 61_000))
        assertFalse(UninstallGuard.recordAttempt(now + 122_000))
        assertFalse(UninstallGuard.isBulkBlocked(now + 122_000))
    }

    @Test
    fun `冷却期结束自动解除`() {
        UninstallGuard.resetBulk()
        val now = 4_000_000L
        UninstallGuard.recordAttempt(now)
        UninstallGuard.recordAttempt(now + 1_000)
        assertTrue(UninstallGuard.recordAttempt(now + 2_000))
        assertTrue(UninstallGuard.isBulkBlocked(now + 2_000 + 9 * 60_000))
        assertFalse(UninstallGuard.isBulkBlocked(now + 2_000 + 10 * 60_000 + 1_000))
        assertFalse(UninstallGuard.recordAttempt(now + 2_000 + 10 * 60_000 + 2_000))
    }

    @Test
    fun `resetBulk 清空保护状态`() {
        UninstallGuard.resetBulk()
        val now = 5_000_000L
        UninstallGuard.recordAttempt(now)
        UninstallGuard.recordAttempt(now + 1_000)
        UninstallGuard.recordAttempt(now + 2_000)
        UninstallGuard.resetBulk()
        assertFalse(UninstallGuard.isBulkBlocked(now + 3_000))
    }

    // ---------- guard_state 模式命令 ----------

    @Test
    fun `guard_state 命令支持 0_1_2`() {
        assertEquals("printf 0 > /data/local/tmp/syskit/guard_state", UninstallGuard.guardStateCmd(0))
        assertEquals("printf 1 > /data/local/tmp/syskit/guard_state", UninstallGuard.guardStateCmd(1))
        assertEquals("printf 2 > /data/local/tmp/syskit/guard_state", UninstallGuard.guardStateCmd(2))
    }

    @Test
    fun `非法模式不生成命令`() {
        assertNull(UninstallGuard.guardStateCmd(3))
        assertNull(UninstallGuard.guardStateCmd(-1))
    }
}

package com.syskit.tools

import com.syskit.tools.ops.StoreGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 商店守卫命令构建与结果判定 */
class StoreGuardTest {

    @Test
    fun `守卫命令覆盖吊销与 appops 双通道`() {
        val cmds = StoreGuard.guardCommands("com.android.vending")
        assertEquals(4, cmds.size)
        assertEquals(
            "pm revoke com.android.vending android.permission.REQUEST_DELETE_PACKAGES",
            cmds[0]
        )
        assertEquals(
            "pm revoke com.android.vending android.permission.DELETE_PACKAGES",
            cmds[1]
        )
        assertEquals(
            "appops set com.android.vending REQUEST_DELETE_PACKAGES deny",
            cmds[2]
        )
        assertEquals(
            "appops set com.android.vending DELETE_PACKAGES deny",
            cmds[3]
        )
    }

    @Test
    fun `恢复命令包含重授予与 appops 复位`() {
        val cmds = StoreGuard.restoreCommands("com.xiaomi.market")
        assertTrue(cmds.any { it == "pm grant com.xiaomi.market android.permission.REQUEST_DELETE_PACKAGES" })
        assertTrue(cmds.any { it == "appops reset com.xiaomi.market" })
    }

    @Test
    fun `REQUEST_DELETE_PACKAGES 任一生效即判定守卫成功`() {
        val results = listOf(
            StoreGuard.CmdResult("pm revoke p android.permission.REQUEST_DELETE_PACKAGES", true, ""),
            StoreGuard.CmdResult("pm revoke p android.permission.DELETE_PACKAGES", false, "not runtime")
        )
        assertTrue(StoreGuard.guarded(results))
    }

    @Test
    fun `仅 DELETE_PACKAGES 生效不判定成功`() {
        val results = listOf(
            StoreGuard.CmdResult("pm revoke p android.permission.REQUEST_DELETE_PACKAGES", false, "unknown"),
            StoreGuard.CmdResult("appops set p DELETE_PACKAGES deny", true, "")
        )
        assertFalse(StoreGuard.guarded(results))
    }

    @Test
    fun `全部失败判定未守卫`() {
        val results = StoreGuard.CmdResult::class.let { emptyList<StoreGuard.CmdResult>() }
        assertFalse(StoreGuard.guarded(results))
    }

    @Test
    fun `已知商店清单无重复包名`() {
        val pkgs = StoreGuard.KNOWN_STORES.map { it.first }
        assertEquals(pkgs.size, pkgs.distinct().size)
    }
}

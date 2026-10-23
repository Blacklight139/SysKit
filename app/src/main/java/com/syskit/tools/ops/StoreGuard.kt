package com.syskit.tools.ops

import android.content.Context
import android.content.pm.PackageManager
import com.syskit.tools.root.SuSession

/**
 * 应用商店卸载能力禁用（root）。
 *
 * 分层冗余（任意一层生效即视为成功）：
 *   1. pm revoke REQUEST_DELETE_PACKAGES（运行时权限，商店由此发起系统卸载流程）
 *   2. pm revoke DELETE_PACKAGES（特权权限，尽力而为，可能不支持 revoke）
 *   3. appops set REQUEST_DELETE_PACKAGES deny（应用操作层面封死）
 *   4. appops set DELETE_PACKAGES deny（同上，尽力而为）
 * 商店升级后可能重新申请权限 → 由 BootReceiver 开机重应用兜底。
 */
object StoreGuard {

    data class StoreInfo(val pkg: String, val label: String)
    data class CmdResult(val cmd: String, val ok: Boolean, val output: String)

    /** 常见系统/第三方应用商店 */
    val KNOWN_STORES: List<Pair<String, String>> = listOf(
        "com.android.vending" to "Google Play 商店",
        "com.xiaomi.market" to "小米应用商店",
        "com.huawei.appmarket" to "华为应用市场",
        "com.hihonor.appmarket" to "荣耀应用市场",
        "com.heytap.market" to "OPPO 软件商店",
        "com.bbk.appstore" to "vivo 应用商店",
        "com.tencent.android.qqdownloader" to "腾讯应用宝",
        "com.baidu.appsearch" to "百度手机助手",
        "com.qihoo.appstore" to "360 手机助手",
        "com.wandoujia.phoenix2" to "豌豆荚",
        "com.meizu.mstore" to "魅族应用商店",
        "com.lenovo.leos.appstore" to "联想应用中心",
        "com.sec.android.app.samsungapps" to "三星 Galaxy Store",
    )

    fun guardCommands(pkg: String): List<String> = listOf(
        "pm revoke $pkg android.permission.REQUEST_DELETE_PACKAGES",
        "pm revoke $pkg android.permission.DELETE_PACKAGES",
        "appops set $pkg REQUEST_DELETE_PACKAGES deny",
        "appops set $pkg DELETE_PACKAGES deny",
    )

    fun restoreCommands(pkg: String): List<String> = listOf(
        "pm grant $pkg android.permission.REQUEST_DELETE_PACKAGES",
        "appops set $pkg REQUEST_DELETE_PACKAGES allow",
        "appops set $pkg DELETE_PACKAGES allow",
        "appops reset $pkg",
    )

    /** 对单个商店执行守卫命令，逐条返回结果 */
    fun applyTo(su: SuSession, pkg: String): List<CmdResult> =
        guardCommands(pkg).map { cmd ->
            val r = su.exec(cmd)
            CmdResult(cmd, r?.ok == true, r?.output ?: "超时或会话不可用")
        }

    /** 恢复单个商店的卸载能力 */
    fun restoreTo(su: SuSession, pkg: String): List<CmdResult> =
        restoreCommands(pkg).map { cmd ->
            val r = su.exec(cmd)
            CmdResult(cmd, r?.ok == true, r?.output ?: "超时或会话不可用")
        }

    /** 是否有效禁用（REQUEST_DELETE_PACKAGES 的 revoke/appops 任一生效） */
    fun guarded(results: List<CmdResult>): Boolean =
        results.any { it.ok && it.cmd.contains("REQUEST_DELETE_PACKAGES") }

    /** 查询某商店当前是否处于禁用状态（appops get 输出包含 deny） */
    fun queryGuarded(su: SuSession, pkg: String): Boolean {
        val r = su.exec("appops get $pkg REQUEST_DELETE_PACKAGES") ?: return false
        return r.output.contains("deny", ignoreCase = true)
    }

    /** 检测已安装的商店（已知清单 + 用户自定义） */
    fun detectInstalled(ctx: Context, extra: Set<String> = emptySet()): List<StoreInfo> {
        val pm = ctx.packageManager
        val pkgs = (KNOWN_STORES.map { it.first } + extra).distinct()
        return pkgs.mapNotNull { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                StoreInfo(pkg, KNOWN_STORES.firstOrNull { it.first == pkg }?.second ?: pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
}

package com.syskit.tools.ops

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import com.syskit.tools.root.SuSession

/** 通用结果 */
data class OpOutcome(val ok: Boolean, val message: String)

/**
 * 系统操作层：ADB / 开发者选项。
 *
 * 三层权限模型：
 *  L1  WRITE_SECURE_SETTINGS（pm grant 或系统应用）→ 直接走 Settings API，无需 root
 *  L2  系统特权应用（/system/priv-app）            → 同 L1，且自身不可常规卸载
 *  L3  Magisk root                                 → 全部能力（settings put / setprop / pm / appops / dpm）
 */
object SystemOps {

    const val ADB_ENABLED_KEY = "adb_enabled"
    const val DEV_SETTINGS_ENABLED_KEY = "development_settings_enabled"
    const val TCP_ADB_PORT = 5555

    // ---------- 权限检测 ----------

    fun hasWriteSecureSettings(ctx: Context): Boolean = try {
        ctx.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    fun isSystemApp(ctx: Context): Boolean = try {
        (ctx.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (e: Exception) {
        false
    }

    fun isPrivApp(ctx: Context): Boolean {
        val dir = ctx.applicationInfo.sourceDir ?: return false
        return listOf(
            "/system/priv-app/", "/product/priv-app/",
            "/system_ext/priv-app/", "/vendor/priv-app/"
        ).any { dir.startsWith(it) }
    }

    fun installPath(ctx: Context): String = ctx.applicationInfo.sourceDir ?: "?"

    // ---------- 读取（无需任何权限） ----------

    fun getAdbEnabled(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, ADB_ENABLED_KEY, 0) == 1

    fun getDevOptionsEnabled(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, DEV_SETTINGS_ENABLED_KEY, 0) == 1

    // ---------- L1/L2：Settings API 写入 ----------

    /** 直接开关 ADB 调试（系统会自动重启 adbd）。需 WRITE_SECURE_SETTINGS。 */
    fun setAdbEnabledApi(ctx: Context, on: Boolean): Boolean = try {
        Settings.Global.putInt(ctx.contentResolver, ADB_ENABLED_KEY, if (on) 1 else 0)
    } catch (e: Exception) {
        false
    }

    /** 开关开发者选项。需 WRITE_SECURE_SETTINGS。 */
    fun setDevOptionsEnabledApi(ctx: Context, on: Boolean): Boolean = try {
        Settings.Global.putInt(ctx.contentResolver, DEV_SETTINGS_ENABLED_KEY, if (on) 1 else 0)
    } catch (e: Exception) {
        false
    }

    // ---------- L3：root 命令（单行，供 su 会话执行） ----------

    fun cmdSetAdb(on: Boolean) = "settings put global $ADB_ENABLED_KEY ${if (on) 1 else 0}"
    fun cmdSetDevOptions(on: Boolean) = "settings put global $DEV_SETTINGS_ENABLED_KEY ${if (on) 1 else 0}"
    fun cmdGetUsbConfig() = "getprop persist.sys.usb.config"
    fun cmdSetUsbConfig(cfg: String) = "setprop persist.sys.usb.config '$cfg'"
    fun cmdGetTcpAdb() = "getprop persist.adb.tcp.port"
    fun cmdSetTcpAdb(on: Boolean) = "setprop persist.adb.tcp.port ${if (on) TCP_ADB_PORT else -1}"
    fun cmdRestartAdbd() = "stop adbd && start adbd"

    /**
     * 把 adb 合并进当前 USB 持久配置（纯函数，可测试，输出始终规整）。
     * 例：null/""/"none" → "mtp,adb"；"mtp" → "mtp,adb"；"mtp,adb" → 原样。
     */
    fun mergeAdbIntoUsbConfig(current: String?): String {
        val cur = current?.trim()?.takeIf { it.isNotEmpty() && it != "none" } ?: return "mtp,adb"
        val parts = cur.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if ("adb" in parts) parts.joinToString(",") else (parts + "adb").joinToString(",")
    }

    // ---------- L3：root 执行 ----------

    fun setAdbEnabledRoot(su: SuSession, on: Boolean): Boolean =
        su.exec(cmdSetAdb(on))?.ok == true

    fun setDevOptionsEnabledRoot(su: SuSession, on: Boolean): Boolean =
        su.exec(cmdSetDevOptions(on))?.ok == true

    /** 固化：保证 USB 插入即有 adb（persist.sys.usb.config） */
    fun persistUsbAdb(su: SuSession): OpOutcome {
        val cur = su.exec(cmdGetUsbConfig())?.output
            ?: return OpOutcome(false, "读取 USB 配置失败（root 会话不可用）")
        val merged = mergeAdbIntoUsbConfig(cur)
        if (merged == cur) return OpOutcome(true, "USB 配置已包含 adb：$cur")
        val r = su.exec(cmdSetUsbConfig(merged)) ?: return OpOutcome(false, "setprop 超时")
        return if (r.ok) OpOutcome(true, "USB 配置已固化为 $merged")
        else OpOutcome(false, "setprop 失败：${r.output}")
    }

    /** 无线 ADB（TCP 5555），设置后重启 adbd 立即生效 */
    fun setTcpAdb(su: SuSession, on: Boolean): OpOutcome {
        val r1 = su.exec(cmdSetTcpAdb(on)) ?: return OpOutcome(false, "setprop 超时")
        if (!r1.ok) return OpOutcome(false, "设置端口失败：${r1.output}")
        val r2 = su.exec(cmdRestartAdbd()) ?: return OpOutcome(false, "重启 adbd 超时")
        if (!r2.ok) return OpOutcome(false, "重启 adbd 失败：${r2.output}")
        return OpOutcome(true, if (on) "无线 ADB 已开启，端口 $TCP_ADB_PORT（adb connect <设备IP>）" else "无线 ADB 已关闭")
    }

    fun isTcpAdbOn(su: SuSession): Boolean {
        val v = su.exec(cmdGetTcpAdb())?.output ?: return false
        return v.isNotEmpty() && v != "-1" && v != "0"
    }
}

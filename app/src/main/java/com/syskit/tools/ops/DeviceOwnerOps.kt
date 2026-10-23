package com.syskit.tools.ops

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.syskit.tools.admin.AdminReceiver
import com.syskit.tools.root.SuSession

/**
 * 设备所有者增强（可选）：
 * 成为 Device Owner 后可通过 DevicePolicyManager.setUninstallBlocked 彻底封死卸载，
 * 并使本应用自身不可被卸载（含商店、桌面、pm uninstall --user 0）。
 *
 * 注意：已配好账户/已激活的设备上 `dpm set-device-owner` 通常会被系统拒绝，
 * 失败不影响其余功能（商店守卫 + appops 仍有效）。
 */
object DeviceOwnerOps {

    private fun dpm(ctx: Context): DevicePolicyManager =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun isDeviceOwner(ctx: Context): Boolean =
        try { dpm(ctx).isDeviceOwnerApp(ctx.packageName) } catch (e: Exception) { false }

    /** root 方式注册设备所有者 */
    fun becomeDeviceOwner(ctx: Context, su: SuSession): OpOutcome {
        if (isDeviceOwner(ctx)) return OpOutcome(true, "已是设备所有者")
        val r = su.exec("dpm set-device-owner ${ctx.packageName}/${AdminReceiver::class.java.name}")
            ?: return OpOutcome(false, "dpm 命令超时")
        val out = r.output
        return if (r.ok && out.contains("uccess", ignoreCase = true)) {
            OpOutcome(true, "设备所有者设置成功")
        } else {
            OpOutcome(false, "设置失败：${out.ifBlank { "未知错误（设备可能已配置账户）" }}")
        }
    }

    /** 应用自身无需 root 即可注销设备所有者 */
    fun clearDeviceOwner(ctx: Context): Boolean = try {
        dpm(ctx).clearDeviceOwnerApp(ctx.packageName)
        true
    } catch (e: Exception) {
        false
    }

    /** 禁止/允许卸载指定包（需设备所有者） */
    fun setUninstallBlocked(ctx: Context, pkg: String, blocked: Boolean): Boolean = try {
        dpm(ctx).setUninstallBlocked(admin(ctx), pkg, blocked)
        true
    } catch (e: Exception) {
        false
    }
}

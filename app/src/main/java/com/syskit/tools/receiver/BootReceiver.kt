package com.syskit.tools.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.syskit.tools.log.OpsLog
import com.syskit.tools.ops.DeviceOwnerOps
import com.syskit.tools.ops.StoreGuard
import com.syskit.tools.ops.SystemOps
import com.syskit.tools.prefs.AppPrefs
import com.syskit.tools.root.SuSession

/**
 * 开机 watchdog：重新应用所有持久化配置。
 *
 * - L1/L2（WRITE_SECURE_SETTINGS）路径无需 root 即可重应用 ADB / 开发者选项
 * - L3（root）路径静默尝试（Magisk 已记住授权则自动成功，否则跳过并记录）
 * - 设备所有者状态下重新封死自身卸载
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = AppPrefs(context)
        if (!prefs.adbLock && !prefs.devOptions && !prefs.storeGuard) return

        val pending = goAsync()
        Thread {
            try {
                OpsLog.log("开机：重新应用持久配置")
                val hasWss = SystemOps.hasWriteSecureSettings(context)

                // L1/L2 路径：无需 root
                if (prefs.adbLock && hasWss) {
                    if (SystemOps.setAdbEnabledApi(context, true)) OpsLog.log("开机：ADB 已重开（系统权限路径）")
                }
                if (prefs.devOptions && hasWss) {
                    SystemOps.setDevOptionsEnabledApi(context, true)
                }

                // L3 路径：需要 root（Magisk 需记住授权）
                val su = SuSession.acquire(timeoutMs = 12_000)
                if (su != null) {
                    if (prefs.adbLock) {
                        if (!hasWss) SystemOps.setAdbEnabledRoot(su, true)
                        OpsLog.log("开机：固化 ADB → ${SystemOps.persistUsbAdb(su).message}")
                    }
                    if (prefs.devOptions) {
                        SystemOps.setDevOptionsEnabledRoot(su, true)
                        OpsLog.log("开机：开发者选项已保持开启")
                    }
                    if (prefs.storeGuard) {
                        StoreGuard.detectInstalled(context, prefs.customStores).forEach { st ->
                            val results = StoreGuard.applyTo(su, st.pkg)
                            OpsLog.log(
                                "开机：商店守卫 [${st.label}] " +
                                    if (StoreGuard.guarded(results)) "已重应用" else "重应用失败"
                            )
                        }
                    }
                } else {
                    OpsLog.log("开机：无可用 root（未记住授权？），部分配置未重应用")
                }

                // 设备所有者：重新封死自身卸载
                if (DeviceOwnerOps.isDeviceOwner(context)) {
                    if (DeviceOwnerOps.setUninstallBlocked(context, context.packageName, true)) {
                        OpsLog.log("开机：自身卸载保护（设备所有者）已重应用")
                    }
                }
            } catch (e: Exception) {
                OpsLog.log("开机处理异常：${e.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}

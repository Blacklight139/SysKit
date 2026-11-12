package com.syskit.tools.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.syskit.tools.log.OpsLog
import com.syskit.tools.ops.DeviceOwnerOps
import com.syskit.tools.ops.SystemOps
import com.syskit.tools.prefs.AppPrefs
import com.syskit.tools.root.SuSession
import com.syskit.tools.service.UninstallGuardService

/**
 * 开机 watchdog：重新应用所有持久化配置。
 *
 * - L1/L2（WRITE_SECURE_SETTINGS）路径无需 root 即可重应用 ADB / 开发者选项
 * - L3（root）路径静默尝试（Magisk 已记住授权则自动成功，否则跳过并记录）
 * - 卸载管控开启时启动看板狗（服务自行重建 guard_state 与 root 会话）
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = AppPrefs(context)
        if (!prefs.adbLock && !prefs.devOptions && !prefs.blockAll && !prefs.takeover) return

        // 卸载管控：开机即启动看板狗（无需 root；root 会话与 guard_state 由服务自行恢复）
        if (prefs.blockAll || prefs.takeover) {
            runCatching { UninstallGuardService.start(context) }
        }

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

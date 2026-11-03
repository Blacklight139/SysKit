package com.syskit.tools.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.syskit.tools.MainActivity
import com.syskit.tools.R
import com.syskit.tools.log.OpsLog
import com.syskit.tools.ops.UninstallGuard
import com.syskit.tools.root.SuSession
import com.syskit.tools.ui.ConsentDialogActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 卸载守卫看板狗（前台服务，specialUse）。
 *
 * - 轮询 pm 包装器请求：熄屏自动拒绝；亮屏由包装器拉起的对话框等待用户
 * - 监听 PACKAGE_REMOVED：熄屏自动恢复；亮屏弹窗询问（30s 默认恢复）
 * - 启动后全量备份 /data/app 应用 APK；新装包自动补备份
 */
class UninstallGuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiver: BroadcastReceiver? = null
    private val labels = ConcurrentHashMap<String, String>()

    companion object {
        private const val CHANNEL_ID = "uninstall_guard"
        private const val NOTIF_ID = 42

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, UninstallGuardService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, UninstallGuardService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        registerPackageReceiver()
        OpsLog.log("卸载守卫：看板狗已启动")
        scope.launch {
            backupAllInstalled()
            pollLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        scope.cancel()
        OpsLog.log("卸载守卫：看板狗已停止")
        super.onDestroy()
    }

    // ---------- 轮询 pm 包装器请求 ----------

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val su = runCatching { SuSession.acquire(timeoutMs = 8_000) }.getOrNull()
            if (su != null) {
                runCatching {
                    UninstallGuard.scanRequests(su).forEach { req ->
                        if (!screenOn()) {
                            UninstallGuard.respond(su, req.id, req.pkg, allow = false)
                            OpsLog.log("卸载守卫：熄屏自动拦截卸载请求 [${req.pkg}]")
                        } else {
                            OpsLog.log("卸载守卫：卸载请求等待用户确认 [${req.pkg}]（30s 无响应自动拒绝）")
                        }
                    }
                }
            }
            delay(1_000)
        }
    }

    // ---------- 包事件 ----------

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED -> if (!replacing) scope.launch {
                        delay(4_000)
                        backupOne(pkg)
                    }
                    Intent.ACTION_PACKAGE_REMOVED -> scope.launch { handleRemoved(pkg, replacing) }
                }
            }
        }
        receiver = r
        ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private suspend fun handleRemoved(pkg: String, replacing: Boolean) {
        val now = System.currentTimeMillis()
        if (!UninstallGuard.shouldReactTo(pkg, packageName, replacing, UninstallGuard.allowedUntil(pkg), now)) return
        val su = runCatching { SuSession.acquire(8_000) }.getOrNull() ?: return
        val label = appLabel(pkg)
        if (!screenOn()) {
            val ok = UninstallGuard.restoreNow(su, pkg)
            OpsLog.log("卸载守卫：熄屏检测到 [$label] 被卸载 → ${if (ok) "已自动拦截并恢复" else "恢复失败（无可用备份）"}")
            notifyIntercepted(label, ok)
        } else {
            val hasBackup = UninstallGuard.hasBackup(su, pkg)
            OpsLog.log("卸载守卫：检测到 [$label] 被卸载（备份可用=$hasBackup）→ 弹窗询问")
            val i = Intent(this, ConsentDialogActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ConsentDialogActivity.EXTRA_MODE, "removed")
                putExtra(ConsentDialogActivity.EXTRA_PKG, pkg)
                putExtra(ConsentDialogActivity.EXTRA_LABEL, label)
            }
            if (!runCatching { startActivity(i) }.isSuccess) {
                val ok = UninstallGuard.restoreNow(su, pkg)
                OpsLog.log("卸载守卫：确认框拉起失败 → ${if (ok) "已自动恢复" else "恢复失败（无可用备份）"}")
                notifyIntercepted(label, ok)
            }
        }
    }

    // ---------- 备份 ----------

    private suspend fun backupAllInstalled() {
        val su = runCatching { SuSession.acquire(20_000) }.getOrNull() ?: run {
            OpsLog.log("卸载守卫：备份跳过（无可用 root 会话，Magisk 未记住授权？）")
            return
        }
        val targets = packageManager.getInstalledPackages(0)
            .mapNotNull { it.applicationInfo }
            .filter { it.sourceDir?.startsWith("/data/app") == true }
            .map { it.packageName }
            .filter { UninstallGuard.validPkg(it) }
        refreshLabels()
        OpsLog.log("卸载守卫：开始备份 ${targets.size} 个 /data/app 应用的 APK")
        var done = 0
        for (p in targets) {
            if (!scope.isActive) return
            UninstallGuard.backupNow(su, p)
            done++
            if (done % 10 == 0) OpsLog.log("卸载守卫：备份进度 $done/${targets.size}")
        }
        OpsLog.log("卸载守卫：备份完成 $done/${targets.size}（目录 ${UninstallGuard.BACKUP_DIR}）")
    }

    private fun backupOne(pkg: String) {
        if (!UninstallGuard.validPkg(pkg)) return
        val ai = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return
        if (ai.sourceDir?.startsWith("/data/app") != true) return
        val su = runCatching { SuSession.acquire(8_000) }.getOrNull() ?: return
        if (UninstallGuard.backupNow(su, pkg)) OpsLog.log("卸载守卫：已备份新装应用 [$pkg]")
    }

    // ---------- 辅助 ----------

    private fun screenOn(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    private fun appLabel(pkg: String): String {
        labels[pkg]?.let { return it }
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun refreshLabels() {
        packageManager.getInstalledPackages(0).forEach { pi ->
            runCatching {
                pi.applicationInfo?.let {
                    labels[pi.packageName] = packageManager.getApplicationLabel(it).toString()
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "卸载守卫", NotificationManager.IMPORTANCE_LOW).apply {
                description = "卸载守卫运行状态与拦截通知"
            }
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SysKit 卸载守卫运行中")
            .setContentText("所有卸载须经确认：亮屏弹窗询问 / 熄屏自动拦截")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun notifyIntercepted(label: String, ok: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("卸载守卫：已自动拦截卸载")
            .setContentText("[$label] ${if (ok) "已自动恢复" else "恢复失败（无可用备份）"}")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 20_000).toInt() + 100, n)
    }
}

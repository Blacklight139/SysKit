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
import com.syskit.tools.log.GuardEventLog
import com.syskit.tools.log.OpsLog
import com.syskit.tools.ops.RateLimiter
import com.syskit.tools.ops.UninstallControl
import com.syskit.tools.ops.UninstallGuard
import com.syskit.tools.prefs.AppPrefs
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
 * 卸载管控看板狗（前台服务，specialUse）。
 *
 * 模式（UninstallControl.modeOf）：BLOCK_ALL=所有卸载一律拒绝；TAKEOVER=单次弹窗、频繁/批量自动禁止。
 * - 轮询 pm 包装器请求：按模式决策（禁止/熄屏/超频 → 自动拒绝并记入 GuardEventLog；亮屏单次 → 弹窗）
 * - 监听 PACKAGE_REMOVED：被拒卸载自动恢复（系统应用 install-existing，第三方从备份重装）
 * - 启动后全量备份 /data/app 应用 APK；新装包自动补备份
 */
class UninstallGuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiver: BroadcastReceiver? = null
    private val labels = ConcurrentHashMap<String, String>()
    private var mode = UninstallControl.Mode.OFF
    private var rateState = RateLimiter.State()

    companion object {
        private const val CHANNEL_ID = "uninstall_guard"
        private const val NOTIF_ID = 42

        /** 限速阈值：60 秒窗口内 ≥3 次卸载尝试后自动禁止（被拒尝试同样计数） */
        const val RATE_WINDOW_MS = 60_000L
        const val RATE_MAX = 3

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
        GuardEventLog.init(filesDir)
        startForeground(NOTIF_ID, buildNotification())
        registerPackageReceiver()
        OpsLog.log("卸载管控：看板狗已启动")
        scope.launch { initGuard() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mode = UninstallControl.modeOf(AppPrefs(this).blockAll, AppPrefs(this).takeover)
        return START_STICKY
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        scope.cancel()
        OpsLog.log("卸载管控：看板狗已停止")
        super.onDestroy()
    }

    // ---------- 初始化 ----------

    private suspend fun initGuard() {
        val prefs = AppPrefs(this)
        mode = UninstallControl.modeOf(prefs.blockAll, prefs.takeover)
        if (mode == UninstallControl.Mode.OFF) {
            stopSelf()
            return
        }
        val su = runCatching { SuSession.acquire(20_000) }.getOrNull()
        if (su == null) {
            OpsLog.log("卸载管控：暂无 root 会话（Magisk 未记住授权？），guard_state 保持原值")
            return
        }
        val ok = UninstallGuard.ensureDirs(su) && UninstallGuard.setGuardState(su, true)
        UninstallGuard.grantSaw(su, packageName)
        OpsLog.log("卸载管控：guard_state 已应用（${if (ok) "成功" else "失败"}），模式=$mode")
        backupAllInstalled()
        pollLoop()
    }

    // ---------- 轮询 pm 包装器请求 ----------

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val su = runCatching { SuSession.acquire(timeoutMs = 8_000) }.getOrNull()
            if (su != null) {
                runCatching {
                    UninstallGuard.scanRequests(su).forEach { req -> handleRequest(su, req) }
                }
            }
            delay(1_000)
        }
    }

    private fun handleRequest(su: SuSession, req: UninstallGuard.GuardRequest) {
        val now = System.currentTimeMillis()
        val rate = RateLimiter.evaluate(rateState, now, RATE_WINDOW_MS, RATE_MAX)
        rateState = rate.next
        when (UninstallControl.decide(mode, screenOn(), rate.allowed)) {
            UninstallControl.Action.DENY -> {
                UninstallGuard.respond(su, req.id, req.pkg, allow = false)
                val type = when {
                    mode == UninstallControl.Mode.BLOCK_ALL -> GuardEventLog.TYPE_BLOCK_ALL
                    !rate.allowed -> GuardEventLog.TYPE_RATE_LIMIT
                    else -> GuardEventLog.TYPE_SCREEN_OFF
                }
                GuardEventLog.log(type, "已拒绝卸载请求 [${req.pkg}]")
            }
            UninstallControl.Action.ASK ->
                OpsLog.log("卸载管控：卸载请求等待用户确认 [${req.pkg}]（30s 无响应自动拒绝）")
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
        val rate = RateLimiter.evaluate(rateState, System.currentTimeMillis(), RATE_WINDOW_MS, RATE_MAX)
        rateState = rate.next
        when (UninstallControl.decide(mode, screenOn(), rate.allowed)) {
            UninstallControl.Action.DENY -> {
                val ok = UninstallGuard.restoreNow(su, pkg)
                val type = when {
                    mode == UninstallControl.Mode.BLOCK_ALL -> GuardEventLog.TYPE_BLOCK_ALL
                    !rate.allowed -> GuardEventLog.TYPE_RATE_LIMIT
                    else -> GuardEventLog.TYPE_SCREEN_OFF
                }
                GuardEventLog.log(
                    type,
                    "[$label] 被卸载 → ${if (ok) "已自动拦截并恢复" else "恢复失败（无可用备份）"}"
                )
                notifyIntercepted(label, ok)
            }
            UninstallControl.Action.ASK -> {
                val hasBackup = UninstallGuard.hasBackup(su, pkg)
                OpsLog.log("卸载管控：检测到 [$label] 被卸载（备份可用=$hasBackup）→ 弹窗询问")
                val i = Intent(this, ConsentDialogActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(ConsentDialogActivity.EXTRA_MODE, "removed")
                    putExtra(ConsentDialogActivity.EXTRA_PKG, pkg)
                    putExtra(ConsentDialogActivity.EXTRA_LABEL, label)
                }
                if (!runCatching { startActivity(i) }.isSuccess) {
                    val ok = UninstallGuard.restoreNow(su, pkg)
                    GuardEventLog.log(
                        GuardEventLog.TYPE_RESTORED,
                        "确认框拉起失败 → ${if (ok) "已自动恢复 [$label]" else "恢复失败 [$label]（无可用备份）"}"
                    )
                    notifyIntercepted(label, ok)
                }
            }
        }
    }

    // ---------- 备份 ----------

    private suspend fun backupAllInstalled() {
        val su = runCatching { SuSession.acquire(20_000) }.getOrNull() ?: run {
            OpsLog.log("卸载管控：备份跳过（无可用 root 会话，Magisk 未记住授权？）")
            return
        }
        val targets = packageManager.getInstalledPackages(0)
            .mapNotNull { it.applicationInfo }
            .filter { it.sourceDir?.startsWith("/data/app") == true }
            .map { it.packageName }
            .filter { UninstallGuard.validPkg(it) }
        refreshLabels()
        OpsLog.log("卸载管控：开始备份 ${targets.size} 个 /data/app 应用的 APK")
        var done = 0
        for (p in targets) {
            if (!scope.isActive) return
            UninstallGuard.backupNow(su, p)
            done++
            if (done % 10 == 0) OpsLog.log("卸载管控：备份进度 $done/${targets.size}")
        }
        OpsLog.log("卸载管控：备份完成 $done/${targets.size}（目录 ${UninstallGuard.BACKUP_DIR}）")
    }

    private fun backupOne(pkg: String) {
        if (!UninstallGuard.validPkg(pkg)) return
        val ai = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return
        if (ai.sourceDir?.startsWith("/data/app") != true) return
        val su = runCatching { SuSession.acquire(8_000) }.getOrNull() ?: return
        if (UninstallGuard.backupNow(su, pkg)) OpsLog.log("卸载管控：已备份新装应用 [$pkg]")
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
            NotificationChannel(CHANNEL_ID, "卸载管控", NotificationManager.IMPORTANCE_LOW).apply {
                description = "卸载管控运行状态与拦截通知"
            }
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SysKit 卸载管控运行中")
            .setContentText("模式：${if (mode == UninstallControl.Mode.BLOCK_ALL) "禁止系统卸载" else "接管系统卸载"}")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun notifyIntercepted(label: String, ok: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("卸载管控：已自动拦截卸载")
            .setContentText("[$label] ${if (ok) "已自动恢复" else "恢复失败（无可用备份）"}")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 20_000).toInt() + 100, n)
    }
}

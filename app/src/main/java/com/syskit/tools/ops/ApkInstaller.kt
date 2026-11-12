package com.syskit.tools.ops

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.syskit.tools.log.OpsLog
import com.syskit.tools.root.SuSession
import java.io.File

/**
 * APK 安装器。
 *
 * 通道分级（choosePath，纯逻辑可测）：
 *   ROOT_SILENT    — root 会话 `pm install`（静默）
 *   PRIV_SILENT    — priv-app 持有 INSTALL_PACKAGES 的静默会话
 *   CONFIRM_SESSION — 系统确认会话（无特权时的兜底）
 *
 * 边界：平台 PackageInstaller 是框架组件，非平台签名应用无法真正替换；
 * 本安装器提供等效安装通道并注册为 .apk 打开方式（用户可设为默认）。
 */
object ApkInstaller {

    const val ACTION_INSTALL_STATUS = "com.syskit.tools.INSTALL_STATUS"

    /** ROOT_SILENT=root pm install；PRIV_SILENT=特权静默会话；CONFIRM_SESSION=系统确认会话 */
    enum class Path { ROOT_SILENT, PRIV_SILENT, CONFIRM_SESSION }

    fun label(p: Path): String = when (p) {
        Path.ROOT_SILENT -> "Root 静默"
        Path.PRIV_SILENT -> "特权静默"
        Path.CONFIRM_SESSION -> "系统确认"
    }

    fun choosePath(hasRootSession: Boolean, hasInstallPackages: Boolean): Path = when {
        hasRootSession -> Path.ROOT_SILENT
        hasInstallPackages -> Path.PRIV_SILENT
        else -> Path.CONFIRM_SESSION
    }

    fun hasInstallPackages(ctx: Context): Boolean = try {
        ctx.checkCallingOrSelfPermission("android.permission.INSTALL_PACKAGES") ==
            PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    /** 安装 content/file uri 指向的 APK（先拷贝到 cacheDir 暂存） */
    fun install(ctx: Context, uri: Uri, path: Path, onResult: (Boolean, String) -> Unit) {
        val staging = File(ctx.cacheDir, "install_staging.apk")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { input.copyTo(it) }
            } ?: return onResult(false, "无法读取安装包")
        } catch (e: Exception) {
            return onResult(false, "读取安装包失败：${e.message}")
        }
        installFile(ctx, staging, path, onResult)
    }

    /** 安装已就位的本地 APK 文件 */
    fun installFile(ctx: Context, apk: File, path: Path, onResult: (Boolean, String) -> Unit) {
        when (path) {
            Path.ROOT_SILENT -> {
                val su = SuSession.acquire(20_000)
                if (su == null) {
                    onResult(false, "root 会话不可用")
                    return
                }
                OpsLog.log("安装器：Root 静默安装 ${apk.name}")
                val r = su.exec("pm install -r ${apk.absolutePath}")
                apk.delete()
                if (r?.ok == true) onResult(true, "安装完成（Root 静默）")
                else onResult(false, "pm install 失败：${r?.output ?: "超时"}")
            }
            Path.PRIV_SILENT, Path.CONFIRM_SESSION ->
                sessionInstall(ctx, apk, onResult)
        }
    }

    /** PackageInstaller 会话安装：特权持有 INSTALL_PACKAGES 时静默提交，否则走系统确认界面 */
    private fun sessionInstall(ctx: Context, apk: File, onResult: (Boolean, String) -> Unit) {
        try {
            val pi = ctx.packageManager.packageInstaller
            val sessionId = pi.createSession(
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            )
            val session = pi.openSession(sessionId)
            apk.inputStream().use { input ->
                session.openWrite("syskit_install", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val status = i.getIntExtra(
                        PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
                    )
                    when (status) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            val confirm = IntentCompat.getParcelableExtra(
                                i, Intent.EXTRA_INTENT, Intent::class.java
                            )
                            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { confirm?.let { ctx.startActivity(it) } }
                        }
                        PackageInstaller.STATUS_SUCCESS -> {
                            runCatching { ctx.unregisterReceiver(this) }
                            apk.delete()
                            onResult(true, "安装完成")
                        }
                        else -> {
                            runCatching { ctx.unregisterReceiver(this) }
                            apk.delete()
                            onResult(false, "安装失败（status=$status：${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "未知"}）")
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(
                ctx, receiver, IntentFilter(ACTION_INSTALL_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            val sender = PendingIntent.getBroadcast(
                ctx, sessionId,
                Intent(ACTION_INSTALL_STATUS).setPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            session.commit(sender.intentSender)
            session.close()
            onResult(true, "已提交安装会话（等待系统结果）")
        } catch (e: Exception) {
            onResult(false, "安装会话失败：${e.message}")
        }
    }
}

package com.syskit.tools.ops

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.syskit.tools.log.OpsLog
import com.syskit.tools.root.SuSession
import java.io.File
import java.util.zip.ZipFile

/**
 * 内嵌 Magisk（Root 引导）。
 *
 * 任何应用都无法在无 root 设备上给其他应用发放 su —— root 供给只能来自 Magisk
 * 这类 boot 修补方案。因此「内嵌 Magisk」实现为：SysKit 内嵌官方 Magisk APK
 * （构建时锁定版本下载并钉 SHA256），提供静默/确认安装入口与引导说明；
 * Root 授权与模块安装由 Magisk 自身完成。
 *
 * 显示规则（shouldShowEmbedded，纯逻辑可测）：仅当设备无 Magisk、无 root、
 * 且未部署配套模块（priv-app）时显示；任一条件满足即隐藏并关闭功能。
 */
object EmbeddedMagisk {

    const val MAGISK_PKG = "com.topjohnwu.magisk"
    const val MAGISK_ASSET = "magisk/Magisk.apk"

    fun shouldShowEmbedded(magiskInstalled: Boolean, hasRoot: Boolean, isPrivApp: Boolean): Boolean =
        !magiskInstalled && !hasRoot && !isPrivApp

    fun magiskInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(MAGISK_PKG, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun hasEmbeddedAsset(ctx: Context): Boolean = runCatching {
        ctx.assets.open(MAGISK_ASSET).use { true }
    }.getOrDefault(false)

    fun launchMagisk(ctx: Context): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(MAGISK_PKG) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i); true }.getOrDefault(false)
    }

    /** 提取内嵌 Magisk APK 到 cacheDir，供安装器使用 */
    fun extractAssetApk(ctx: Context, targetName: String): File? {
        val f = File(ctx.cacheDir, targetName)
        return runCatching {
            ctx.assets.open(MAGISK_ASSET).use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
            f
        }.getOrNull()
    }

    // ---------- Magisk 模块 zip 解析（纯逻辑可测） ----------

    data class ModuleZipInfo(val id: String, val prefix: String)

    /**
     * 从 zip 条目中定位 module.prop（根目录或单层目录嵌套均可），解析合法 id。
     * @param readText 按条目名读取文本（注入以便测试）
     */
    fun parseModuleZip(entries: List<String>, readText: (String) -> String?): ModuleZipInfo? {
        var entry: String? = null
        var prefix = ""
        if (entries.contains("module.prop")) {
            entry = "module.prop"
        } else {
            entries.filter { it.endsWith("/module.prop") }
                .map { it to it.removeSuffix("module.prop") }
                .firstOrNull { (_, p) -> p.count { it == '/' } == 1 }
                ?.let { entry = it.first; prefix = it.second }
        }
        if (entry == null) return null
        val id = readText(entry)?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("id=") }
            ?.removePrefix("id=")?.trim()
            ?: return null
        if (!Regex("^[a-z0-9._-]{1,50}$").matches(id)) return null
        return ModuleZipInfo(id, prefix)
    }

    // ---------- su 授权记录解析（纯逻辑可测，宽松双格式） ----------

    data class SuPolicyRow(val uid: Int, val policy: Int)

    /**
     * 解析 `magisk --sqlite "SELECT uid,policy,until FROM policies"` 输出。
     * 兼容 key=value 行与管道分隔行；无法解析的行忽略。
     */
    fun parsePolicies(raw: String): List<SuPolicyRow> {
        val rows = ArrayList<SuPolicyRow>()
        for (line in raw.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            if (l.contains("uid=")) {
                val uid = Regex("uid=(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull()
                val policy = Regex("policy=(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull()
                if (uid != null && policy != null) rows += SuPolicyRow(uid, policy)
            } else {
                val parts = l.split('|')
                if (parts.size >= 2) {
                    val uid = parts[0].trim().toIntOrNull()
                    val policy = parts[1].trim().toIntOrNull()
                    if (uid != null && policy != null) rows += SuPolicyRow(uid, policy)
                }
            }
        }
        return rows
    }
}

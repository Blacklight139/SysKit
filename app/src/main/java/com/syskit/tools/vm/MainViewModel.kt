package com.syskit.tools.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syskit.tools.log.GuardEventLog
import com.syskit.tools.log.OpsLog
import com.syskit.tools.ops.ApkInstaller
import com.syskit.tools.ops.DeviceOwnerOps
import com.syskit.tools.ops.EmbeddedMagisk
import com.syskit.tools.ops.SystemOps
import com.syskit.tools.ops.UninstallControl
import com.syskit.tools.ops.UninstallGuard
import com.syskit.tools.prefs.AppPrefs
import com.syskit.tools.root.SuSession
import com.syskit.tools.service.UninstallGuardService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** magisk.db 授权记录（uid + 展示名 + policy） */
data class SuPolicyUi(val uid: Int, val policy: Int, val label: String)

data class UiState(
    val busy: String? = null,
    // 权限状态
    val isSystemApp: Boolean = false,
    val isPrivApp: Boolean = false,
    val hasWss: Boolean = false,
    val installPath: String = "",
    // root
    val rootChecked: Boolean = false,
    val rootOk: Boolean = false,
    val rootInfo: String = "",
    // 当前系统状态
    val adbEnabled: Boolean = false,
    val devOptionsEnabled: Boolean = false,
    val tcpAdbOn: Boolean = false,
    val isDeviceOwner: Boolean = false,
    // 用户开关（持久化）
    val adbLock: Boolean = false,
    val devOptionsPref: Boolean = false,
    val tcpAdbPref: Boolean = false,
    // 卸载管控
    val blockAll: Boolean = false,
    val takeover: Boolean = false,
    val installChannel: String = "",
    // 内嵌 Magisk
    val magiskVisible: Boolean = false,
    val magiskAssetReady: Boolean = false,
    // Root 授权管理
    val suPolicies: List<SuPolicyUi> = emptyList(),
    val toast: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var su: SuSession? = null

    init {
        refresh()
    }

    private fun suOrAcquire(timeoutMs: Long = 30_000): SuSession? {
        su?.let { if (it.isAlive) return it }
        val s = SuSession.acquire(timeoutMs)
        if (s != null) su = s
        return s
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    fun clearLogs() {
        OpsLog.clear()
    }

    fun clearGuardEvents() {
        GuardEventLog.clear()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val s = su?.takeIf { it.isAlive }
            val rootOk = s != null
            val priv = SystemOps.isPrivApp(ctx)
            val magiskInstalled = EmbeddedMagisk.magiskInstalled(ctx)
            val showMagisk = EmbeddedMagisk.shouldShowEmbedded(magiskInstalled, rootOk, priv)
            val policies = s?.let { loadSuPolicies(it) } ?: emptyList()
            _state.update {
                it.copy(
                    isSystemApp = SystemOps.isSystemApp(ctx),
                    isPrivApp = priv,
                    hasWss = SystemOps.hasWriteSecureSettings(ctx),
                    installPath = SystemOps.installPath(ctx),
                    rootChecked = rootOk || it.rootChecked,
                    rootOk = rootOk,
                    rootInfo = it.rootInfo.ifBlank { s?.exec("id")?.output ?: "" },
                    adbEnabled = SystemOps.getAdbEnabled(ctx),
                    devOptionsEnabled = SystemOps.getDevOptionsEnabled(ctx),
                    tcpAdbOn = s?.let { sh -> SystemOps.isTcpAdbOn(sh) } ?: it.tcpAdbOn,
                    isDeviceOwner = DeviceOwnerOps.isDeviceOwner(ctx),
                    adbLock = prefs.adbLock,
                    devOptionsPref = prefs.devOptions,
                    tcpAdbPref = prefs.tcpAdb,
                    blockAll = prefs.blockAll,
                    takeover = prefs.takeover,
                    installChannel = ApkInstaller.label(
                        ApkInstaller.choosePath(rootOk, ApkInstaller.hasInstallPackages(ctx))
                    ),
                    magiskVisible = showMagisk,
                    magiskAssetReady = showMagisk && EmbeddedMagisk.hasEmbeddedAsset(ctx),
                    suPolicies = policies,
                )
            }
        }
    }

    // ---------- 基础：ADB 开关 ----------

    /** 优先系统权限路径（WRITE_SECURE_SETTINGS），失败回退 root */
    fun toggleAdb(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            _state.update { it.copy(busy = if (on) "正在开启 ADB…" else "正在关闭 ADB…") }
            var ok = false
            if (SystemOps.hasWriteSecureSettings(ctx)) {
                ok = SystemOps.setAdbEnabledApi(ctx, on)
                OpsLog.log("ADB ${if (on) "开启" else "关闭"}（系统权限路径）：${if (ok) "成功" else "失败"}")
            }
            if (!ok) {
                val s = suOrAcquire(25_000)
                if (s != null) {
                    ok = SystemOps.setAdbEnabledRoot(s, on)
                    OpsLog.log("ADB ${if (on) "开启" else "关闭"}（root 路径）：${if (ok) "成功" else "失败"}")
                }
            }
            _state.update {
                it.copy(
                    busy = null,
                    toast = if (ok) (if (on) "ADB 调试已开启" else "ADB 调试已关闭") else "开关失败：需要系统权限或 Root"
                )
            }
            refresh()
        }
    }

    // ---------- Root ----------

    /** 请求 root；成功后若卸载管控已开启 → 立即激活看板狗 */
    fun requestRoot() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = "等待 Magisk 授权…") }
            val s = SuSession.acquire(45_000)
            if (s == null) {
                OpsLog.log("Root 获取失败（无 Magisk / 被拒绝 / 超时未确认）")
                _state.update { it.copy(busy = null, rootChecked = true, rootOk = false, toast = "未获得 Root 权限") }
                refresh()
                return@launch
            }
            su = s
            val id = s.exec("id")?.output ?: "uid=0"
            val magisk = s.exec("magisk -v")?.output?.lineSequence()?.firstOrNull().orEmpty()
            OpsLog.log("Root 授权成功：$id${if (magisk.isNotBlank()) " · $magisk" else ""}")
            _state.update { it.copy(busy = null, rootChecked = true, rootOk = true, rootInfo = id, toast = "Root 授权成功") }
            if (UninstallControl.modeOf(prefs.blockAll, prefs.takeover) != UninstallControl.Mode.OFF) {
                val ctx = getApplication<Application>()
                UninstallGuard.ensureDirs(s)
                UninstallGuard.setGuardState(s, true)
                UninstallGuard.grantSaw(s, ctx.packageName)
                UninstallGuardService.start(ctx)
                OpsLog.log("已获取 Root 且卸载管控开启 → 看板狗已激活")
            }
            refresh()
        }
    }

    // ---------- 固化 ADB ----------

    fun toggleAdbLock(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            if (!on) {
                prefs.adbLock = false
                OpsLog.log("固化 ADB：已停用（不再开机重应用；ADB 当前状态不受影响）")
                _state.update { it.copy(toast = "固化 ADB 已停用") }
                refresh()
                return@launch
            }
            _state.update { it.copy(busy = "正在固化 ADB…") }
            var ok = false
            if (SystemOps.hasWriteSecureSettings(ctx)) ok = SystemOps.setAdbEnabledApi(ctx, true)
            val s = suOrAcquire(30_000)
            if (!ok && s != null) ok = SystemOps.setAdbEnabledRoot(s, true)
            if (s != null) {
                val p = SystemOps.persistUsbAdb(s)
                OpsLog.log("固化 ADB：${p.message}")
                if (prefs.tcpAdb) {
                    val t = SystemOps.setTcpAdb(s, true)
                    OpsLog.log("固化 ADB：无线 → ${t.message}")
                }
            }
            prefs.adbLock = true
            OpsLog.log("固化 ADB：已开启，开机将自动重应用")
            val anyOk = ok || s != null
            _state.update {
                it.copy(
                    busy = null,
                    toast = if (anyOk) "ADB 已固化（USB 配置 + 开机自动重应用）" else "部分步骤失败，详见日志"
                )
            }
            refresh()
        }
    }

    // ---------- 开发者选项 ----------

    fun toggleDevOptions(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            _state.update { it.copy(busy = if (on) "正在开启开发者选项…" else "正在关闭开发者选项…") }
            var ok = false
            var via = ""
            if (SystemOps.hasWriteSecureSettings(ctx)) {
                ok = SystemOps.setDevOptionsEnabledApi(ctx, on)
                via = "系统权限"
            }
            if (!ok) {
                val s = suOrAcquire(25_000)
                if (s != null) {
                    ok = SystemOps.setDevOptionsEnabledRoot(s, on)
                    via = "root"
                }
            }
            prefs.devOptions = on && ok
            OpsLog.log("开发者选项 ${if (on) "开启" else "关闭"}（$via）：${if (ok) "成功" else "失败"}")
            _state.update {
                it.copy(
                    busy = null,
                    toast = when {
                        ok && on -> "开发者选项已开启（设置 → 系统 → 开发者选项）"
                        ok -> "开发者选项已关闭"
                        else -> "操作失败：需要系统权限或 Root"
                    }
                )
            }
            refresh()
        }
    }

    // ---------- 无线 ADB ----------

    fun toggleTcpAdb(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = suOrAcquire(30_000)
            if (s == null) {
                _state.update { it.copy(toast = "需要 Root 权限") }
                refresh()
                return@launch
            }
            _state.update { it.copy(busy = "正在配置无线 ADB…") }
            val r = SystemOps.setTcpAdb(s, on)
            prefs.tcpAdb = on && r.ok
            OpsLog.log("无线 ADB：${r.message}")
            _state.update { it.copy(busy = null, toast = r.message) }
            refresh()
        }
    }

    // ---------- 卸载管控 ----------

    fun toggleBlockAll(on: Boolean) {
        prefs.blockAll = on
        OpsLog.log(if (on) "卸载管控：禁止系统卸载 已开启（所有卸载一律拒绝）" else "卸载管控：禁止系统卸载 已关闭")
        applyControl()
    }

    fun toggleTakeover(on: Boolean) {
        prefs.takeover = on
        OpsLog.log(if (on) "卸载管控：接管系统卸载 已开启（单次弹窗确认，频繁/批量自动禁止）" else "卸载管控：接管系统卸载 已关闭")
        applyControl()
    }

    private fun applyControl() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val mode = UninstallControl.modeOf(prefs.blockAll, prefs.takeover)
            if (mode == UninstallControl.Mode.OFF) {
                val s = suOrAcquire(15_000)
                if (s != null) UninstallGuard.setGuardState(s, false)
                UninstallGuardService.stop(ctx)
                _state.update { it.copy(toast = "卸载管控已停用（pm 包装器恢复透传）") }
                refresh()
                return@launch
            }
            _state.update { it.copy(busy = "正在应用卸载管控…") }
            val s = suOrAcquire(30_000)
            if (s == null) {
                _state.update { it.copy(busy = null, toast = "卸载管控需要 Root 权限") }
                refresh()
                return@launch
            }
            val ok = UninstallGuard.ensureDirs(s) && UninstallGuard.setGuardState(s, true)
            UninstallGuard.grantSaw(s, ctx.packageName)
            if (ok) {
                UninstallGuardService.start(ctx)
                _state.update {
                    it.copy(
                        busy = null,
                        toast = "卸载管控已应用：" +
                            if (mode == UninstallControl.Mode.BLOCK_ALL) "禁止系统卸载" else "接管系统卸载"
                    )
                }
            } else {
                _state.update { it.copy(busy = null, toast = "应用失败，详见日志") }
            }
            refresh()
        }
    }

    // ---------- APK 安装器 ----------

    fun installApk(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val s = su?.takeIf { it.isAlive }
            val path = ApkInstaller.choosePath(s != null, ApkInstaller.hasInstallPackages(ctx))
            OpsLog.log("安装器：开始安装（通道=${ApkInstaller.label(path)}）")
            _state.update { it.copy(busy = "正在安装…（通道=${ApkInstaller.label(path)}）") }
            ApkInstaller.install(ctx, uri, path) { ok, msg ->
                OpsLog.log("安装器：$msg")
                _state.update { it.copy(busy = null, toast = msg) }
            }
            refresh()
        }
    }

    // ---------- 内嵌 Magisk ----------

    fun installMagiskManager() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            if (!EmbeddedMagisk.hasEmbeddedAsset(ctx)) {
                _state.update { it.copy(toast = "构建未内嵌 Magisk APK（SYSKIT_SKIP_MAGISK=1）") }
                return@launch
            }
            val f = EmbeddedMagisk.extractAssetApk(ctx, "magisk_install.apk")
            if (f == null) {
                _state.update { it.copy(toast = "内嵌资源提取失败") }
                return@launch
            }
            val s = su?.takeIf { it.isAlive }
            val path = ApkInstaller.choosePath(s != null, ApkInstaller.hasInstallPackages(ctx))
            _state.update { it.copy(busy = "正在安装 Magisk 管理器…（通道=${ApkInstaller.label(path)}）") }
            ApkInstaller.installFile(ctx, f, path) { ok, msg ->
                OpsLog.log("内嵌 Magisk：$msg")
                _state.update {
                    it.copy(
                        busy = null,
                        toast = if (ok) "$msg；打开 Magisk 后「修补 boot 并刷入」即可获得 Root" else msg
                    )
                }
                refresh()
            }
        }
    }

    fun openMagisk() {
        val ctx = getApplication<Application>()
        if (!EmbeddedMagisk.launchMagisk(ctx)) {
            _state.update { it.copy(toast = "未找到 Magisk 管理器") }
        }
    }

    // ---------- Root 授权管理（magisk.db，root 可用时；实验性） ----------

    /** Magisk magisk.db policy 约定：2=允许，0=拒绝；写操作为实验性，精确控制请用 Magisk App */
    fun setSuPolicy(uid: Int, allow: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = suOrAcquire(30_000)
            if (s == null) {
                _state.update { it.copy(toast = "需要 Root 权限") }
                return@launch
            }
            val policy = if (allow) 2 else 0
            val r = s.exec(
                "magisk --sqlite \"REPLACE INTO policies (uid, policy, until, notify) VALUES($uid, $policy, 0, 1)\""
            )
            val ok = r?.ok == true
            OpsLog.log("Root 授权：uid $uid → ${if (allow) "允许" else "拒绝"}${if (ok) "" else "（失败：${r?.output}）"}")
            _state.update {
                it.copy(toast = if (ok) "已${if (allow) "允许" else "拒绝"} uid $uid 的 root 授权" else "设置失败，详见日志")
            }
            refresh()
        }
    }

    fun clearSuPolicy(uid: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = suOrAcquire(30_000)
            if (s == null) {
                _state.update { it.copy(toast = "需要 Root 权限") }
                return@launch
            }
            val ok = s.exec("magisk --sqlite \"DELETE FROM policies WHERE uid=$uid\"")?.ok == true
            OpsLog.log("Root 授权：uid $uid 记录${if (ok) "已清除（恢复询问）" else "清除失败"}")
            _state.update { it.copy(toast = if (ok) "已清除 uid $uid 授权记录（恢复询问）" else "清除失败") }
            refresh()
        }
    }

    private fun loadSuPolicies(s: SuSession): List<SuPolicyUi> {
        val r = s.exec("magisk --sqlite \"SELECT uid, policy, until FROM policies\"") ?: return emptyList()
        if (!r.ok) return emptyList()
        val ctx = getApplication<Application>()
        return EmbeddedMagisk.parsePolicies(r.output).map { row ->
            val name = runCatching { ctx.packageManager.getNameForUid(row.uid) }.getOrNull()
            SuPolicyUi(row.uid, row.policy, name?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: "uid ${row.uid}")
        }
    }

    // ---------- 设备所有者 ----------

    fun toggleDeviceOwner(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            if (on) {
                val s = suOrAcquire(30_000)
                if (s == null) {
                    _state.update { it.copy(toast = "需要 Root 权限") }
                    return@launch
                }
                _state.update { it.copy(busy = "正在设置设备所有者…") }
                val r = DeviceOwnerOps.becomeDeviceOwner(ctx, s)
                OpsLog.log("设备所有者：${r.message}")
                if (r.ok) {
                    DeviceOwnerOps.setUninstallBlocked(ctx, ctx.packageName, true)
                    OpsLog.log("设备所有者：本应用已设为禁止卸载")
                }
                _state.update { it.copy(busy = null, toast = r.message) }
            } else {
                val ok = DeviceOwnerOps.clearDeviceOwner(ctx)
                OpsLog.log("设备所有者：注销${if (ok) "成功" else "失败"}")
                _state.update { it.copy(toast = if (ok) "已注销设备所有者" else "注销失败") }
            }
            refresh()
        }
    }
}

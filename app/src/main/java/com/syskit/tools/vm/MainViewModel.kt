package com.syskit.tools.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syskit.tools.log.OpsLog
import com.syskit.tools.ops.DeviceOwnerOps
import com.syskit.tools.ops.StoreGuard
import com.syskit.tools.ops.SystemOps
import com.syskit.tools.prefs.AppPrefs
import com.syskit.tools.root.SuSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StoreUi(val info: StoreGuard.StoreInfo, val guarded: Boolean?)

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
    val storeGuardPref: Boolean = false,
    val tcpAdbPref: Boolean = false,
    // 商店
    val stores: List<StoreUi> = emptyList(),
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

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val s = su?.takeIf { it.isAlive }
            val stores = StoreGuard.detectInstalled(ctx, prefs.customStores).map { info ->
                StoreUi(info, s?.let { StoreGuard.queryGuarded(it, info.pkg) })
            }
            _state.update {
                it.copy(
                    isSystemApp = SystemOps.isSystemApp(ctx),
                    isPrivApp = SystemOps.isPrivApp(ctx),
                    hasWss = SystemOps.hasWriteSecureSettings(ctx),
                    installPath = SystemOps.installPath(ctx),
                    rootChecked = (s != null) || it.rootChecked,
                    rootOk = s != null,
                    rootInfo = it.rootInfo.ifBlank { s?.exec("id")?.output ?: "" },
                    adbEnabled = SystemOps.getAdbEnabled(ctx),
                    devOptionsEnabled = SystemOps.getDevOptionsEnabled(ctx),
                    tcpAdbOn = s?.let { sh -> SystemOps.isTcpAdbOn(sh) } ?: it.tcpAdbOn,
                    isDeviceOwner = DeviceOwnerOps.isDeviceOwner(ctx),
                    adbLock = prefs.adbLock,
                    devOptionsPref = prefs.devOptions,
                    storeGuardPref = prefs.storeGuard,
                    tcpAdbPref = prefs.tcpAdb,
                    stores = stores,
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

    /** 请求 root；成功后若商店守卫已开启 → 立即禁用商店卸载能力（核心需求） */
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
            if (prefs.storeGuard) {
                OpsLog.log("已获取 Root 且商店守卫开启 → 立即禁用应用商店卸载能力")
                applyStoreGuardInternal()
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

    // ---------- 商店守卫 ----------

    fun toggleStoreGuard(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!on) {
                prefs.storeGuard = false
                OpsLog.log("商店守卫：已停用（已禁用的商店不会自动恢复，可手动“恢复”）")
                _state.update { it.copy(toast = "商店守卫已停用") }
                refresh()
                return@launch
            }
            prefs.storeGuard = true
            val s = suOrAcquire(30_000)
            if (s == null) {
                OpsLog.log("商店守卫：已登记，待获取 Root 后立即执行")
                _state.update { it.copy(toast = "已登记，待获取 Root 后立即执行") }
                refresh()
                return@launch
            }
            applyStoreGuardInternal()
        }
    }

    /** 手动触发：立即禁用 */
    fun applyStoreGuardNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val s = suOrAcquire(30_000)
            if (s == null) {
                _state.update { it.copy(toast = "需要 Root 权限") }
                return@launch
            }
            applyStoreGuardInternal()
        }
    }

    private fun applyStoreGuardInternal() {
        val ctx = getApplication<Application>()
        val s = su ?: return
        _state.update { it.copy(busy = "正在禁用应用商店卸载能力…") }
        val stores = StoreGuard.detectInstalled(ctx, prefs.customStores)
        if (stores.isEmpty()) OpsLog.log("商店守卫：未检测到任何应用商店")
        val results = stores.associate { st -> st.pkg to StoreGuard.applyTo(s, st.pkg) }
        var allOk = true
        results.forEach { (pkg, rs) ->
            val st = stores.first { it.pkg == pkg }
            val g = StoreGuard.guarded(rs)
            if (!g) allOk = false
            OpsLog.log("商店守卫 [${st.label}]：${if (g) "卸载能力已禁用" else "未能禁用（见明细）"}")
            rs.filter { !it.ok }.forEach { OpsLog.log("  ↳ ${it.cmd} → ${it.output}") }
        }
        if (DeviceOwnerOps.isDeviceOwner(ctx)) {
            DeviceOwnerOps.setUninstallBlocked(ctx, ctx.packageName, true)
        }
        _state.update { st ->
            st.copy(
                busy = null,
                stores = st.stores.map { sui ->
                    sui.copy(guarded = results[sui.info.pkg]?.let { StoreGuard.guarded(it) } ?: sui.guarded)
                },
                toast = if (allOk) "应用商店卸载能力已禁用" else "部分商店未完全禁用，详见日志"
            )
        }
        refresh()
    }

    fun restoreStore(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = suOrAcquire(30_000)
            if (s == null) {
                _state.update { it.copy(toast = "需要 Root 权限") }
                return@launch
            }
            val rs = StoreGuard.restoreTo(s, pkg)
            val anyOk = rs.any { it.ok }
            OpsLog.log("恢复 [$pkg]：${if (anyOk) "已恢复卸载能力" else "恢复失败"}")
            rs.filter { !it.ok }.forEach { OpsLog.log("  ↳ ${it.cmd} → ${it.output}") }
            _state.update { st ->
                st.copy(
                    toast = if (anyOk) "已恢复 $pkg 的卸载能力" else "恢复失败，详见日志",
                    stores = st.stores.map { if (it.info.pkg == pkg) it.copy(guarded = false) else it }
                )
            }
            refresh()
        }
    }

    fun addCustomStore(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!pkg.contains('.')) {
                _state.update { it.copy(toast = "包名格式不正确") }
                return@launch
            }
            prefs.customStores = prefs.customStores + pkg
            OpsLog.log("已添加自定义守卫目标：$pkg")
            refresh()
            if (prefs.storeGuard) applyStoreGuardNow()
        }
    }

    fun removeCustomStore(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.customStores = prefs.customStores - pkg
            OpsLog.log("已移除自定义守卫目标：$pkg")
            refresh()
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

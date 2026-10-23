package com.syskit.tools.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.syskit.tools.log.OpsLog
import com.syskit.tools.ops.StoreGuard
import com.syskit.tools.vm.MainViewModel
import com.syskit.tools.vm.StoreUi
import com.syskit.tools.vm.UiState

private val OkGreen = Color(0xFF2E7D32)
private val BadRed = Color(0xFFB3261E)

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val logs by OpsLog.lines.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val ctx = LocalContext.current

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    androidx.compose.material3.Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { HeaderCard(state) }
            item { PrivilegeCard(state) }
            item { BasicCard(state, vm, ctx) }
            item { RootCard(state, vm, ctx) }
            item { StoreGuardCard(state, vm) }
            item { LogsCard(logs, vm) }
            item { FooterCard(ctx) }
        }
    }
}

// ---------------- 头部 ----------------

@Composable
private fun HeaderCard(state: UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SysKit", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("系统工具箱", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (state.busy != null) CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "ADB 管理 · Root 提权 · 商店卸载守卫",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.busy != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(state.busy, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ---------------- 权限状态 ----------------

@Composable
private fun PrivilegeCard(state: UiState) {
    SectionCard("权限状态") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip("系统应用", state.isSystemApp, Modifier.weight(1f))
            StatusChip("特权应用", state.isPrivApp, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip("写系统设置", state.hasWss, Modifier.weight(1f))
            StatusChip("Root", state.rootOk, Modifier.weight(1f))
            StatusChip("设备所有者", state.isDeviceOwner, Modifier.weight(1f))
        }
        Text(
            "安装位置：${state.installPath}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusChip(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    val bg = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val mark = if (ok) "✓ " else "✗ "
    Surface(color = bg, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(
            mark + text,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
        )
    }
}

// ---------------- 基础 ----------------

@Composable
private fun BasicCard(state: UiState, vm: MainViewModel, ctx: Context) {
    SectionCard("基础 · ADB 管理（系统应用路径）") {
        ToggleRow(
            title = "ADB 调试",
            subtitle = "当前：${if (state.adbEnabled) "已开启" else "已关闭"} · 经 WRITE_SECURE_SETTINGS 直接写入，无需 Root",
            checked = state.adbEnabled,
            onChange = vm::toggleAdb
        )
        if (!state.hasWss && !state.rootOk) {
            HintPanel(
                text = "尚未获得系统权限。将本应用部署为系统应用（推荐，见底部说明），或执行：",
                cmd = "adb shell pm grant com.syskit.tools android.permission.WRITE_SECURE_SETTINGS",
                ctx = ctx
            )
        }
    }
}

// ---------------- 高级（Root） ----------------

@Composable
private fun RootCard(state: UiState, vm: MainViewModel, ctx: Context) {
    SectionCard("高级 · Root 功能（Magisk 提权）") {
        if (!state.rootOk) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("获取 Root 权限", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (state.rootChecked) "上次尝试失败（无 Magisk / 被拒 / 超时）" else "需要已安装 Magisk 并授权",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = vm::requestRoot) { Text("获取 Root") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("已获取 Root", style = MaterialTheme.typography.bodyLarge, color = OkGreen, fontWeight = FontWeight.Bold)
                    Text(
                        state.rootInfo,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = vm::refresh) { Text("刷新") }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ToggleRow(
            title = "固化 ADB（永久开启）",
            subtitle = "adb_enabled=1 + persist.sys.usb.config 挂载 adb + 开机自动重应用",
            checked = state.adbLock,
            enabled = state.rootOk || state.hasWss,
            onChange = vm::toggleAdbLock
        )
        ToggleRow(
            title = "无线 ADB（TCP 5555）",
            subtitle = "当前：${if (state.tcpAdbOn) "已开启" else "未开启"} · adb connect <设备IP>:5555 · 局域网内有效，注意安全",
            checked = state.tcpAdbPref,
            enabled = state.rootOk,
            onChange = vm::toggleTcpAdb
        )
        ToggleRow(
            title = "开启开发者选项",
            subtitle = "当前：${if (state.devOptionsEnabled) "已开启" else "已关闭"} · 设置 → 系统 → 开发者选项；带抽屉侧栏的设备将同步显示入口",
            checked = state.devOptionsEnabled,
            enabled = state.rootOk || state.hasWss,
            onChange = vm::toggleDevOptions
        )
        OutlinedButton(
            onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } },
            enabled = state.devOptionsEnabled
        ) { Text("打开开发者选项") }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ToggleRow(
            title = "设备所有者防卸载（增强）",
            subtitle = "dpm set-device-owner → 彻底禁止卸载本应用；已配置账户的设备通常无法设置",
            checked = state.isDeviceOwner,
            enabled = state.rootOk,
            onChange = vm::toggleDeviceOwner
        )
    }
}

// ---------------- 商店守卫 ----------------

@Composable
private fun StoreGuardCard(state: UiState, vm: MainViewModel) {
    SectionCard("应用商店卸载守卫（获得 Root 后立即执行）") {
        ToggleRow(
            title = "禁用应用商店卸载能力",
            subtitle = "吊销 REQUEST_DELETE_PACKAGES + appops deny，开机自动重应用；商店升级后如复得权限亦会被重新吊销",
            checked = state.storeGuardPref,
            onChange = vm::toggleStoreGuard
        )
        if (state.rootOk && state.storeGuardPref) {
            TextButton(onClick = vm::applyStoreGuardNow) { Text("立即执行") }
        }

        if (state.stores.isEmpty()) {
            Text(
                "未检测到已知应用商店（可添加自定义包名）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.stores.forEach { item -> StoreRow(item, state.rootOk, vm) }
        }

        var custom by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                label = { Text("自定义商店包名") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = custom.contains('.'),
                onClick = {
                    vm.addCustomStore(custom.trim())
                    custom = ""
                }
            ) { Text("添加") }
        }
    }
}

@Composable
private fun StoreRow(item: StoreUi, hasRoot: Boolean, vm: MainViewModel) {
    val isKnown = StoreGuard.KNOWN_STORES.any { it.first == item.info.pkg }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.info.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                item.info.pkg,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                when (item.guarded) {
                    true -> "● 卸载能力已禁用"
                    false -> "● 已恢复"
                    null -> "○ 待执行${if (!hasRoot) "（需 Root）" else ""}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (item.guarded) {
                    true -> OkGreen
                    false -> MaterialTheme.colorScheme.onSurfaceVariant
                    null -> BadRed
                }
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (hasRoot) {
                TextButton(onClick = { vm.restoreStore(item.info.pkg) }) { Text("恢复") }
            }
            if (!isKnown) {
                TextButton(onClick = { vm.removeCustomStore(item.info.pkg) }) { Text("移除") }
            }
        }
    }
}

// ---------------- 日志 ----------------

@Composable
private fun LogsCard(logs: List<OpsLog.Line>, vm: MainViewModel) {
    SectionCard("操作日志") {
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = vm::clearLogs) { Text("清空") }
        }
        if (logs.isEmpty()) {
            Text("暂无日志", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                logs.takeLast(80).reversed().joinToString("\n") { it.formatted },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}

// ---------------- 底部 ----------------

@Composable
private fun FooterCard(ctx: Context) {
    SectionCard("部署与说明") {
        Text(
            "① Magisk 模块（推荐）：刷入 SysKit-MagiskModule.zip，自动以系统特权应用（/system/priv-app）方式挂载\n" +
                "② 直接写入系统分区：adb push 至 /system/priv-app/SysKit/ 并部署 privapp-permissions 白名单（详见 README）\n" +
                "③ 临时授权（无需 root / 系统应用）：执行下方命令，开放基础功能",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HintPanel(
            text = "临时授权命令：",
            cmd = "adb shell pm grant com.syskit.tools android.permission.WRITE_SECURE_SETTINGS",
            ctx = ctx
        )
        Text(
            "安全提示：固化/无线 ADB 会扩大调试面，请仅在可信环境使用；本应用不申请网络权限。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "SysKit v1.0.0 · minSdk 26 (Android 8.0)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------- 通用组件 ----------------

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun HintPanel(text: String, cmd: String, ctx: Context) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            Text(
                cmd,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(onClick = { copyToClipboard(ctx, cmd) }) { Text("复制命令") }
        }
    }
}

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("syskit", text))
}

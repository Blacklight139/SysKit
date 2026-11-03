package com.syskit.tools.ui

import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.syskit.tools.log.OpsLog
import com.syskit.tools.ops.UninstallGuard
import com.syskit.tools.root.SuSession
import kotlinx.coroutines.delay

/**
 * 卸载同意对话框（exported=false，仅由 pm 包装器 am start / 看板狗服务拉起）。
 *
 * - ask 模式：pm 包装器事前询问 —— 允许 / 拒绝，30s 无响应自动拒绝
 * - removed 模式：看板狗事后询问 —— 保持卸载 / 恢复应用，30s 无响应自动恢复
 * - 参数白名单校验；熄屏时 ask 直接自动拒绝（与看板狗双保险）
 */
class ConsentDialogActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "mode"   // ask | removed
        const val EXTRA_PKG = "pkg"
        const val EXTRA_ID = "id"
        const val EXTRA_LABEL = "label"
        private const val TIMEOUT_SECS = 30
    }

    private var mode = "ask"
    private var pkg = ""
    private var id: String? = null
    private var label = ""
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: "ask"
        pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        label = intent.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() } ?: pkg
        id = intent.getStringExtra(EXTRA_ID)

        // 入口校验：非白名单参数直接关闭
        if (!UninstallGuard.validPkg(pkg)) { finish(); return }
        if (mode != "removed" && (id == null || !UninstallGuard.validId(id!!))) { finish(); return }

        // 熄屏兜底：正常由看板狗秒回 DENY，此处双保险
        if (mode != "removed" && !screenOn()) { doAsk(false); return }

        enableEdgeToEdge()
        setContent { SysKitTheme { ConsentContent() } }
    }

    private fun screenOn(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    /** ask 模式：应答包装器 */
    private fun doAsk(allow: Boolean) {
        val rid = id ?: return
        act {
            val su = SuSession.acquire(10_000)
            if (su != null) {
                UninstallGuard.respond(su, rid, pkg, allow)
                OpsLog.log("卸载守卫：用户${if (allow) "允许" else "拒绝"}卸载 [$pkg]")
            } else {
                OpsLog.log("卸载守卫：root 会话不可用，卸载请求按拒绝处理 [$pkg]")
            }
        }
    }

    /** removed 模式：keep=保持卸载（并清理备份）；否则恢复 */
    private fun doRemoved(keep: Boolean) {
        act {
            val su = SuSession.acquire(10_000)
            if (su == null) {
                OpsLog.log("卸载守卫：root 会话不可用 [$pkg]")
                return@act
            }
            if (keep) {
                UninstallGuard.removeBackup(su, pkg)
                OpsLog.log("卸载守卫：用户确认保持卸载 [$pkg]，备份已清理")
            } else {
                val ok = UninstallGuard.restoreNow(su, pkg)
                OpsLog.log("卸载守卫：恢复 [$pkg] → ${if (ok) "成功" else "失败（无可用备份）"}")
            }
        }
    }

    private fun act(block: () -> Unit) {
        if (handled) return
        handled = true
        finish()
        Thread { runCatching(block) }.start()
    }

    @Composable
    private fun ConsentContent() {
        var left by remember { mutableStateOf(TIMEOUT_SECS) }
        LaunchedEffect(Unit) {
            while (left > 0) {
                delay(1_000)
                left--
            }
            if (mode != "removed") doAsk(false) else doRemoved(false)
        }
        AlertDialog(
            onDismissRequest = { /* 点外/返回不响应，走倒计时安全默认 */ },
            title = { Text(if (mode != "removed") "卸载确认" else "检测到应用被卸载") },
            text = {
                Text(
                    if (mode != "removed")
                        "SysKit 检测到卸载请求：\n\n$label\n($pkg)\n\n是否允许？\n${left}s 后自动拒绝"
                    else
                        "应用被卸载：\n\n$label\n($pkg)\n\n是否恢复？\n${left}s 后自动尝试恢复"
                )
            },
            confirmButton = {
                TextButton(onClick = { if (mode != "removed") doAsk(true) else doRemoved(true) }) {
                    Text(if (mode != "removed") "允许卸载" else "保持卸载")
                }
            },
            dismissButton = {
                TextButton(onClick = { if (mode != "removed") doAsk(false) else doRemoved(false) }) {
                    Text(if (mode != "removed") "拒绝" else "恢复应用")
                }
            }
        )
    }
}

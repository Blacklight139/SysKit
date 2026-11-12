package com.syskit.tools.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.syskit.tools.log.OpsLog
import com.syskit.tools.ops.ApkInstaller

private val OkColor = Color(0xFF2E7D32)
private val BadColor = Color(0xFFB3261E)

/**
 * APK 安装器：接收 .apk 的 VIEW 打开请求（用户可设为默认安装器）。
 * 安装始终需要手动点击确认；外部入口不触发 su（避免意外弹出 Magisk 授权），
 * 有 INSTALL_PACKAGES（priv-app）走静默会话，否则走系统确认。
 */
class InstallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent { SysKitTheme { InstallerContent(uri) } }
    }

    @Composable
    private fun InstallerContent(uri: Uri) {
        val ctx = this
        var installing by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<String?>(null) }
        val info = remember(uri) { describe(uri) }
        val priv = remember { ApkInstaller.hasInstallPackages(ctx) }

        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("SysKit 安装器", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    info,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                if (installing) {
                    CircularProgressIndicator()
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            enabled = result == null || !(result!!.startsWith("安装完成") || result!!.startsWith("已提交")),
                            onClick = {
                                installing = true
                                result = null
                                val path = if (priv) ApkInstaller.Path.PRIV_SILENT
                                else ApkInstaller.Path.CONFIRM_SESSION
                                ApkInstaller.install(ctx, uri, path) { _, msg ->
                                    installing = false
                                    result = msg
                                    OpsLog.log("安装器：$msg")
                                }
                            }
                        ) { Text("安装") }
                        OutlinedButton(onClick = { finish() }) {
                            Text(if (result == null) "取消" else "关闭")
                        }
                    }
                }
                result?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        it,
                        color = if (it.startsWith("安装完成") || it.startsWith("已提交")) OkColor else BadColor
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "安装通道：${if (priv) "特权静默" else "系统确认"}（Root 静默通道仅应用内安装页使用）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    private fun describe(uri: Uri): String = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "unknown" else "unknown"
                val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else -1L
                "$name${if (size > 0) " · ${size / 1024 / 1024}MB" else ""}"
            } else {
                null
            }
        } ?: uri.toString()
    }.getOrDefault(uri.toString())
}

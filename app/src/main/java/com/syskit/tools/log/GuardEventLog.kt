package com.syskit.tools.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 卸载管控独立审计日志（与 OpsLog 分离）。
 *
 * 记录自动禁止（禁止模式 / 超频 / 熄屏）、自动恢复、用户放行/拒绝等事件；
 * 内存环形展示 + filesDir 文件持久化（超 256KB 截断保留最新）。
 */
object GuardEventLog {

    const val TYPE_BLOCK_ALL = "BLOCK_ALL"
    const val TYPE_RATE_LIMIT = "RATE_LIMIT"
    const val TYPE_SCREEN_OFF = "SCREEN_OFF"
    const val TYPE_RESTORED = "RESTORED"
    const val TYPE_ALLOWED = "ALLOWED"
    const val TYPE_DENIED = "CONSENT_DENIED"

    data class Entry(val time: Long, val type: String, val detail: String) {
        val formatted: String
            get() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(time)) +
                " [$type] $detail"
    }

    private val _lines = MutableStateFlow<List<Entry>>(emptyList())
    val lines: StateFlow<List<Entry>> = _lines.asStateFlow()

    @Volatile
    private var file: File? = null

    private const val MAX = 200
    private const val FILE_LIMIT = 256 * 1024

    /** 应用启动时调用（幂等）：从文件恢复最近记录并继续追加 */
    fun init(dir: File) {
        val f = File(dir, "guard_events.log")
        file = f
        if (f.exists()) {
            _lines.value = f.readLines().takeLast(MAX).mapNotNull(::parseLine)
        }
    }

    @Synchronized
    fun log(type: String, detail: String) {
        val e = Entry(System.currentTimeMillis(), type, detail.replace('\n', ' '))
        _lines.value = (_lines.value + e).takeLast(MAX)
        file?.let { f ->
            runCatching {
                f.appendText("${e.time}|$type|${e.detail}\n")
                if (f.length() > FILE_LIMIT) {
                    f.writeText(f.readLines().takeLast(MAX).joinToString("\n") + "\n")
                }
            }
        }
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
        file?.let { runCatching { it.writeText("") } }
    }

    private fun parseLine(l: String): Entry? {
        val p = l.split('|', limit = 3)
        if (p.size < 3) return null
        val t = p[0].toLongOrNull() ?: return null
        return Entry(t, p[1], p[2])
    }
}

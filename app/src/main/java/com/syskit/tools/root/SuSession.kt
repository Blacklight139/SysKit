package com.syskit.tools.root

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 单条命令执行结果。
 *
 * @param code  命令退出码；[CODE_BROKEN] 表示 su 进程已退出（被拒/无 su）
 */
data class ExecResult(val code: Int, val out: List<String>) {
    val ok: Boolean get() = code == 0
    val broken: Boolean get() = code == CODE_BROKEN
    /** 合并后的输出（已去除空行） */
    val output: String get() = out.joinToString("\n").trim()

    companion object {
        const val CODE_BROKEN = -999
    }
}

/**
 * 常驻交互式 su 会话（Magisk 兼容）。
 *
 * 一次授权、多次复用，避免每条命令都弹出授权框。
 * 通过向 su 的 stdin 写入命令 + 唯一 marker 回显退出码来可靠地收集输出。
 */
class SuSession private constructor(private val process: Process) {

    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()

    /** 执行单条命令（必须是单行）。超时返回 null（可能正在等待授权确认）。 */
    fun exec(cmd: String, timeoutMs: Long = 15_000): ExecResult? = synchronized(lock) {
        if (!process.isAlive) return ExecResult(ExecResult.CODE_BROKEN, listOf("su process exited"))
        val marker = "SYSKIT_RC_${UUID.randomUUID().toString().replace("-", "").substring(0, 12)}"
        val future = io.submit(Callable<ExecResult> {
            process.outputStream.apply {
                write("$cmd\n".toByteArray())
                write("echo $marker:$?\n".toByteArray())
                flush()
            }
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            val lines = ArrayList<String>(16)
            val rcPattern = Regex("^$marker:(-?\\d+)$")
            while (true) {
                val line = reader.readLine() ?: return@Callable ExecResult(ExecResult.CODE_BROKEN, lines)
                val m = rcPattern.find(line.trim())
                if (m != null) {
                    val rc = m.groupValues[1].toIntOrNull() ?: ExecResult.CODE_BROKEN
                    return@Callable ExecResult(rc, lines)
                }
                if (line.isNotBlank()) lines.add(line)
            }
            @Suppress("UNREACHABLE_CODE")
            ExecResult(ExecResult.CODE_BROKEN, lines)
        })
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            null
        } catch (e: Exception) {
            null
        }
    }

    val isAlive: Boolean get() = process.isAlive

    fun close() {
        runCatching {
            process.outputStream.write("exit\n".toByteArray())
            process.outputStream.flush()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        process.destroyForcibly()
        io.shutdownNow()
    }

    companion object {
        @Volatile
        private var cached: SuSession? = null

        /**
         * 获取 root 会话；失败（无 su / 被拒绝 / 超时未确认授权）返回 null。
         * 已有存活会话时直接复用。
         *
         * @param timeoutMs 首次 `id` 校验超时，期间 Magisk 会弹出授权对话框
         */
        fun acquire(timeoutMs: Long = 45_000): SuSession? {
            cached?.let { if (it.isAlive) return it }
            val session = try {
                val p = ProcessBuilder("su").redirectErrorStream(true).start()
                val s = SuSession(p)
                val check = s.exec("id", timeoutMs)
                if (check != null && check.ok && check.out.any { it.contains("uid=0") }) s
                else { s.close(); null }
            } catch (e: Exception) {
                null
            }
            if (session != null) cached = session
            return session
        }

        fun release() {
            cached?.close()
            cached = null
        }
    }
}

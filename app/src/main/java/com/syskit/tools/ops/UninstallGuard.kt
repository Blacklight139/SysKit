package com.syskit.tools.ops

import com.syskit.tools.log.OpsLog
import com.syskit.tools.root.SuSession
import java.util.concurrent.ConcurrentHashMap

/**
 * 卸载守卫（Uninstall Guard）核心。
 *
 * 三层机制：
 *  1. pm 包装器（Magisk 模块 /system/bin/pm）事前拦截 shell 卸载 —— 文件协议在本对象定义：
 *     包装器写 req_<id>（内容为包名）→ 轮询 resp_<id>（ALLOW / DENY）
 *  2. PACKAGE_REMOVED 看板狗（UninstallGuardService）事后确认 / 熄屏自动恢复
 *  3. APK 备份（第三方应用可恢复性；系统应用走 install-existing 无需备份）
 *
 * 安全：所有进入 root 单行命令的外部值（包名 / 请求 ID）均须白名单校验，杜绝 shell 注入；
 * 包装器超时 / 会话丢失 / 写入失败一律按拒绝处理（fail-closed）。
 */
object UninstallGuard {

    const val GUARD_DIR = "/data/local/tmp/syskit"
    const val GUARD_STATE_FILE = "$GUARD_DIR/guard_state"
    const val BACKUP_DIR = "$GUARD_DIR/backups"

    /** 包装器授权后短期内到达的 REMOVED 广播视为用户已同意，避免二次拦截 */
    const val ALLOW_WINDOW_MS = 15_000L

    /** guard_state 取值：0 关闭（透传）/ 1 接管（弹窗确认）/ 2 禁止（一律拒绝） */
    const val GUARD_MODE_OFF = 0
    const val GUARD_MODE_ASK = 1
    const val GUARD_MODE_DENY_ALL = 2

    /** 批量保护：时间窗内卸载尝试次数阈值 / 窗口时长 / 触发后冷却期 */
    const val BULK_LIMIT = 3
    const val BULK_WINDOW_MS = 60_000L
    const val BULK_COOLDOWN_MS = 10 * 60_000L

    private val PKG_RE = Regex("^[a-zA-Z0-9._]{1,200}$")
    private val ID_RE = Regex("^[0-9_]{1,32}$")

    /** 守卫动作：亮屏弹窗询问 / 熄屏自动拦截 */
    enum class Action { BLOCK, ASK }

    /** pm 包装器卸载请求 */
    data class GuardRequest(val id: String, val pkg: String)

    /** 恢复计划：primary 失败后尝试 fallback */
    data class RestorePlan(val primary: String, val fallback: String?)

    // ---------- 校验（root 命令注入防线） ----------

    fun validPkg(pkg: String): Boolean = pkg != "." && pkg != ".." && PKG_RE.matches(pkg)

    fun validId(id: String): Boolean = ID_RE.matches(id)

    // ---------- 决策 ----------

    /** 亮屏弹窗询问；熄屏自动拦截 */
    fun decide(screenOn: Boolean): Action = if (screenOn) Action.ASK else Action.BLOCK

    /** REMOVED 事件是否需要守卫处理：升级替换 / 自身 / 刚授权过的不处理 */
    fun shouldReactTo(
        pkg: String,
        selfPkg: String,
        replacing: Boolean,
        allowedUntil: Long,
        now: Long,
    ): Boolean = !replacing && pkg != selfPkg && now >= allowedUntil

    // ---------- 恢复计划 ----------

    fun restorePlan(pkg: String, hasBackup: Boolean): RestorePlan? {
        if (!validPkg(pkg)) return null
        val primary = "pm install-existing $pkg"
        val fallback = if (hasBackup) "cd $BACKUP_DIR/$pkg && pm install-multiple -r *.apk" else null
        return RestorePlan(primary, fallback)
    }

    // ---------- 文件协议 ----------

    fun reqFileName(id: String): String? = if (validId(id)) "req_$id" else null

    fun respFileName(id: String): String? = if (validId(id)) "resp_$id" else null

    /** 扫描并消费 req_*（printf 输出请求行）；顺带清理超过 3 分钟的孤儿 resp_* */
    fun pollCmd(): String =
        "D=$GUARD_DIR; " +
            "for f in \"\$D\"/req_*; do [ -e \"\$f\" ] || break; " +
            "printf 'SYSKIT_REQ|%s|%s\\n' \"\${f##*/}\" \"\$(cat \"\$f\" 2>/dev/null)\"; " +
            "rm -f \"\$f\"; done; " +
            "find \"\$D\" -maxdepth 1 -name 'resp_*' -mmin +3 -exec rm -f {} \\; 2>/dev/null"

    fun respCmd(id: String, allow: Boolean): String? {
        if (!validId(id)) return null
        val verdict = if (allow) "ALLOW" else "DENY"
        return "printf $verdict > $GUARD_DIR/resp_$id"
    }

    fun parsePollLine(line: String): GuardRequest? {
        val m = Regex("^SYSKIT_REQ\\|req_([0-9_]{1,32})\\|(.{1,220})$").find(line) ?: return null
        val id = m.groupValues[1]
        val pkg = m.groupValues[2]
        if (!validId(id) || !validPkg(pkg)) return null
        return GuardRequest(id, pkg)
    }

    // ---------- 看板狗集成（SuSession 执行） ----------

    /** 轮询包装器请求文件并解析（shell 侧已消费 req_*） */
    fun scanRequests(su: SuSession): List<GuardRequest> {
        val r = su.exec(pollCmd()) ?: return emptyList()
        return r.out.mapNotNull { parsePollLine(it) }
    }

    /** 应答包装器；allow=true 且写入成功时记入最近授权窗口 */
    fun respond(su: SuSession, id: String, pkg: String, allow: Boolean): Boolean {
        val cmd = respCmd(id, allow) ?: return false
        val r = su.exec(cmd) ?: return false
        if (r.ok && allow) markAllowed(pkg)
        return r.ok
    }

    fun hasBackup(su: SuSession, pkg: String): Boolean {
        if (!validPkg(pkg)) return false
        val r = su.exec("ls $BACKUP_DIR/$pkg/*.apk 2>/dev/null") ?: return false
        return r.ok && r.output.isNotBlank()
    }

    /** 恢复：系统应用 install-existing 优先，失败回退备份 install-multiple */
    fun restoreNow(su: SuSession, pkg: String): Boolean {
        val plan = restorePlan(pkg, hasBackup(su, pkg)) ?: return false
        if (su.exec(plan.primary)?.ok == true) return true
        plan.fallback?.let { return su.exec(it)?.ok == true }
        return false
    }

    fun backupCmd(pkg: String): String =
        "mkdir -p $BACKUP_DIR/$pkg && rm -f $BACKUP_DIR/$pkg/*.apk && " +
            "for p in \$(pm path $pkg | cut -d: -f2); do cp -f \"\$p\" \"$BACKUP_DIR/$pkg/\"; done"

    fun backupNow(su: SuSession, pkg: String): Boolean {
        if (!validPkg(pkg)) return false
        return su.exec(backupCmd(pkg))?.ok == true
    }

    fun removeBackup(su: SuSession, pkg: String): Boolean {
        if (!validPkg(pkg)) return false
        return su.exec("rm -rf $BACKUP_DIR/$pkg")?.ok == true
    }

    /** 建守卫目录 + 777 权限（包装器 shell uid 需可写 req）+ 清理遗留请求 */
    fun ensureDirs(su: SuSession): Boolean {
        val mkdir = su.exec("mkdir -p $BACKUP_DIR && chmod 777 $GUARD_DIR $BACKUP_DIR")
        if (mkdir?.ok != true) {
            OpsLog.log("卸载守卫：创建/授权 $GUARD_DIR 失败")
            return false
        }
        su.exec("rm -f $GUARD_DIR/req_* $GUARD_DIR/resp_*")
        return true
    }

    /** guard_state 写入命令（0 关闭 / 1 接管弹窗 / 2 禁止一切卸载） */
    fun guardStateCmd(mode: Int): String? =
        if (mode == GUARD_MODE_OFF || mode == GUARD_MODE_ASK || mode == GUARD_MODE_DENY_ALL)
            "printf $mode > $GUARD_STATE_FILE"
        else null

    /** 写 guard_state（包装器据此决定透传 / 弹窗 / 拒绝） */
    fun setGuardState(su: SuSession, mode: Int): Boolean {
        val cmd = guardStateCmd(mode) ?: return false
        val r = su.exec(cmd)
        if (r?.ok != true) OpsLog.log("卸载守卫：写入 guard_state 失败")
        return r?.ok == true
    }

    /** 悬浮窗 appop（亮屏弹窗从后台拉起）；通知权限尽力而为 */
    fun grantSaw(su: SuSession, selfPkg: String): Boolean {
        if (!validPkg(selfPkg)) return false
        val ok = su.exec("appops set $selfPkg SYSTEM_ALERT_WINDOW allow")?.ok == true
        su.exec("pm grant $selfPkg android.permission.POST_NOTIFICATIONS")
        return ok
    }

    // ---------- 批量保护（接管模式：频繁/一次性大量卸载自动禁止） ----------

    private val attempts = ArrayDeque<Long>()

    @Volatile
    private var bulkUntil = 0L

    /** 记录一次卸载尝试；返回 true 表示本次触发了批量保护（进入冷却期） */
    fun recordAttempt(now: Long): Boolean {
        synchronized(attempts) {
            if (now < bulkUntil) return false
            attempts.addLast(now)
            while (attempts.size > 32) attempts.removeFirst()
            val recent = attempts.count { it >= now - BULK_WINDOW_MS }
            if (recent >= BULK_LIMIT) {
                bulkUntil = now + BULK_COOLDOWN_MS
                attempts.clear()
                return true
            }
            return false
        }
    }

    /** 是否处于批量保护冷却期（期间所有卸载自动禁止） */
    fun isBulkBlocked(now: Long): Boolean = synchronized(attempts) { now < bulkUntil }

    /** 清空批量保护状态（模式切换/服务重启时调用） */
    fun resetBulk() = synchronized(attempts) { attempts.clear(); bulkUntil = 0L }

    // ---------- 最近授权状态（进程内存；服务重启即失效，属可接受降级） ----------

    private val allowed = ConcurrentHashMap<String, Long>()

    fun markAllowed(pkg: String) {
        if (!validPkg(pkg)) return
        val now = System.currentTimeMillis()
        if (allowed.size >= 64) allowed.entries.removeIf { it.value <= now }
        allowed[pkg] = now + ALLOW_WINDOW_MS
    }

    fun allowedUntil(pkg: String): Long = allowed[pkg] ?: 0L
}

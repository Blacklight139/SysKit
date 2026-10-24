package com.syskit.tools.ops

/** 卸载守卫纯逻辑（存根：TDD RED 阶段） */
object UninstallGuard {

    enum class Action { BLOCK, ASK }

    data class GuardRequest(val id: String, val pkg: String)

    data class RestorePlan(val primary: String, val fallback: String?)

    fun validPkg(pkg: String): Boolean = false

    fun validId(id: String): Boolean = false

    fun decide(screenOn: Boolean): Action = Action.BLOCK

    fun shouldReactTo(
        pkg: String,
        selfPkg: String,
        replacing: Boolean,
        allowedUntil: Long,
        now: Long,
    ): Boolean = false

    fun restorePlan(pkg: String, hasBackup: Boolean): RestorePlan? = null

    fun reqFileName(id: String): String? = null

    fun respFileName(id: String): String? = null

    fun pollCmd(): String = ""

    fun respCmd(id: String, allow: Boolean): String? = null

    fun parsePollLine(line: String): GuardRequest? = null
}

package com.syskit.tools.ops

/**
 * 卸载管控决策核心（纯逻辑，可测）。
 *
 * 两个开关（AppPrefs）：blockAll=禁止系统卸载；takeover=接管系统卸载。
 * modeOf：禁止优先于接管。OFF 时 pm 包装器透传、服务不启动。
 */
object UninstallControl {

    /** OFF=守卫关闭（包装器透传）；TAKEOVER=接管系统卸载；BLOCK_ALL=禁止系统卸载 */
    enum class Mode { OFF, TAKEOVER, BLOCK_ALL }

    /** ASK=弹窗询问；DENY=自动拒绝（熄屏/超频/禁止模式） */
    enum class Action { ASK, DENY }

    fun modeOf(blockAll: Boolean, takeover: Boolean): Mode =
        if (blockAll) Mode.BLOCK_ALL else if (takeover) Mode.TAKEOVER else Mode.OFF

    fun decide(mode: Mode, screenOn: Boolean, rateAllowed: Boolean): Action = when {
        mode != Mode.TAKEOVER -> Action.DENY // 禁止模式一律拒绝；OFF 不可达（服务不启动）→ fail-closed
        !rateAllowed -> Action.DENY          // 频繁 / 一次性大量卸载 → 自动拒绝
        !screenOn -> Action.DENY             // 熄屏 → 自动拒绝
        else -> Action.ASK                   // 单次单个应用 → 弹窗询问
    }
}

/** 卸载尝试限速器（滑动窗口；被拒尝试同样计数，防绕过） */
object RateLimiter {

    data class State(val times: List<Long> = emptyList())

    data class Result(val allowed: Boolean, val next: State)

    fun evaluate(state: State, now: Long, windowMs: Long, max: Int): Result {
        val inWindow = state.times.filter { it > now - windowMs }
        val allowed = inWindow.size < max
        return Result(allowed, State(inWindow + now))
    }
}

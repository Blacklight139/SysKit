package com.syskit.tools.prefs

import android.content.Context
import android.content.SharedPreferences

/** 应用持久配置：决定了开机 watchdog 需要重新应用哪些项 */
class AppPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("syskit_prefs", Context.MODE_PRIVATE)

    /** 固化 ADB：立即开启 + persist USB 配置 + 开机自动重应用 */
    var adbLock: Boolean
        get() = sp.getBoolean(K_ADB_LOCK, false)
        set(v) = sp.edit().putBoolean(K_ADB_LOCK, v).apply()

    /** 保持开发者选项开启（含开机重应用） */
    var devOptions: Boolean
        get() = sp.getBoolean(K_DEV_OPTIONS, false)
        set(v) = sp.edit().putBoolean(K_DEV_OPTIONS, v).apply()

    /** 禁止系统卸载：开启后所有卸载一律拒绝（优先于接管开关） */
    var blockAll: Boolean
        get() = sp.getBoolean(K_UNINSTALL_BLOCK_ALL, false)
        set(v) = sp.edit().putBoolean(K_UNINSTALL_BLOCK_ALL, v).apply()

    /** 接管系统卸载：单次单个应用弹窗确认；频繁/批量自动禁止并独立记录 */
    var takeover: Boolean
        get() = sp.getBoolean(K_UNINSTALL_TAKEOVER, false)
        set(v) = sp.edit().putBoolean(K_UNINSTALL_TAKEOVER, v).apply()

    /** 无线 ADB（TCP 5555） */
    var tcpAdb: Boolean
        get() = sp.getBoolean(K_TCP_ADB, false)
        set(v) = sp.edit().putBoolean(K_TCP_ADB, v).apply()

    private companion object {
        const val K_ADB_LOCK = "adb_lock"
        const val K_DEV_OPTIONS = "dev_options"
        const val K_UNINSTALL_BLOCK_ALL = "uninstall_block_all"
        const val K_UNINSTALL_TAKEOVER = "uninstall_takeover"
        const val K_TCP_ADB = "tcp_adb"
    }
}

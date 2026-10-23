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

    /** 禁用应用商店卸载能力（获得 root 后立即执行 + 开机重应用） */
    var storeGuard: Boolean
        get() = sp.getBoolean(K_STORE_GUARD, false)
        set(v) = sp.edit().putBoolean(K_STORE_GUARD, v).apply()

    /** 无线 ADB（TCP 5555） */
    var tcpAdb: Boolean
        get() = sp.getBoolean(K_TCP_ADB, false)
        set(v) = sp.edit().putBoolean(K_TCP_ADB, v).apply()

    /** 用户自定义需要守卫的商店包名 */
    var customStores: Set<String>
        get() = sp.getStringSet(K_CUSTOM_STORES, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_CUSTOM_STORES, v).apply()

    private companion object {
        const val K_ADB_LOCK = "adb_lock"
        const val K_DEV_OPTIONS = "dev_options"
        const val K_STORE_GUARD = "store_guard"
        const val K_TCP_ADB = "tcp_adb"
        const val K_CUSTOM_STORES = "custom_stores"
    }
}

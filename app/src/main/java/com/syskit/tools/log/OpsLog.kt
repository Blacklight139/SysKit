package com.syskit.tools.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 全局操作日志（内存环形缓冲，UI 实时观察，便于排障） */
object OpsLog {

    data class Line(val time: Long, val text: String) {
        val formatted: String
            get() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(time)) + "  " + text
    }

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    @Synchronized
    fun log(text: String) {
        _lines.value = (_lines.value + Line(System.currentTimeMillis(), text)).takeLast(MAX)
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }

    private const val MAX = 400
}

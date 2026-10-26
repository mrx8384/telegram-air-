package com.tcam.app.data

import android.util.Log
import com.tcam.core.engine.LogLevel
import com.tcam.core.engine.LogSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LogLine(val time: Long, val level: LogLevel, val message: String)

/** Collects logs from every part of the app: live in memory for the UI, and in Room. */
class LogRepository(
    private val dao: LogDao,
    private val scope: CoroutineScope,
    private val showDebug: () -> Boolean = { true },
) : LogSink {
    private val written = java.util.concurrent.atomic.AtomicLong()
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    override fun log(level: LogLevel, message: String) {
        val line = LogLine(System.currentTimeMillis(), level, message)
        Log.println(
            when (level) {
                LogLevel.DEBUG -> Log.DEBUG
                LogLevel.INFO -> Log.INFO
                LogLevel.WARN -> Log.WARN
                LogLevel.ERROR -> Log.ERROR
            },
            "ChannelManager", message,
        )
        if (level != LogLevel.DEBUG || showDebug()) {
            _lines.update { (it + line).takeLast(MAX_LIVE) }
        }
        val trim = written.incrementAndGet() % 200 == 0L
        scope.launch {
            dao.insert(LogEntity(time = line.time, level = level.name, message = message))
            if (trim) dao.trim(MAX_STORED)
        }
    }

    fun clearLive() = _lines.update { emptyList() }

    companion object {
        const val MAX_LIVE = 400
        const val MAX_STORED = 5000
    }
}

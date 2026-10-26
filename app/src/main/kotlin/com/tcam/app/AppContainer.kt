package com.tcam.app

import android.content.Context
import android.os.Build
import com.tcam.app.automation.AutomationController
import com.tcam.app.data.AppDatabase
import com.tcam.app.data.LogRepository
import com.tcam.app.data.SettingsStore
import com.tcam.screen.ScreenChannelEngine
import com.tcam.tdlib.TdlibChannelEngine
import com.tcam.tdlib.TdlibConfig
import com.tcam.tdlib.TdlibSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Manual dependency container: one instance per process. */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(context)
    val database = AppDatabase.create(context)
    val logs = LogRepository(database.logs(), CoroutineScope(SupervisorJob() + Dispatchers.IO))

    val tdlibSession = TdlibSession(
        scope = appScope,
        configProvider = ::tdlibConfig,
        keyProvider = settings,
        log = logs,
    )

    val backgroundEngine = TdlibChannelEngine(tdlibSession, logs)
    val screenEngine = ScreenChannelEngine(context, logs)

    val controller = AutomationController(
        context = context,
        scope = appScope,
        logs = logs,
        runs = database.runs(),
        background = backgroundEngine,
        screen = screenEngine,
    )

    private fun tdlibConfig(): TdlibConfig? {
        val id = settings.apiId
        val hash = settings.apiHash
        if (id <= 0 || hash.isBlank()) return null
        return TdlibConfig(
            apiId = id,
            apiHash = hash,
            databaseDirectory = File(context.filesDir, "tdlib").absolutePath,
            filesDirectory = File(context.filesDir, "tdlib-files").absolutePath,
            systemLanguageCode = Locale.getDefault().language.ifBlank { "en" },
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            systemVersion = "Android ${Build.VERSION.RELEASE}",
            applicationVersion = BuildConfig.VERSION_NAME,
        )
    }

    fun startTdlibIfConfigured() {
        appScope.launch(Dispatchers.IO) { tdlibSession.start() }
    }

    fun restartTdlib() {
        appScope.launch(Dispatchers.IO) { tdlibSession.restart() }
    }
}

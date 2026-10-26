package com.tcam.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tcam.app.R
import com.tcam.app.container
import com.tcam.app.service.OverlayService
import com.tcam.app.service.ScreenRecordService
import com.tcam.core.batch.BatchProcessor
import com.tcam.core.input.ChannelInputParser
import com.tcam.core.model.AutomationMode
import com.tcam.core.model.ChannelAction
import com.tcam.core.model.ChannelInput
import com.tcam.screen.TelegramAccessibilityService
import com.tcam.screen.TelegramApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EnvironmentStatus(
    val telegramInstalled: Boolean = false,
    val accessibilityOn: Boolean = false,
    val overlayAllowed: Boolean = false,
    val notificationsAllowed: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val settings = c.settings

    val batch = c.controller.state
    val logs = c.logs.lines
    val auth = c.tdlibSession.auth
    val runs = c.database.runs().observeAll()
    val recording = ScreenRecordService.state
    val overlayVisible = OverlayService.visible
    val accessibilityService = TelegramAccessibilityService.instance

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    var inputText by mutableStateOf("")
    var queue by mutableStateOf(ChannelInputParser.parse(settings.channelText))
        private set
    var mode by mutableStateOf(settings.mode)
        private set
    var action by mutableStateOf(settings.action)
        private set
    var dryRun by mutableStateOf(settings.dryRun)
        private set
    var environment by mutableStateOf(EnvironmentStatus())
        private set
    var authError by mutableStateOf<String?>(null)
        private set
    var authBusy by mutableStateOf(false)
        private set

    private fun str(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)
    private fun say(text: String) { _messages.tryEmit(text) }

    // ---------------------------------------------------------------- channel list

    fun addFromInput() {
        addText(inputText)
        inputText = ""
    }

    private fun addText(text: String) {
        val entries = text.split('\n', '\r', ',', '،').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val bad = entries.filter { ChannelInputParser.parseOne(it) == null }
        val parsed = ChannelInputParser.parse(text)
        val merged = ChannelInputParser.parse((queue.map { it.raw } + parsed.map { it.raw }).joinToString("\n"))
        updateQueue(merged)
        if (bad.isNotEmpty()) say(str(R.string.unrecognized, bad.joinToString()))
    }

    fun importFile(uri: Uri) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
        }
        if (text == null) say(str(R.string.import_failed)) else addText(text.trimStart(BYTE_ORDER_MARK))
    }

    var keywordText by mutableStateOf("")

    fun addKeyword() {
        val word = keywordText.trim()
        if (word.isEmpty()) return
        addText(ChannelInputParser.keywordRaw(word))
        keywordText = ""
    }

    fun remove(input: ChannelInput) = updateQueue(queue - input)
    fun clearQueue() = updateQueue(emptyList())

    private fun updateQueue(list: List<ChannelInput>) {
        queue = list
        settings.channelText = list.joinToString("\n") { it.raw }
    }

    fun updateMode(m: AutomationMode) { mode = m; settings.mode = m }
    fun updateAction(a: ChannelAction) { action = a; settings.action = a }
    fun updateDryRun(v: Boolean) { dryRun = v; settings.dryRun = v }

    // ---------------------------------------------------------------- batch

    fun start() {
        if (inputText.isNotBlank()) addFromInput()
        if (keywordText.isNotBlank()) addKeyword()
        if (queue.isEmpty()) { say(str(R.string.empty_list)); return }
        viewModelScope.launch {
            when (val r = c.controller.begin(queue, mode, action, dryRun)) {
                is com.tcam.app.automation.AutomationController.StartOutcome.Started -> say(r.explanation)
                is com.tcam.app.automation.AutomationController.StartOutcome.Failed -> say(str(R.string.cannot_start, r.reason))
            }
        }
    }

    fun confirmAndRun() {
        val r = c.controller.confirmAndRun()
        if (r is BatchProcessor.StartResult.Rejected) say(r.reason)
    }

    fun pause() = c.controller.pause()
    fun resume() = c.controller.resume()
    fun stop() = c.controller.stop()
    fun newBatch() = c.controller.clear()
    fun select(taskId: Int, key: String) = c.controller.select(taskId, key)
    fun skip(taskId: Int) = c.controller.skip(taskId)
    fun setIncluded(taskId: Int, v: Boolean) = c.controller.setIncluded(taskId, v)
    fun confirmDeletion(taskId: Int, typed: String): Boolean = c.controller.confirmDeletion(taskId, typed)
    fun confirmKeyword(keyword: String, typed: String): Boolean = c.controller.confirmKeyword(keyword, typed)
    fun reportText(): String = c.controller.reportText()

    // ---------------------------------------------------------------- account

    private fun authCall(block: suspend () -> Result<Unit>) {
        authBusy = true
        authError = null
        viewModelScope.launch {
            block().onFailure { authError = it.message }
            authBusy = false
        }
    }

    fun submitPhone(phone: String) = authCall { c.tdlibSession.submitPhoneNumber(phone) }
    fun submitCode(code: String) = authCall { c.tdlibSession.submitCode(code) }
    fun resendCode() = authCall { c.tdlibSession.resendCode() }
    fun submitEmail(email: String) = authCall { c.tdlibSession.submitEmailAddress(email) }
    fun submitEmailCode(code: String) = authCall { c.tdlibSession.submitEmailCode(code) }
    fun submitPassword(pw: String) = authCall { c.tdlibSession.submitPassword(pw) }
    fun logOut() = authCall { c.tdlibSession.logOut() }
    fun restartTdlib() = c.startTdlibIfConfigured()

    // ---------------------------------------------------------------- settings

    val apiId: Int get() = settings.apiId
    val apiHash: String get() = settings.apiHash

    fun saveApi(id: String, hash: String) {
        val parsed = id.trim().toIntOrNull()
        if (parsed == null || parsed <= 0 || hash.isBlank()) {
            say(str(R.string.error_prefix, "API ID / API hash"))
            return
        }
        settings.apiId = parsed
        settings.apiHash = hash
        say(str(R.string.saved))
        c.restartTdlib()
    }

    fun setLanguage(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }

    fun currentLanguage(): String? = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { null }

    fun refreshEnvironment() {
        val ctx = getApplication<Application>()
        environment = EnvironmentStatus(
            telegramInstalled = TelegramApps.installedPackage(ctx) != null,
            accessibilityOn = TelegramAccessibilityService.instance.value != null,
            overlayAllowed = Settings.canDrawOverlays(ctx),
            notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }

    fun showOverlay(show: Boolean) {
        val ctx = getApplication<Application>()
        if (show) OverlayService.start(ctx) else OverlayService.stop(ctx)
    }

    fun stopRecording() = ScreenRecordService.stop(getApplication())

    fun clearHistory() = viewModelScope.launch(Dispatchers.IO) { c.database.runs().clear() }
}

/** Some editors start UTF-8 text files with a byte-order mark. */
private val BYTE_ORDER_MARK = Char(0xFEFF)

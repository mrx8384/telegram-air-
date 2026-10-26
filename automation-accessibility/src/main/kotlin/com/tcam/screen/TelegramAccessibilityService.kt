package com.tcam.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The accessibility service the user enables in system settings. It only
 * receives events from the official Telegram apps (see the XML config) and
 * never acts on its own: the [ScreenChannelEngine] tells it what to do.
 */
class TelegramAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // State is read on demand from the window tree; events are not needed here.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        _instance.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _instance.value = null
        super.onDestroy()
    }

    /** Root of the window currently shown by Telegram, or null if Telegram is not in front. */
    fun telegramRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() in TelegramApps.PACKAGES) return active
        return windows.asSequence()
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() in TelegramApps.PACKAGES }
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Taps the centre of [bounds]. The bounds always come from a node or OCR, never from fixed coordinates. */
    suspend fun tap(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                },
                null,
            )
            if (!ok && cont.isActive) cont.resume(false)
        }
    }

    /** Screenshot for OCR. Android 11+ only. */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    result.hardwareBuffer.close()
                    if (cont.isActive) cont.resume(soft)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
    }

    companion object {
        private val _instance = MutableStateFlow<TelegramAccessibilityService?>(null)

        /** The connected service, or null when the user has not enabled it. */
        val instance: StateFlow<TelegramAccessibilityService?> = _instance.asStateFlow()
    }
}

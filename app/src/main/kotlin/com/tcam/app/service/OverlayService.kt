package com.tcam.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tcam.app.R
import com.tcam.app.TcamApp
import com.tcam.app.container
import com.tcam.app.ui.MainActivity
import com.tcam.app.ui.label
import com.tcam.core.batch.BatchProcessor
import com.tcam.core.state.BatchPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Optional floating control panel (Start / Pause / Resume / Stop) drawn over
 * other apps, so a screen-mode run can be watched and stopped from Telegram.
 */
class OverlayService : LifecycleService() {
    private var root: View? = null
    private lateinit var status: TextView

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID,
            NotificationCompat.Builder(this, TcamApp.CHANNEL_OVERLAY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_overlay))
                .setOngoing(true)
                .build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (root == null) show()
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun show() {
        val wm = getSystemService(WindowManager::class.java)
        val dp = resources.displayMetrics.density
        val controller = container.controller

        status = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            maxLines = 3
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
        }
        fun button(label: Int, onClick: () -> Unit) = Button(this).apply {
            text = getString(label)
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(R.string.overlay_start) {
                val s = controller.state.value
                if (s.phase == BatchPhase.AWAITING_USER) {
                    val r = controller.confirmAndRun()
                    if (r is BatchProcessor.StartResult.Rejected) {
                        status.text = getString(R.string.overlay_open_app)
                        openApp()
                    }
                } else if (s.phase != BatchPhase.RUNNING && s.phase != BatchPhase.RESOLVING && s.phase != BatchPhase.PAUSED) {
                    openApp()
                }
            })
            addView(button(R.string.pause) { controller.pause() })
            addView(button(R.string.resume) { controller.resume() })
            addView(button(R.string.stop) { controller.stop() })
            addView(button(android.R.string.cancel) { stopSelf() }.apply { text = "✕" })
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xE61B2430.toInt())
                cornerRadius = 14 * dp
            }
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            addView(status)
            addView(buttons)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (8 * dp).toInt()
            y = (120 * dp).toInt()
        }

        // Drag the panel by its status line.
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        status.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(panel, params)
                    true
                }
                else -> false
            }
        }

        wm.addView(panel, params)
        root = panel

        lifecycleScope.launch {
            combine(controller.state, container.screenEngine.step) { s, step -> s to step }.collect { (s, step) ->
                val phase = getString(s.phase.label())
                val current = s.current?.let { it.candidate?.label ?: it.input.displayText }
                status.text = buildString {
                    append(phase)
                    if (s.tasks.isNotEmpty()) append("  ${s.finishedCount}/${s.tasks.size}")
                    current?.let { append("\n").append(it) }
                    if (s.engine?.name == "SCREEN" && (s.phase == BatchPhase.RUNNING || s.phase == BatchPhase.RESOLVING)) append("\n· ").append(step.name)
                }
            }
        }
        _visible.value = true
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    override fun onDestroy() {
        root?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        root = null
        _visible.value = false
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 103
        private val _visible = MutableStateFlow(false)
        val visible: StateFlow<Boolean> = _visible.asStateFlow()

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, OverlayService::class.java))
    }
}

package com.tcam.app.service

import android.app.Activity
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.tcam.app.R
import com.tcam.app.TcamApp
import com.tcam.app.container
import com.tcam.core.engine.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class RecordingState {
    data object Idle : RecordingState()
    data object Recording : RecordingState()
    data class Saved(val name: String) : RecordingState()
    data class Failed(val reason: String) : RecordingState()
}

/**
 * Optional screen recording with MediaProjection. The user grants capture
 * permission each time through Android's own dialog. Output: an MP4 in
 * Movies/ChannelAutoManager, written through MediaStore.
 */
class ScreenRecordService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var fileName: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (resultCode != Activity.RESULT_OK || data == null) {
            _state.value = RecordingState.Failed("Screen capture permission was not granted.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+ requires the foreground service to be running before getMediaProjection().
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID,
            NotificationCompat.Builder(this, TcamApp.CHANNEL_RECORDING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_recording))
                .setOngoing(true)
                .addAction(
                    0, getString(R.string.record_stop),
                    android.app.PendingIntent.getService(
                        this, 2, Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        try {
            startRecording(resultCode, data)
        } catch (e: Exception) {
            container.logs.log(LogLevel.ERROR, "Screen recording failed: ${e.message}")
            _state.value = RecordingState.Failed(e.message ?: e.javaClass.simpleName)
            cleanup(deleteOutput = true)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data) ?: error("Could not start screen capture")
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopRecording() }
        }, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        val (screenW, screenH) = realScreenSize()
        val size = VideoSizes.pick(screenW, screenH)
        val width = size.width
        val height = size.height
        container.logs.log(LogLevel.INFO, "Recording at ${width}x$height (screen ${screenW}x$screenH, ${size.bitrate / 1000} kbps)")

        fileName = "tcam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ChannelAutoManager")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val out = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create the video file")
        uri = out
        val fd = contentResolver.openFileDescriptor(out, "w") ?: error("Could not open the video file")
        pfd = fd

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setVideoSize(width, height)
        rec.setVideoFrameRate(30)
        rec.setVideoEncodingBitRate(size.bitrate)
        rec.setOutputFile(fd.fileDescriptor)
        rec.prepare()
        recorder = rec

        display = mp.createVirtualDisplay(
            "ChannelAutoManagerRecording", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, null,
        )
        rec.start()
        _state.value = RecordingState.Recording
        container.logs.log(LogLevel.INFO, "Screen recording started: $fileName")
    }

    private fun realScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(android.view.WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun stopRecording() {
        if (recorder == null && projection == null) {
            stopSelf()
            return
        }
        var ok = true
        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            ok = false // stop() throws when nothing was recorded
        }
        cleanup(deleteOutput = !ok)
        _state.value = if (ok) RecordingState.Saved("Movies/ChannelAutoManager/$fileName") else RecordingState.Failed("Recording was too short to save.")
        container.logs.log(LogLevel.INFO, if (ok) "Recording saved: $fileName" else "Recording discarded (too short).")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup(deleteOutput: Boolean) {
        runCatching { recorder?.release() }
        recorder = null
        runCatching { display?.release() }
        display = null
        runCatching { projection?.stop() }
        projection = null
        runCatching { pfd?.close() }
        pfd = null
        uri?.let { u ->
            if (deleteOutput) {
                runCatching { contentResolver.delete(u, null, null) }
            } else {
                runCatching {
                    contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                }
            }
        }
        uri = null
    }

    override fun onDestroy() {
        if (recorder != null) stopRecording()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 102
        private const val ACTION_STOP = "com.tcam.app.STOP_RECORDING"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
        val state: StateFlow<RecordingState> = _state.asStateFlow()

        fun captureIntent(context: Context): Intent =
            context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenRecordService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP))
        }
    }
}

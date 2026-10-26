package com.tcam.app.service

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

data class VideoSize(val width: Int, val height: Int, val bitrate: Int)

/**
 * Chooses a recording size the device's H.264 encoder really accepts.
 * Tall phone screens (e.g. 1080x2400) are larger than many encoders allow,
 * which makes MediaRecorder fail, so the size is scaled down until it fits.
 */
object VideoSizes {
    /** Pure sizing rule: keep the aspect ratio, fit inside 1080x1920 (either orientation), multiples of 16. */
    fun fit(screenW: Int, screenH: Int, maxShort: Int = 1080, maxLong: Int = 1920): Pair<Int, Int> {
        val short = minOf(screenW, screenH).coerceAtLeast(16)
        val long = maxOf(screenW, screenH).coerceAtLeast(16)
        val scale = minOf(1.0, maxShort.toDouble() / short, maxLong.toDouble() / long)
        fun align(v: Double) = (v.toInt() / 16 * 16).coerceAtLeast(16)
        val s = align(short * scale)
        val l = align(long * scale)
        return if (screenW <= screenH) s to l else l to s
    }

    fun pick(screenW: Int, screenH: Int): VideoSize {
        val caps = avcEncoderCapabilities()
        var (w, h) = fit(screenW, screenH)
        if (caps != null) {
            var guard = 0
            while (!caps.isSizeSupported(w, h) && guard++ < 12) {
                val next = fit(w, h, (minOf(w, h) * 0.85).toInt(), (maxOf(w, h) * 0.85).toInt())
                if (next.first == w && next.second == h) break
                w = next.first
                h = next.second
            }
        }
        val wanted = (w * h * 4).coerceIn(1_000_000, 8_000_000)
        val bitrate = caps?.bitrateRange?.clamp(wanted) ?: wanted
        return VideoSize(w, h, bitrate)
    }

    private fun avcEncoderCapabilities(): MediaCodecInfo.VideoCapabilities? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } }
            .sortedBy { if (it.isHardwareAccelerated) 0 else 1 }
            .firstNotNullOfOrNull { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }
    }.getOrNull()
}

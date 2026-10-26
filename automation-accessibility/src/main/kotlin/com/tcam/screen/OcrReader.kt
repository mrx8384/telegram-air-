package com.tcam.screen

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class OcrLine(val text: String, val bounds: Rect)

/**
 * On-device OCR with ML Kit. Used only when the accessibility tree does not
 * expose a label. Note: ML Kit's bundled recogniser reads Latin script only,
 * so OCR helps with an English Telegram UI but not a Persian one.
 */
class OcrReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(bitmap: Bitmap): List<OcrLine> = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line -> line.boundingBox?.let { OcrLine(line.text, it) } }
                }
                if (cont.isActive) cont.resume(lines)
            }
            .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
    }

    fun close() = recognizer.close()
}

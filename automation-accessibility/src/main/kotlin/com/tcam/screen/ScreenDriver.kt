package com.tcam.screen

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.tcam.core.engine.LogLevel
import com.tcam.core.engine.LogSink
import kotlinx.coroutines.delay

/** A flattened view of one accessibility node. */
data class UiNode(
    val node: AccessibilityNodeInfo,
    val text: String?,
    val description: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
) {
    /** Text and content description together, as a screen reader would announce it. */
    val label: String get() = listOfNotNull(text, description).joinToString(" ")
}

/** Something to tap: a node, or a rectangle found by OCR. */
sealed class Target {
    abstract val what: String
    data class Node(val ui: UiNode) : Target() { override val what get() = ui.label }
    data class Ocr(val line: OcrLine) : Target() { override val what get() = "\"${line.text}\" (OCR)" }
}

/**
 * Low-level UI helpers. Every tap targets a node or OCR result that matched a
 * known label — there is no blind clicking and no fixed coordinate.
 */
class ScreenDriver(
    private val ocr: OcrReader,
    private val log: LogSink,
    private val pollMillis: Long = 350,
) {
    val service: TelegramAccessibilityService? get() = TelegramAccessibilityService.instance.value

    fun snapshot(): List<UiNode> {
        val root = service?.telegramRoot() ?: return emptyList()
        val out = ArrayList<UiNode>(256)
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60 || out.size > 3000) return
            val r = Rect().also { n.getBoundsInScreen(it) }
            out += UiNode(
                node = n,
                text = n.text?.toString()?.takeIf { it.isNotBlank() },
                description = n.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                bounds = r,
                clickable = n.isClickable,
                editable = n.isEditable,
                scrollable = n.isScrollable,
            )
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    /** Polls the screen until [pick] returns non-null or the timeout passes. */
    suspend fun <T> waitFor(timeoutMillis: Long, pick: (List<UiNode>) -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMillis
        while (true) {
            pick(snapshot())?.let { return it }
            if (System.currentTimeMillis() >= end) return null
            delay(pollMillis)
        }
    }

    fun findExact(nodes: List<UiNode>, labels: Collection<String>): UiNode? =
        nodes.firstOrNull { UiText.equalsAny(it.text, labels) || UiText.equalsAny(it.description, labels) }

    /** Finds a label with the accessibility tree first, then OCR (Android 11+, Latin script). */
    suspend fun findTarget(labels: Collection<String>, timeoutMillis: Long, allowOcr: Boolean = true): Target? {
        waitFor(timeoutMillis) { findExact(it, labels) }?.let { return Target.Node(it) }
        if (!allowOcr || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val bmp = service?.screenshot() ?: return null
        val lines = try { ocr.read(bmp) } finally { bmp.recycle() }
        val hit = lines.firstOrNull { UiText.equalsAny(it.text, labels) } ?: return null
        log.log(LogLevel.DEBUG, "Label not in accessibility tree; found by OCR: ${hit.text}")
        return Target.Ocr(hit)
    }

    suspend fun click(target: Target): Boolean {
        val svc = service ?: return false
        log.log(LogLevel.DEBUG, "Tapping ${target.what}")
        return when (target) {
            is Target.Node -> {
                var n: AccessibilityNodeInfo? = target.ui.node
                var depth = 0
                while (n != null && depth < 6) {
                    if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    n = n.parent
                    depth++
                }
                svc.tap(target.ui.bounds)
            }
            is Target.Ocr -> svc.tap(target.line.bounds)
        }
    }

    suspend fun clickLabel(labels: Collection<String>, timeoutMillis: Long = 6000): Boolean {
        val t = findTarget(labels, timeoutMillis) ?: return false
        return click(t)
    }

    /** Scrolls the first scrollable container forward until [labels] appears. */
    suspend fun scrollUntil(labels: Collection<String>, maxScrolls: Int = 10): UiNode? {
        repeat(maxScrolls) {
            val nodes = snapshot()
            findExact(nodes, labels)?.let { return it }
            val scroller = nodes.firstOrNull { it.scrollable } ?: return null
            if (!scroller.node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return findExact(snapshot(), labels)
            delay(500)
        }
        return findExact(snapshot(), labels)
    }

    suspend fun typeIntoFocusedField(text: String, timeoutMillis: Long = 5000): Boolean {
        val field = waitFor(timeoutMillis) { nodes -> nodes.firstOrNull { it.editable } } ?: return false
        return service?.setText(field.node, text) == true
    }

    fun back(): Boolean = service?.back() == true
}

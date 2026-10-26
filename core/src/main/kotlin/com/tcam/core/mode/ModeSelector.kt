package com.tcam.core.mode

import com.tcam.core.engine.ChannelEngine
import com.tcam.core.model.AutomationMode
import com.tcam.core.model.ChannelAction
import com.tcam.core.model.EngineAvailability
import com.tcam.core.model.EngineType
import com.tcam.core.model.Failure
import com.tcam.core.model.FailureKind

sealed class ModeChoice {
    data class Use(val engine: ChannelEngine, val explanation: String) : ModeChoice()
    data class None(val failure: Failure) : ModeChoice()
}

/**
 * Picks the engine for a run.
 * AUTO prefers TDLib (BACKGROUND) when it supports the action and the user is
 * logged in; otherwise it falls back to screen automation if that is enabled.
 */
object ModeSelector {

    suspend fun choose(
        mode: AutomationMode,
        action: ChannelAction,
        background: ChannelEngine?,
        screen: ChannelEngine?,
    ): ModeChoice {
        return when (mode) {
            AutomationMode.BACKGROUND -> pickExplicit(background, action, EngineType.BACKGROUND)
            AutomationMode.SCREEN -> pickExplicit(screen, action, EngineType.SCREEN)
            AutomationMode.AUTO -> {
                val bgReason = reasonUnusable(background, action)
                if (bgReason == null && background != null) {
                    return ModeChoice.Use(background, "AUTO: using background mode (TDLib) because you are logged in and it supports this action.")
                }
                val scReason = reasonUnusable(screen, action)
                if (scReason == null && screen != null) {
                    return ModeChoice.Use(screen, "AUTO: using screen automation because background mode is unavailable (${bgReason?.message}).")
                }
                ModeChoice.None(
                    Failure(
                        FailureKind.UNSUPPORTED,
                        "No automation method is available. Background: ${bgReason?.message}. Screen: ${scReason?.message}.",
                    ),
                )
            }
        }
    }

    private suspend fun pickExplicit(engine: ChannelEngine?, action: ChannelAction, type: EngineType): ModeChoice {
        val reason = reasonUnusable(engine, action)
        return if (reason == null && engine != null) {
            ModeChoice.Use(engine, if (type == EngineType.BACKGROUND) "Using background mode (TDLib)." else "Using screen automation (Accessibility).")
        } else {
            ModeChoice.None(reason ?: Failure(FailureKind.UNSUPPORTED, "Engine not available."))
        }
    }

    private suspend fun reasonUnusable(engine: ChannelEngine?, action: ChannelAction): Failure? {
        if (engine == null) return Failure(FailureKind.UNSUPPORTED, "not installed in this build")
        if (!engine.supports(action)) return Failure(FailureKind.UNSUPPORTED, "does not support ${action.name}")
        return when (val a = engine.availability()) {
            EngineAvailability.Ready -> null
            is EngineAvailability.Unavailable -> a.failure
        }
    }
}

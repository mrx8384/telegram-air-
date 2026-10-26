package com.tcam.core.engine

import com.tcam.core.model.ActionResult
import com.tcam.core.model.ChannelAction
import com.tcam.core.model.ChannelCandidate
import com.tcam.core.model.ChannelInput
import com.tcam.core.model.EngineAvailability
import com.tcam.core.model.EngineType
import com.tcam.core.model.ResolveResult
import com.tcam.core.model.VerifyResult

/**
 * A way of talking to Telegram. Implemented by the TDLib module and the
 * accessibility module. Implementations must report failures honestly and
 * never claim success they have not observed.
 */
interface ChannelEngine {
    val type: EngineType

    /** Whether the engine can run right now (logged in, permission granted, ...). */
    suspend fun availability(): EngineAvailability

    /** Whether this engine can perform [action] at all. */
    fun supports(action: ChannelAction): Boolean

    /** Finds the channel(s) for one input. Must not change anything. */
    suspend fun resolve(input: ChannelInput): ResolveResult

    /** Performs [action]. Only called after the safety policy and the user approved it. */
    suspend fun execute(candidate: ChannelCandidate, action: ChannelAction): ActionResult

    /** Checks that [action] really took effect. */
    suspend fun verify(candidate: ChannelCandidate, action: ChannelAction): VerifyResult
}

/** Receives human-readable log lines from engines and the batch processor. */
fun interface LogSink {
    fun log(level: LogLevel, message: String)
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

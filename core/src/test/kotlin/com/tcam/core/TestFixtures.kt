package com.tcam.core

import com.tcam.core.engine.ChannelEngine
import com.tcam.core.model.ActionResult
import com.tcam.core.model.ChannelAction
import com.tcam.core.model.ChannelCandidate
import com.tcam.core.model.ChannelInput
import com.tcam.core.model.EngineAvailability
import com.tcam.core.model.EngineType
import com.tcam.core.model.MatchQuality
import com.tcam.core.model.MemberRole
import com.tcam.core.model.ResolveResult
import com.tcam.core.model.VerifyResult

fun candidate(
    key: String,
    title: String = key,
    username: String? = key,
    role: MemberRole = MemberRole.MEMBER,
    canDelete: Boolean = false,
    isChannel: Boolean = true,
    match: MatchQuality = MatchQuality.EXACT_IDENTIFIER,
    engine: EngineType = EngineType.BACKGROUND,
) = ChannelCandidate(key, title, username, isChannel, role, canDelete, 10, null, match, engine)

/** Scripted engine for tests: answers from fixed tables and records calls. */
class ScriptedEngine(
    private val resolveAnswers: Map<String, List<ResolveResult>>,
    private val executeAnswers: MutableMap<String, MutableList<ActionResult>> = mutableMapOf(),
    private val verifyAnswer: VerifyResult = VerifyResult.Verified("ok"),
    private val available: EngineAvailability = EngineAvailability.Ready,
    override val type: EngineType = EngineType.BACKGROUND,
) : ChannelEngine {
    val executed = mutableListOf<Pair<String, ChannelAction>>()
    private val resolveCalls = mutableMapOf<String, Int>()

    override suspend fun availability() = available
    override fun supports(action: ChannelAction) = true

    override suspend fun resolve(input: ChannelInput): ResolveResult {
        val key = input.displayText
        val n = resolveCalls.merge(key, 1, Int::plus)!! - 1
        val answers = resolveAnswers[key] ?: return ResolveResult.NotFound("none")
        return answers[minOf(n, answers.lastIndex)]
    }

    override suspend fun execute(candidate: ChannelCandidate, action: ChannelAction): ActionResult {
        executed += candidate.key to action
        val list = executeAnswers[candidate.key]
        return if (list.isNullOrEmpty()) ActionResult.Done("done ${candidate.key}") else list.removeAt(0)
    }

    override suspend fun verify(candidate: ChannelCandidate, action: ChannelAction) = verifyAnswer
}

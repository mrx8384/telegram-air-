package com.tcam.core.engine

import com.tcam.core.input.TitleNormalizer
import com.tcam.core.model.ChannelCandidate
import com.tcam.core.model.ChannelInput
import com.tcam.core.model.MatchQuality
import com.tcam.core.model.ResolveResult

/**
 * Shared rules for turning search results into a [ResolveResult].
 * Never picks one channel out of several on its own.
 */
object ChannelMatcher {

    fun classify(input: ChannelInput, results: List<ChannelCandidate>): ResolveResult {
        val channels = results.filter { it.isChannel }.distinctBy { it.key }
        return when (input) {
            is ChannelInput.Username -> {
                val exact = channels.filter { it.username.equals(input.username, ignoreCase = true) }
                when {
                    exact.size == 1 -> ResolveResult.Found(exact[0].copy(match = MatchQuality.EXACT_IDENTIFIER))
                    exact.size > 1 -> ResolveResult.Ambiguous(exact)
                    results.any { it.username.equals(input.username, ignoreCase = true) } ->
                        ResolveResult.NotFound("@${input.username} exists but is not a channel.")
                    else -> ResolveResult.NotFound("No channel with username @${input.username}.")
                }
            }
            is ChannelInput.InviteLink -> when (channels.size) {
                0 -> ResolveResult.NotFound("The invite link did not lead to a channel.")
                1 -> ResolveResult.Found(channels[0].copy(match = MatchQuality.EXACT_IDENTIFIER))
                else -> ResolveResult.Ambiguous(channels)
            }
            is ChannelInput.Title -> classifyTitle(input.title, channels)
        }
    }

    private fun classifyTitle(title: String, channels: List<ChannelCandidate>): ResolveResult {
        val wanted = TitleNormalizer.normalize(title)
        if (wanted.isEmpty()) return ResolveResult.NotFound("Empty channel name.")

        // A bare word that equals a username counts as an identifier match.
        val byUsername = channels.filter { it.username.equals(wanted.removePrefix("@"), ignoreCase = true) }
        val exact = channels.filter { TitleNormalizer.normalize(it.title) == wanted }
        val identified = (byUsername.map { it.copy(match = MatchQuality.EXACT_IDENTIFIER) } +
            exact.filter { e -> byUsername.none { it.key == e.key } }.map { it.copy(match = MatchQuality.EXACT_TITLE) })

        return when {
            identified.size == 1 -> ResolveResult.Found(identified[0])
            identified.size > 1 -> ResolveResult.Ambiguous(identified)
            else -> {
                val partial = channels.filter { TitleNormalizer.normalize(it.title).contains(wanted) || wanted.contains(TitleNormalizer.normalize(it.title)) }
                if (partial.isEmpty()) ResolveResult.NotFound("No channel named \"$title\".")
                else ResolveResult.Ambiguous(partial.map { it.copy(match = MatchQuality.PARTIAL) })
            }
        }
    }
}

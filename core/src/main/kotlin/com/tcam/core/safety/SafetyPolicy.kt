package com.tcam.core.safety

import com.tcam.core.input.TitleNormalizer
import com.tcam.core.model.ChannelAction
import com.tcam.core.model.ChannelCandidate
import com.tcam.core.model.MatchQuality
import com.tcam.core.model.MemberRole

sealed class SafetyDecision {
    data object Allowed : SafetyDecision()
    data class Denied(val reason: String) : SafetyDecision()
}

/**
 * Rules every action must pass before it runs. The engines also check with
 * Telegram, but these rules stop obviously unsafe work before any request.
 */
object SafetyPolicy {

    fun check(candidate: ChannelCandidate, action: ChannelAction): SafetyDecision {
        if (action == ChannelAction.PREVIEW) return SafetyDecision.Allowed

        if (!candidate.isChannel) {
            return SafetyDecision.Denied("\"${candidate.title}\" is not a channel.")
        }
        if (candidate.match == MatchQuality.PARTIAL) {
            return SafetyDecision.Denied("Only a partial name match. Pick the exact channel first.")
        }

        return when (action) {
            ChannelAction.PREVIEW -> SafetyDecision.Allowed
            ChannelAction.ARCHIVE, ChannelAction.LEAVE -> when (candidate.role) {
                MemberRole.OWNER, MemberRole.ADMIN, MemberRole.MEMBER -> SafetyDecision.Allowed
                MemberRole.UNKNOWN ->
                    if (candidate.engine == com.tcam.core.model.EngineType.SCREEN) SafetyDecision.Allowed
                    else SafetyDecision.Denied("Could not confirm that this account is a member.")
                MemberRole.NOT_MEMBER -> SafetyDecision.Denied("This account is not a member of the channel.")
                MemberRole.BANNED -> SafetyDecision.Denied("This account is banned from the channel.")
            }
            ChannelAction.REMOVE -> when (candidate.role) {
                MemberRole.NOT_MEMBER -> SafetyDecision.Denied("This account is not a member of the channel.")
                MemberRole.BANNED -> SafetyDecision.Denied("This account is banned from the channel.")
                else -> if (candidate.match == MatchQuality.EXACT_TITLE && candidate.role == MemberRole.UNKNOWN && candidate.engine == com.tcam.core.model.EngineType.BACKGROUND)
                    SafetyDecision.Denied("Could not confirm that this account is a member.")
                else SafetyDecision.Allowed
            }
            ChannelAction.DELETE_OWNED -> when {
                candidate.match == MatchQuality.EXACT_TITLE ->
                    SafetyDecision.Denied("Deletion needs a @username, a link, or a channel you picked from the list — a title alone is not enough.")
                candidate.role != MemberRole.OWNER && candidate.role != MemberRole.UNKNOWN ->
                    SafetyDecision.Denied("Only the owner can delete a channel. This account is ${candidate.role.name.lowercase().replace('_', ' ')}.")
                candidate.role == MemberRole.UNKNOWN && candidate.engine == com.tcam.core.model.EngineType.BACKGROUND ->
                    SafetyDecision.Denied("Ownership could not be confirmed.")
                candidate.engine == com.tcam.core.model.EngineType.BACKGROUND && !candidate.canDeleteForEveryone ->
                    SafetyDecision.Denied("Telegram does not allow this account to delete the channel.")
                else -> SafetyDecision.Allowed
            }
        }
    }

    /**
     * Each permanent deletion needs its own confirmation: the user must type the
     * channel's @username (or its exact title when it has no username).
     */
    /** For keyword batches: the user confirms every deletion at once by typing the keyword itself. */
    fun keywordConfirmationMatches(keyword: String, typed: String): Boolean {
        val t = TitleNormalizer.normalize(typed)
        return t.isNotEmpty() && t == TitleNormalizer.normalize(keyword)
    }

    fun deletionConfirmationMatches(candidate: ChannelCandidate, typed: String): Boolean {
        val t = typed.trim()
        if (t.isEmpty()) return false
        val username = candidate.username
        return if (!username.isNullOrBlank()) {
            t.removePrefix("@").equals(username, ignoreCase = true)
        } else {
            TitleNormalizer.normalize(t) == TitleNormalizer.normalize(candidate.title)
        }
    }

    /** What the user has to type to confirm deleting [candidate]. */
    fun deletionConfirmationPhrase(candidate: ChannelCandidate): String =
        candidate.username?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: candidate.title
}

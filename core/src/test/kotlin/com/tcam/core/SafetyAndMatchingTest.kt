package com.tcam.core

import com.tcam.core.engine.ChannelMatcher
import com.tcam.core.model.ChannelAction
import com.tcam.core.model.ChannelInput
import com.tcam.core.model.MatchQuality
import com.tcam.core.model.MemberRole
import com.tcam.core.model.ResolveResult
import com.tcam.core.safety.SafetyDecision
import com.tcam.core.safety.SafetyPolicy
import com.tcam.core.state.TaskState
import com.tcam.core.state.TaskStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyAndMatchingTest {

    @Test fun deleteRequiresOwnerAndTelegramPermission() {
        val member = candidate("a", role = MemberRole.MEMBER)
        val ownerNoPerm = candidate("b", role = MemberRole.OWNER, canDelete = false)
        val owner = candidate("c", role = MemberRole.OWNER, canDelete = true)
        assertTrue(SafetyPolicy.check(member, ChannelAction.DELETE_OWNED) is SafetyDecision.Denied)
        assertTrue(SafetyPolicy.check(ownerNoPerm, ChannelAction.DELETE_OWNED) is SafetyDecision.Denied)
        assertEquals(SafetyDecision.Allowed, SafetyPolicy.check(owner, ChannelAction.DELETE_OWNED))
    }

    @Test fun deleteNeverOnTitleOnlyOrPartialMatch() {
        val byTitle = candidate("c", role = MemberRole.OWNER, canDelete = true, match = MatchQuality.EXACT_TITLE)
        val partial = candidate("c", role = MemberRole.OWNER, canDelete = true, match = MatchQuality.PARTIAL)
        val picked = candidate("c", role = MemberRole.OWNER, canDelete = true, match = MatchQuality.USER_SELECTED)
        assertTrue(SafetyPolicy.check(byTitle, ChannelAction.DELETE_OWNED) is SafetyDecision.Denied)
        assertTrue(SafetyPolicy.check(partial, ChannelAction.LEAVE) is SafetyDecision.Denied)
        assertEquals(SafetyDecision.Allowed, SafetyPolicy.check(picked, ChannelAction.DELETE_OWNED))
    }

    @Test fun leaveNeedsMembershipAndChannel() {
        assertTrue(SafetyPolicy.check(candidate("a", role = MemberRole.NOT_MEMBER), ChannelAction.LEAVE) is SafetyDecision.Denied)
        assertTrue(SafetyPolicy.check(candidate("a", isChannel = false), ChannelAction.LEAVE) is SafetyDecision.Denied)
        assertEquals(SafetyDecision.Allowed, SafetyPolicy.check(candidate("a"), ChannelAction.LEAVE))
        assertEquals(SafetyDecision.Allowed, SafetyPolicy.check(candidate("a", role = MemberRole.NOT_MEMBER), ChannelAction.PREVIEW))
    }

    @Test fun deletionConfirmationText() {
        val c = candidate("k", title = "News", username = "news_ch", role = MemberRole.OWNER, canDelete = true)
        assertTrue(SafetyPolicy.deletionConfirmationMatches(c, "@NEWS_CH"))
        assertFalse(SafetyPolicy.deletionConfirmationMatches(c, "News"))
        val noUser = c.copy(username = null)
        assertTrue(SafetyPolicy.deletionConfirmationMatches(noUser, " news "))
    }

    @Test fun titleMatchingIsCareful() {
        val results = listOf(
            candidate("1", title = "Daily News", username = "dn1"),
            candidate("2", title = "Daily News", username = "dn2"),
            candidate("3", title = "Daily News Extra", username = "dn3"),
        )
        val r = ChannelMatcher.classify(ChannelInput.Title("daily news", "daily news"), results)
        assertTrue(r is ResolveResult.Ambiguous)
        assertEquals(2, (r as ResolveResult.Ambiguous).candidates.size)

        val single = ChannelMatcher.classify(ChannelInput.Title("Daily News Extra", ""), results)
        assertEquals(MatchQuality.EXACT_TITLE, (single as ResolveResult.Found).candidate.match)

        val partial = ChannelMatcher.classify(ChannelInput.Title("Daily", ""), results)
        assertTrue((partial as ResolveResult.Ambiguous).candidates.all { it.match == MatchQuality.PARTIAL })

        val user = ChannelMatcher.classify(ChannelInput.Username("DN2", ""), results)
        assertEquals("2", (user as ResolveResult.Found).candidate.key)
    }

    @Test fun stateMachineRejectsSkippingConfirmation() {
        assertFalse(TaskStateMachine.canTransition(TaskState.RESOLVED, TaskState.EXECUTING))
        assertFalse(TaskStateMachine.canTransition(TaskState.NEEDS_SELECTION, TaskState.CONFIRMED))
        assertTrue(TaskStateMachine.canTransition(TaskState.AWAITING_CONFIRMATION, TaskState.CONFIRMED))
    }
}

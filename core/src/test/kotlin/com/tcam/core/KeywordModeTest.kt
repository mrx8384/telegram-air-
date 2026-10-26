package com.tcam.core

import com.tcam.core.batch.BatchConfig
import com.tcam.core.batch.BatchProcessor
import com.tcam.core.engine.ChannelMatcher
import com.tcam.core.engine.LogSink
import com.tcam.core.input.ChannelInputParser
import com.tcam.core.model.ChannelAction
import com.tcam.core.model.ChannelInput
import com.tcam.core.model.EngineType
import com.tcam.core.model.MatchQuality
import com.tcam.core.model.MemberRole
import com.tcam.core.model.ResolveResult
import com.tcam.core.state.BatchPhase
import com.tcam.core.state.TaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeywordModeTest {

    @Test fun parsesKeywordPrefixes() {
        assertEquals(ChannelInput.Keyword("crypto", "keyword: crypto"), ChannelInputParser.parseOne("keyword: crypto"))
        assertEquals("فروش", (ChannelInputParser.parseOne("کلمه: فروش") as ChannelInput.Keyword).keyword)
        assertEquals(null, ChannelInputParser.parseOne("keyword:   "))
        assertEquals(ChannelInputParser.parseOne(ChannelInputParser.keywordRaw("abc")), ChannelInput.Keyword("abc", "keyword: abc"))
    }

    @Test fun keywordMatchesEveryChannelContainingTheWord() {
        val list = listOf(
            candidate("1", title = "Crypto News", engine = EngineType.SCREEN, role = MemberRole.UNKNOWN),
            candidate("2", title = "best crypto signals", engine = EngineType.SCREEN, role = MemberRole.UNKNOWN),
            candidate("3", title = "Cooking", engine = EngineType.SCREEN, role = MemberRole.UNKNOWN),
            candidate("4", title = "Crypto group", isChannel = false),
        )
        val r = ChannelMatcher.classifyKeyword("CRYPTO", list) as ResolveResult.Many
        assertEquals(listOf("1", "2"), r.candidates.map { it.key })
        assertTrue(r.candidates.all { it.match == MatchQuality.KEYWORD })
        assertTrue(ChannelMatcher.classifyKeyword("xyz", list) is ResolveResult.NotFound)
    }

    @Test fun keywordBatchExpandsAndNeedsOneTypedConfirmation() = runTest {
        val found = listOf(
            candidate("a", title = "Crypto A", username = null, role = MemberRole.UNKNOWN, engine = EngineType.SCREEN, match = MatchQuality.KEYWORD),
            candidate("b", title = "Crypto B", username = null, role = MemberRole.UNKNOWN, engine = EngineType.SCREEN, match = MatchQuality.KEYWORD),
            candidate("c", title = "Crypto C", username = null, role = MemberRole.UNKNOWN, engine = EngineType.SCREEN, match = MatchQuality.KEYWORD),
        )
        val engine = ScriptedEngine(mapOf("🔎 crypto" to listOf(ResolveResult.Many(found))), type = EngineType.SCREEN)
        val p = BatchProcessor(this, LogSink { _, _ -> }, BatchConfig(delayBetweenTasksMillis = 1))
        p.load(listOf(ChannelInput.Keyword("crypto", "keyword: crypto")), ChannelAction.REMOVE, engine, "t", dryRun = false)
        p.beginResolve(); advanceUntilIdle()

        val tasks = p.state.value.tasks
        assertEquals(3, tasks.size)
        assertTrue(tasks.all { it.state == TaskState.AWAITING_CONFIRMATION })
        assertEquals(mapOf("crypto" to 3), p.keywordsAwaitingConfirmation())

        // Keep one channel: untick it.
        p.setIncluded(tasks[1].id, false)
        assertTrue(p.startExecution() is BatchProcessor.StartResult.Rejected)
        assertFalse(p.confirmKeyword("crypto", "crypt"))
        assertTrue(p.confirmKeyword("crypto", " Crypto "))
        assertEquals(BatchProcessor.StartResult.Started, p.startExecution()); advanceUntilIdle()

        assertEquals(BatchPhase.FINISHED, p.state.value.phase)
        assertEquals(listOf("a" to ChannelAction.REMOVE, "c" to ChannelAction.REMOVE), engine.executed)
        assertEquals(TaskState.SKIPPED, p.state.value.tasks[1].state)
    }

    @Test fun keywordDryRunTouchesNothing() = runTest {
        val found = listOf(candidate("a", title = "Crypto A", role = MemberRole.UNKNOWN, engine = EngineType.SCREEN, match = MatchQuality.KEYWORD))
        val engine = ScriptedEngine(mapOf("🔎 crypto" to listOf(ResolveResult.Many(found))), type = EngineType.SCREEN)
        val p = BatchProcessor(this, LogSink { _, _ -> }, BatchConfig(delayBetweenTasksMillis = 1))
        p.load(listOf(ChannelInput.Keyword("crypto", "keyword: crypto")), ChannelAction.REMOVE, engine, "t", dryRun = true)
        p.beginResolve(); advanceUntilIdle()
        p.confirmKeyword("crypto", "crypto")
        p.startExecution(); advanceUntilIdle()
        assertTrue(engine.executed.isEmpty())
        assertEquals(TaskState.SUCCEEDED, p.state.value.tasks[0].state)
    }
}

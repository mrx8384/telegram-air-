package com.tcam.core

import com.tcam.core.batch.BatchConfig
import com.tcam.core.batch.BatchProcessor
import com.tcam.core.engine.LogSink
import com.tcam.core.mode.ModeChoice
import com.tcam.core.mode.ModeSelector
import com.tcam.core.model.ActionResult
import com.tcam.core.model.AutomationMode
import com.tcam.core.model.ChannelAction
import com.tcam.core.model.ChannelInput
import com.tcam.core.model.EngineAvailability
import com.tcam.core.model.EngineType
import com.tcam.core.model.Failure
import com.tcam.core.model.FailureKind
import com.tcam.core.model.MemberRole
import com.tcam.core.model.ResolveResult
import com.tcam.core.state.BatchPhase
import com.tcam.core.state.TaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatchProcessorTest {
    private val logs = mutableListOf<String>()
    private val sink = LogSink { _, m -> logs += m }

    private fun u(name: String) = ChannelInput.Username(name, "@$name")

    private fun TestScope.processor() = BatchProcessor(this, sink, BatchConfig(delayBetweenTasksMillis = 10, networkRetryDelayMillis = 10))

    @Test fun fullFlowLeaveWithVerification() = runTest {
        val engine = ScriptedEngine(
            mapOf(
                "@aaaaa" to listOf(ResolveResult.Found(candidate("aaaaa"))),
                "@bbbbb" to listOf(ResolveResult.NotFound("missing")),
            ),
        )
        val p = processor()
        p.load(listOf(u("aaaaa"), u("bbbbb")), ChannelAction.LEAVE, engine, "test", dryRun = false)
        p.beginResolve(); advanceUntilIdle()
        assertEquals(BatchPhase.AWAITING_USER, p.state.value.phase)
        assertEquals(listOf(TaskState.AWAITING_CONFIRMATION, TaskState.FAILED), p.state.value.tasks.map { it.state })
        assertTrue(engine.executed.isEmpty())

        assertEquals(BatchProcessor.StartResult.Started, p.startExecution()); advanceUntilIdle()
        assertEquals(BatchPhase.FINISHED, p.state.value.phase)
        assertEquals(TaskState.SUCCEEDED, p.state.value.tasks[0].state)
        assertEquals(listOf("aaaaa" to ChannelAction.LEAVE), engine.executed)
    }

    @Test fun dryRunChangesNothing() = runTest {
        val engine = ScriptedEngine(mapOf("@aaaaa" to listOf(ResolveResult.Found(candidate("aaaaa")))))
        val p = processor()
        p.load(listOf(u("aaaaa")), ChannelAction.LEAVE, engine, "test", dryRun = true)
        p.beginResolve(); advanceUntilIdle()
        p.startExecution(); advanceUntilIdle()
        assertEquals(TaskState.SUCCEEDED, p.state.value.tasks[0].state)
        assertTrue(engine.executed.isEmpty())
    }

    @Test fun everyDeletionNeedsItsOwnConfirmation() = runTest {
        val engine = ScriptedEngine(
            mapOf(
                "@owned1" to listOf(ResolveResult.Found(candidate("owned1", role = MemberRole.OWNER, canDelete = true))),
                "@owned2" to listOf(ResolveResult.Found(candidate("owned2", role = MemberRole.OWNER, canDelete = true))),
            ),
        )
        val p = processor()
        p.load(listOf(u("owned1"), u("owned2")), ChannelAction.DELETE_OWNED, engine, "test", dryRun = false)
        p.beginResolve(); advanceUntilIdle()

        assertTrue(p.startExecution() is BatchProcessor.StartResult.Rejected)
        assertEquals(false, p.confirmDeletion(1, "@owned2"))
        assertEquals(true, p.confirmDeletion(1, "@owned1"))
        assertTrue(p.startExecution() is BatchProcessor.StartResult.Rejected)
        assertEquals(true, p.confirmDeletion(2, "owned2"))
        assertEquals(BatchProcessor.StartResult.Started, p.startExecution()); advanceUntilIdle()
        assertEquals(2, engine.executed.size)
    }

    @Test fun ambiguousNeedsSelectionAndUnpickedIsSkipped() = runTest {
        val a = candidate("1", title = "Same", username = "same_one", match = com.tcam.core.model.MatchQuality.EXACT_TITLE)
        val b = candidate("2", title = "Same", username = "same_two", match = com.tcam.core.model.MatchQuality.EXACT_TITLE)
        val engine = ScriptedEngine(mapOf("Same" to listOf(ResolveResult.Ambiguous(listOf(a, b))), "Other" to listOf(ResolveResult.Ambiguous(listOf(a, b)))))
        val p = processor()
        p.load(listOf(ChannelInput.Title("Same", "Same"), ChannelInput.Title("Other", "Other")), ChannelAction.LEAVE, engine, "t", dryRun = false)
        p.beginResolve(); advanceUntilIdle()
        assertEquals(TaskState.NEEDS_SELECTION, p.state.value.tasks[0].state)
        p.select(1, "2")
        assertEquals(TaskState.AWAITING_CONFIRMATION, p.state.value.tasks[0].state)
        p.startExecution(); advanceUntilIdle()
        assertEquals(listOf("2" to ChannelAction.LEAVE), engine.executed)
        assertEquals(TaskState.SKIPPED, p.state.value.tasks[1].state)
    }

    @Test fun honoursRateLimitWaitAndGivesUpOnLongWaits() = runTest {
        val engine = ScriptedEngine(
            mapOf(
                "@aaaaa" to listOf(ResolveResult.Error(Failure(FailureKind.RATE_LIMITED, "slow down", 5)), ResolveResult.Found(candidate("aaaaa"))),
                "@bbbbb" to listOf(ResolveResult.Error(Failure(FailureKind.RATE_LIMITED, "slow down", 86400))),
            ),
        )
        val p = processor()
        p.load(listOf(u("aaaaa"), u("bbbbb")), ChannelAction.PREVIEW, engine, "t", dryRun = false)
        p.beginResolve(); advanceUntilIdle()
        assertEquals(TaskState.SUCCEEDED, p.state.value.tasks[0].state)
        assertEquals(TaskState.FAILED, p.state.value.tasks[1].state)
        assertTrue(logs.any { it.contains("wait 5 s") })
    }

    @Test fun executionFailureIsReportedNotHidden() = runTest {
        val engine = ScriptedEngine(
            mapOf("@aaaaa" to listOf(ResolveResult.Found(candidate("aaaaa")))),
            executeAnswers = mutableMapOf("aaaaa" to mutableListOf(ActionResult.Failed(Failure(FailureKind.PERMISSION_DENIED, "CHAT_ADMIN_REQUIRED")))),
        )
        val p = processor()
        p.load(listOf(u("aaaaa")), ChannelAction.LEAVE, engine, "t", dryRun = false)
        p.beginResolve(); advanceUntilIdle(); p.startExecution(); advanceUntilIdle()
        assertEquals(TaskState.FAILED, p.state.value.tasks[0].state)
        assertEquals("CHAT_ADMIN_REQUIRED", p.state.value.tasks[0].message)
    }

    @Test fun stopCancelsOpenTasks() = runTest {
        val engine = ScriptedEngine(mapOf("@aaaaa" to listOf(ResolveResult.Found(candidate("aaaaa")))))
        val p = processor()
        p.load(listOf(u("aaaaa"), u("bbbbb")), ChannelAction.LEAVE, engine, "t", dryRun = false)
        p.beginResolve(); advanceUntilIdle()
        p.stop()
        assertEquals(BatchPhase.STOPPED, p.state.value.phase)
        assertEquals(TaskState.CANCELLED, p.state.value.tasks[0].state)
        assertTrue(engine.executed.isEmpty())
    }

    @Test fun autoModePrefersBackgroundThenScreen() = runTest {
        val bgReady = ScriptedEngine(emptyMap(), type = EngineType.BACKGROUND)
        val bgOff = ScriptedEngine(emptyMap(), available = EngineAvailability.Unavailable(Failure(FailureKind.NOT_LOGGED_IN, "not logged in")), type = EngineType.BACKGROUND)
        val screen = ScriptedEngine(emptyMap(), type = EngineType.SCREEN)
        val a = ModeSelector.choose(AutomationMode.AUTO, ChannelAction.LEAVE, bgReady, screen)
        assertEquals(EngineType.BACKGROUND, (a as ModeChoice.Use).engine.type)
        val b = ModeSelector.choose(AutomationMode.AUTO, ChannelAction.LEAVE, bgOff, screen)
        assertEquals(EngineType.SCREEN, (b as ModeChoice.Use).engine.type)
        assertTrue(b.explanation.contains("not logged in"))
        assertTrue(ModeSelector.choose(AutomationMode.BACKGROUND, ChannelAction.LEAVE, bgOff, screen) is ModeChoice.None)
    }
}

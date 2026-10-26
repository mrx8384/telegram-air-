package com.tcam.app.automation

import android.content.Context
import com.tcam.app.data.LogRepository
import com.tcam.app.data.RunDao
import com.tcam.app.data.RunEntity
import com.tcam.app.service.AutomationService
import com.tcam.core.batch.BatchProcessor
import com.tcam.core.batch.BatchState
import com.tcam.core.engine.ChannelEngine
import com.tcam.core.engine.LogLevel
import com.tcam.core.mode.ModeChoice
import com.tcam.core.mode.ModeSelector
import com.tcam.core.model.AutomationMode
import com.tcam.core.model.ChannelAction
import com.tcam.core.model.ChannelInput
import com.tcam.core.state.BatchPhase
import com.tcam.core.state.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Glue between the UI, the batch processor and the Android services:
 * picks the engine, keeps a foreground service alive while work runs, and
 * saves the final report to Room.
 */
class AutomationController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val logs: LogRepository,
    private val runs: RunDao,
    private val background: ChannelEngine,
    private val screen: ChannelEngine,
) {
    private val processor = BatchProcessor(scope, logs)
    val state: StateFlow<BatchState> = processor.state
    private var startedAt = 0L
    private var saved = false

    init {
        scope.launch {
            state.distinctUntilChangedBy { it.phase }.collect { s ->
                when (s.phase) {
                    BatchPhase.RESOLVING, BatchPhase.RUNNING -> AutomationService.start(context)
                    BatchPhase.FINISHED, BatchPhase.STOPPED -> {
                        saveReport(s)
                        AutomationService.stop(context)
                    }
                    BatchPhase.AWAITING_USER -> AutomationService.stop(context)
                    else -> Unit
                }
            }
        }
    }

    sealed class StartOutcome {
        data class Started(val explanation: String) : StartOutcome()
        data class Failed(val reason: String) : StartOutcome()
    }

    /** Stage 1: pick the engine and find every channel. Nothing is changed. */
    suspend fun begin(inputs: List<ChannelInput>, mode: AutomationMode, action: ChannelAction, dryRun: Boolean): StartOutcome {
        if (processor.isBusy) return StartOutcome.Failed("A batch is already running.")
        if (inputs.isEmpty()) return StartOutcome.Failed("The channel list is empty.")
        val choice = ModeSelector.choose(mode, action, background, screen)
        return when (choice) {
            is ModeChoice.None -> {
                logs.log(LogLevel.ERROR, choice.failure.message)
                StartOutcome.Failed(choice.failure.message)
            }
            is ModeChoice.Use -> {
                processor.reset()
                processor.load(inputs, action, choice.engine, choice.explanation, dryRun)
                startedAt = System.currentTimeMillis()
                saved = false
                processor.beginResolve()
                StartOutcome.Started(choice.explanation)
            }
        }
    }

    fun select(taskId: Int, key: String) = processor.select(taskId, key)
    fun skip(taskId: Int) = processor.skip(taskId)
    fun setIncluded(taskId: Int, included: Boolean) = processor.setIncluded(taskId, included)
    fun confirmDeletion(taskId: Int, typed: String) = processor.confirmDeletion(taskId, typed)
    fun confirmKeyword(keyword: String, typed: String) = processor.confirmKeyword(keyword, typed)

    /** Stage 2: run the confirmed tasks. */
    fun confirmAndRun(): BatchProcessor.StartResult = processor.startExecution()

    fun pause() = processor.pause()
    fun resume() = processor.resume()
    fun stop() = processor.stop()

    fun clear() {
        if (processor.isBusy) return
        processor.reset()
    }

    fun reportText(): String = processor.report().asText()

    private fun saveReport(s: BatchState) {
        if (saved || s.tasks.isEmpty()) return
        saved = true
        val report = processor.report()
        scope.launch {
            runs.insert(
                RunEntity(
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    action = s.action.name,
                    engine = s.engine?.name,
                    dryRun = s.dryRun,
                    total = s.tasks.size,
                    succeeded = s.tasks.count { it.state == TaskState.SUCCEEDED },
                    failed = s.tasks.count { it.state == TaskState.FAILED || it.state == TaskState.BLOCKED },
                    outcome = s.phase.name,
                    report = report.asText(),
                ),
            )
        }
    }
}

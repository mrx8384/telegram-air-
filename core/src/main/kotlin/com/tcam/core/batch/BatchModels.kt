package com.tcam.core.batch

import com.tcam.core.model.ChannelAction
import com.tcam.core.model.ChannelCandidate
import com.tcam.core.model.ChannelInput
import com.tcam.core.model.EngineType
import com.tcam.core.model.FailureKind
import com.tcam.core.state.BatchPhase
import com.tcam.core.state.TaskState

data class ChannelTask(
    val id: Int,
    val input: ChannelInput,
    val action: ChannelAction,
    val state: TaskState = TaskState.PENDING,
    val candidate: ChannelCandidate? = null,
    /** Choices shown to the user when the name matched several channels. */
    val options: List<ChannelCandidate> = emptyList(),
    /** The user can untick a task in the preview to leave it out of the run. */
    val included: Boolean = true,
    /** Separate explicit confirmation, required for every permanent deletion. */
    val deletionConfirmed: Boolean = false,
    val message: String? = null,
    val failureKind: FailureKind? = null,
) {
    val needsDeletionConfirmation: Boolean
        get() = action == ChannelAction.DELETE_OWNED && state == TaskState.AWAITING_CONFIRMATION && included && !deletionConfirmed
}

data class BatchState(
    val phase: BatchPhase = BatchPhase.IDLE,
    val tasks: List<ChannelTask> = emptyList(),
    val action: ChannelAction = ChannelAction.PREVIEW,
    val engine: EngineType? = null,
    val engineExplanation: String? = null,
    val dryRun: Boolean = true,
    val currentTaskId: Int? = null,
) {
    val finishedCount: Int get() = tasks.count { it.state.isFinal }
    val progress: Float get() = if (tasks.isEmpty()) 0f else finishedCount.toFloat() / tasks.size
    val current: ChannelTask? get() = tasks.firstOrNull { it.id == currentTaskId }
}

data class BatchConfig(
    /** Extra attempts for network errors. */
    val maxRetries: Int = 2,
    /** Longest server-requested wait we will honour; longer waits fail the task instead. */
    val maxRateLimitWaitSeconds: Int = 300,
    /** Pause between channels so we behave like a person, not a script. */
    val delayBetweenTasksMillis: Long = 1500,
    val networkRetryDelayMillis: Long = 3000,
)

data class BatchReport(
    val action: ChannelAction,
    val engine: EngineType?,
    val dryRun: Boolean,
    val counts: Map<TaskState, Int>,
    val lines: List<String>,
) {
    fun asText(): String = buildString {
        appendLine("Action: ${action.name}${if (dryRun) " (dry run)" else ""}")
        appendLine("Method: ${engine?.name ?: "-"}")
        counts.filterValues { it > 0 }.forEach { (s, n) -> appendLine("${s.name}: $n") }
        appendLine()
        lines.forEach { appendLine(it) }
    }
}

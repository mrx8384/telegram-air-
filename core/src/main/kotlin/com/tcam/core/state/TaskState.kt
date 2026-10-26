package com.tcam.core.state

/** Life cycle of one channel task. */
enum class TaskState {
    PENDING,
    RESOLVING,
    NEEDS_SELECTION,
    RESOLVED,
    BLOCKED,
    AWAITING_CONFIRMATION,
    CONFIRMED,
    EXECUTING,
    VERIFYING,
    SUCCEEDED,
    UNVERIFIED,
    FAILED,
    SKIPPED,
    CANCELLED;

    val isFinal: Boolean get() = this in FINAL

    companion object {
        val FINAL = setOf(SUCCEEDED, UNVERIFIED, FAILED, SKIPPED, CANCELLED, BLOCKED)
    }
}

class IllegalTransitionException(from: TaskState, to: TaskState) :
    IllegalStateException("Illegal task transition $from -> $to")

/** Allowed transitions. Anything else is a bug and throws. */
object TaskStateMachine {
    private val allowed: Map<TaskState, Set<TaskState>> = mapOf(
        TaskState.PENDING to setOf(TaskState.RESOLVING, TaskState.CANCELLED),
        TaskState.RESOLVING to setOf(TaskState.RESOLVED, TaskState.NEEDS_SELECTION, TaskState.FAILED, TaskState.CANCELLED, TaskState.PENDING),
        TaskState.NEEDS_SELECTION to setOf(TaskState.RESOLVED, TaskState.SKIPPED, TaskState.CANCELLED),
        TaskState.RESOLVED to setOf(TaskState.AWAITING_CONFIRMATION, TaskState.BLOCKED, TaskState.SUCCEEDED, TaskState.SKIPPED, TaskState.CANCELLED),
        TaskState.AWAITING_CONFIRMATION to setOf(TaskState.CONFIRMED, TaskState.SKIPPED, TaskState.CANCELLED),
        TaskState.CONFIRMED to setOf(TaskState.EXECUTING, TaskState.SUCCEEDED, TaskState.SKIPPED, TaskState.CANCELLED),
        TaskState.EXECUTING to setOf(TaskState.VERIFYING, TaskState.FAILED, TaskState.CANCELLED, TaskState.CONFIRMED),
        TaskState.VERIFYING to setOf(TaskState.SUCCEEDED, TaskState.UNVERIFIED, TaskState.FAILED, TaskState.CANCELLED),
    )

    fun canTransition(from: TaskState, to: TaskState): Boolean = allowed[from]?.contains(to) == true

    fun transition(from: TaskState, to: TaskState): TaskState {
        if (!canTransition(from, to)) throw IllegalTransitionException(from, to)
        return to
    }
}

/** Life cycle of a whole batch. */
enum class BatchPhase {
    IDLE,
    RESOLVING,
    AWAITING_USER,
    RUNNING,
    PAUSED,
    FINISHED,
    STOPPED,
}

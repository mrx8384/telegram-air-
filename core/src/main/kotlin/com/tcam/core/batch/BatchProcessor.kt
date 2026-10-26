package com.tcam.core.batch

import com.tcam.core.engine.ChannelEngine
import com.tcam.core.engine.LogLevel
import com.tcam.core.engine.LogSink
import com.tcam.core.model.ActionResult
import com.tcam.core.model.ChannelAction
import com.tcam.core.model.ChannelInput
import com.tcam.core.model.Failure
import com.tcam.core.model.FailureKind
import com.tcam.core.model.MatchQuality
import com.tcam.core.model.ResolveResult
import com.tcam.core.model.VerifyResult
import com.tcam.core.safety.SafetyDecision
import com.tcam.core.safety.SafetyPolicy
import com.tcam.core.state.BatchPhase
import com.tcam.core.state.TaskState
import com.tcam.core.state.TaskStateMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs a batch in two user-gated stages:
 *
 * 1. [beginResolve]: find every channel, check permissions, build a preview.
 * 2. [startExecution]: after the user confirmed the preview (and typed a
 *    confirmation for every deletion), process tasks one by one and verify each.
 *
 * [pause], [resume] and [stop] (emergency stop) work in both stages.
 */
class BatchProcessor(
    private val scope: CoroutineScope,
    private val log: LogSink,
    private val config: BatchConfig = BatchConfig(),
) {
    private val _state = MutableStateFlow(BatchState())
    val state: StateFlow<BatchState> = _state.asStateFlow()

    private val paused = MutableStateFlow(false)
    private var engine: ChannelEngine? = null
    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    fun load(
        inputs: List<ChannelInput>,
        action: ChannelAction,
        engine: ChannelEngine,
        explanation: String,
        dryRun: Boolean,
    ) {
        check(!isBusy) { "A batch is already running" }
        require(inputs.isNotEmpty()) { "No channels to process" }
        this.engine = engine
        paused.value = false
        _state.value = BatchState(
            phase = BatchPhase.IDLE,
            tasks = inputs.mapIndexed { i, input -> ChannelTask(id = i + 1, input = input, action = action) },
            action = action,
            engine = engine.type,
            engineExplanation = explanation,
            dryRun = dryRun,
        )
        log.log(LogLevel.INFO, explanation)
        log.log(LogLevel.INFO, "Loaded ${inputs.size} channel(s) for ${action.name}${if (dryRun) " — dry run, nothing will change" else ""}.")
    }

    // ---------------------------------------------------------------- stage 1

    fun beginResolve() {
        val engine = checkNotNull(engine) { "Call load() first" }
        check(!isBusy) { "Already running" }
        setPhase(BatchPhase.RESOLVING)
        job = scope.launch {
            try {
                for (task in _state.value.tasks) {
                    if (task.state != TaskState.PENDING) continue
                    awaitNotPaused(BatchPhase.RESOLVING)
                    resolveTask(engine, task.id)
                }
                val tasks = _state.value.tasks
                val waiting = tasks.any { it.state == TaskState.AWAITING_CONFIRMATION || it.state == TaskState.NEEDS_SELECTION }
                setCurrent(null)
                if (waiting) {
                    setPhase(BatchPhase.AWAITING_USER)
                    log.log(LogLevel.INFO, "Preview ready. Review the matches and confirm to continue.")
                } else {
                    setPhase(BatchPhase.FINISHED)
                    log.log(LogLevel.INFO, "Nothing to do. ${summaryLine()}")
                }
            } catch (e: CancellationException) {
                setCurrent(null)
                throw e
            }
        }
    }

    private suspend fun resolveTask(engine: ChannelEngine, id: Int) {
        setCurrent(id)
        move(id, TaskState.RESOLVING)
        val input = task(id).input
        log.log(LogLevel.INFO, "Searching for ${input.displayText}…")

        var attempt = 0
        while (true) {
            when (val r = engine.resolve(input)) {
                is ResolveResult.Found -> {
                    move(id, TaskState.RESOLVED) { it.copy(candidate = r.candidate, options = emptyList()) }
                    log.log(LogLevel.INFO, "Found ${r.candidate.label} — role: ${r.candidate.role.name.lowercase()}.")
                    applySafety(id)
                    return
                }
                is ResolveResult.Many -> {
                    expandKeywordTask(id, r.candidates)
                    log.log(LogLevel.INFO, "${input.displayText}: found ${r.candidates.size} channel(s): ${r.candidates.joinToString { it.label }}")
                    return
                }
                is ResolveResult.Ambiguous -> {
                    move(id, TaskState.NEEDS_SELECTION) {
                        it.copy(options = r.candidates, message = "${r.candidates.size} channels match. Pick the right one.")
                    }
                    log.log(LogLevel.WARN, "${input.displayText}: ${r.candidates.size} matches — waiting for you to pick one.")
                    return
                }
                is ResolveResult.NotFound -> {
                    move(id, TaskState.FAILED) { it.copy(message = r.reason, failureKind = FailureKind.NOT_FOUND) }
                    log.log(LogLevel.WARN, "${input.displayText}: ${r.reason}")
                    return
                }
                is ResolveResult.Error -> {
                    if (shouldRetry(r.failure, attempt)) {
                        attempt++
                        waitBeforeRetry(r.failure)
                        continue
                    }
                    move(id, TaskState.FAILED) { it.copy(message = r.failure.message, failureKind = r.failure.kind) }
                    log.log(LogLevel.ERROR, "${input.displayText}: ${r.failure.message}")
                    return
                }
            }
        }
    }

    /** A keyword found several channels: the task takes the first, and one new task is added per other channel. */
    private fun expandKeywordTask(id: Int, candidates: List<com.tcam.core.model.ChannelCandidate>) {
        val first = candidates.first()
        move(id, TaskState.RESOLVED) { it.copy(candidate = first, options = emptyList()) }
        applySafety(id)
        if (candidates.size == 1) return
        _state.update { s ->
            val original = s.tasks.first { it.id == id }
            var nextId = s.tasks.maxOf { it.id } + 1
            val extra = candidates.drop(1).map { c ->
                val t = ChannelTask(id = nextId++, input = original.input, action = original.action, state = TaskState.RESOLVED, candidate = c)
                applySafetyTo(t)
            }
            val index = s.tasks.indexOfFirst { it.id == id }
            s.copy(tasks = s.tasks.subList(0, index + 1) + extra + s.tasks.subList(index + 1, s.tasks.size))
        }
    }

    /** Pure version of [applySafety] for tasks that are not in the state yet. */
    private fun applySafetyTo(t: ChannelTask): ChannelTask {
        val c = t.candidate ?: return t
        if (t.action == ChannelAction.PREVIEW) return t.copy(state = TaskStateMachine.transition(t.state, TaskState.SUCCEEDED), message = describe(c))
        return when (val d = SafetyPolicy.check(c, t.action)) {
            SafetyDecision.Allowed -> t.copy(state = TaskStateMachine.transition(t.state, TaskState.AWAITING_CONFIRMATION), message = describe(c))
            is SafetyDecision.Denied -> t.copy(state = TaskStateMachine.transition(t.state, TaskState.BLOCKED), message = d.reason, failureKind = FailureKind.PERMISSION_DENIED)
        }
    }

    /** Applies the safety policy to a RESOLVED task. */
    private fun applySafety(id: Int) {
        val t = task(id)
        val candidate = t.candidate ?: return
        if (t.action == ChannelAction.PREVIEW) {
            move(id, TaskState.SUCCEEDED) { it.copy(message = describe(candidate)) }
            return
        }
        when (val d = SafetyPolicy.check(candidate, t.action)) {
            SafetyDecision.Allowed -> move(id, TaskState.AWAITING_CONFIRMATION) { it.copy(message = describe(candidate)) }
            is SafetyDecision.Denied -> {
                move(id, TaskState.BLOCKED) { it.copy(message = d.reason, failureKind = FailureKind.PERMISSION_DENIED) }
                log.log(LogLevel.WARN, "${candidate.label}: blocked — ${d.reason}")
            }
        }
    }

    /** The user picked one of several matches. */
    fun select(taskId: Int, candidateKey: String) {
        val t = task(taskId)
        check(t.state == TaskState.NEEDS_SELECTION) { "Task $taskId is not waiting for a selection" }
        val chosen = t.options.firstOrNull { it.key == candidateKey } ?: error("Unknown option $candidateKey")
        move(taskId, TaskState.RESOLVED) { it.copy(candidate = chosen.copy(match = MatchQuality.USER_SELECTED), options = emptyList()) }
        log.log(LogLevel.INFO, "You picked ${chosen.label} for ${t.input.displayText}.")
        applySafety(taskId)
        if (_state.value.phase == BatchPhase.AWAITING_USER && _state.value.tasks.none { it.state == TaskState.AWAITING_CONFIRMATION || it.state == TaskState.NEEDS_SELECTION }) {
            setPhase(BatchPhase.FINISHED)
        }
    }

    fun skip(taskId: Int) {
        val t = task(taskId)
        if (t.state.isFinal) return
        if (TaskStateMachine.canTransition(t.state, TaskState.SKIPPED)) {
            move(taskId, TaskState.SKIPPED) { it.copy(message = "Skipped by you.") }
        }
    }

    fun setIncluded(taskId: Int, included: Boolean) {
        _state.update { s -> s.copy(tasks = s.tasks.map { if (it.id == taskId) it.copy(included = included) else it }) }
    }

    /** Separate confirmation for one permanent deletion. Returns false if the typed text does not match. */
    fun confirmDeletion(taskId: Int, typed: String): Boolean {
        val t = task(taskId)
        val candidate = t.candidate ?: return false
        if (!t.action.mayDelete || t.state != TaskState.AWAITING_CONFIRMATION) return false
        if (!SafetyPolicy.deletionConfirmationMatches(candidate, typed)) return false
        _state.update { s -> s.copy(tasks = s.tasks.map { if (it.id == taskId) it.copy(deletionConfirmed = true) else it }) }
        log.log(LogLevel.WARN, "Deletion of ${candidate.label} confirmed by you.")
        return true
    }

    /** Keywords whose found channels still wait for the typed keyword confirmation. */
    fun keywordsAwaitingConfirmation(): Map<String, Int> =
        _state.value.tasks.filter { it.needsDeletionConfirmation && it.keyword != null }
            .groupingBy { it.keyword!! }.eachCount()

    /**
     * Confirms every pending deletion found by [keyword] at once. The user must type the
     * keyword itself, after seeing the full list of channels.
     */
    fun confirmKeyword(keyword: String, typed: String): Boolean {
        if (!SafetyPolicy.keywordConfirmationMatches(keyword, typed)) return false
        var count = 0
        _state.update { s ->
            s.copy(tasks = s.tasks.map {
                if (it.keyword == keyword && it.needsDeletionConfirmation) { count++; it.copy(deletionConfirmed = true) } else it
            })
        }
        log.log(LogLevel.WARN, "You confirmed removing $count channel(s) found by \"$keyword\".")
        return true
    }

    // ---------------------------------------------------------------- stage 2

    sealed class StartResult {
        data object Started : StartResult()
        data class Rejected(val reason: String) : StartResult()
    }

    /** Called when the user confirms the preview. */
    fun startExecution(): StartResult {
        val engine = engine ?: return StartResult.Rejected("No batch loaded.")
        if (isBusy) return StartResult.Rejected("Still working.")
        val s = _state.value
        if (s.phase != BatchPhase.AWAITING_USER) return StartResult.Rejected("Nothing is waiting for confirmation.")
        val pendingDeletes = s.tasks.filter { it.needsDeletionConfirmation }
        if (pendingDeletes.isNotEmpty()) {
            return StartResult.Rejected("Confirm each deletion separately first: ${pendingDeletes.joinToString { it.candidate?.label ?: it.input.displayText }}")
        }

        for (t in s.tasks) {
            when {
                t.state == TaskState.AWAITING_CONFIRMATION && t.included -> move(t.id, TaskState.CONFIRMED)
                t.state == TaskState.AWAITING_CONFIRMATION -> move(t.id, TaskState.SKIPPED) { it.copy(message = "Left out of the run.") }
                t.state == TaskState.NEEDS_SELECTION -> move(t.id, TaskState.SKIPPED) { it.copy(message = "No channel was picked.") }
            }
        }
        setPhase(BatchPhase.RUNNING)
        log.log(LogLevel.INFO, "Starting ${_state.value.tasks.count { it.state == TaskState.CONFIRMED }} confirmed task(s).")

        job = scope.launch {
            var first = true
            for (t in _state.value.tasks) {
                if (t.state != TaskState.CONFIRMED) continue
                awaitNotPaused(BatchPhase.RUNNING)
                if (!first && !_state.value.dryRun) delay(config.delayBetweenTasksMillis)
                first = false
                runTask(engine, t.id)
            }
            setCurrent(null)
            setPhase(BatchPhase.FINISHED)
            log.log(LogLevel.INFO, "Finished. ${summaryLine()}")
        }
        return StartResult.Started
    }

    private suspend fun runTask(engine: ChannelEngine, id: Int) {
        setCurrent(id)
        val t = task(id)
        val candidate = t.candidate ?: run {
            move(id, TaskState.SKIPPED) { it.copy(message = "No channel.") }
            return
        }
        val verb = verb(t.action)

        if (_state.value.dryRun) {
            move(id, TaskState.SUCCEEDED) { it.copy(message = "Dry run: would $verb ${candidate.label}. Nothing was changed.") }
            log.log(LogLevel.INFO, "Dry run: would $verb ${candidate.label}.")
            return
        }

        // Re-check right before acting, in case anything changed since the preview.
        val decision = SafetyPolicy.check(candidate, t.action)
        if (decision is SafetyDecision.Denied || (t.action.mayDelete && !t.deletionConfirmed)) {
            val reason = (decision as? SafetyDecision.Denied)?.reason ?: "Deletion was not confirmed."
            move(id, TaskState.EXECUTING)
            move(id, TaskState.FAILED) { it.copy(message = reason, failureKind = FailureKind.PERMISSION_DENIED) }
            return
        }

        move(id, TaskState.EXECUTING)
        log.log(LogLevel.INFO, "${verb.replaceFirstChar { it.uppercase() }} ${candidate.label}…")
        var attempt = 0
        while (true) {
            when (val r = engine.execute(candidate, t.action)) {
                is ActionResult.Done -> {
                    log.log(LogLevel.INFO, r.detail)
                    break
                }
                is ActionResult.Failed -> {
                    if (shouldRetry(r.failure, attempt)) {
                        attempt++
                        waitBeforeRetry(r.failure)
                        continue
                    }
                    move(id, TaskState.FAILED) { it.copy(message = r.failure.message, failureKind = r.failure.kind) }
                    log.log(LogLevel.ERROR, "${candidate.label}: ${r.failure.message}")
                    return
                }
            }
        }

        move(id, TaskState.VERIFYING)
        when (val v = engine.verify(candidate, t.action)) {
            is VerifyResult.Verified -> {
                move(id, TaskState.SUCCEEDED) { it.copy(message = v.detail) }
                log.log(LogLevel.INFO, "Verified: ${v.detail}")
            }
            is VerifyResult.NotVerified -> {
                move(id, TaskState.UNVERIFIED) { it.copy(message = v.detail) }
                log.log(LogLevel.WARN, "Could not verify ${candidate.label}: ${v.detail}")
            }
        }
    }

    // ---------------------------------------------------------------- control

    fun pause() {
        val p = _state.value.phase
        if (p == BatchPhase.RUNNING || p == BatchPhase.RESOLVING) {
            paused.value = true
            log.log(LogLevel.INFO, "Pausing after the current step…")
        }
    }

    fun resume() {
        if (paused.value) {
            paused.value = false
            log.log(LogLevel.INFO, "Resumed.")
        }
    }

    /** Emergency stop: cancels whatever is running and marks open tasks as cancelled. */
    fun stop() {
        job?.cancel()
        job = null
        paused.value = false
        _state.update { s ->
            s.copy(
                phase = if (s.phase == BatchPhase.IDLE) BatchPhase.IDLE else BatchPhase.STOPPED,
                currentTaskId = null,
                tasks = s.tasks.map { t ->
                    if (!t.state.isFinal) t.copy(state = TaskState.CANCELLED, message = t.message ?: "Stopped by you.") else t
                },
            )
        }
        log.log(LogLevel.WARN, "Stopped.")
    }

    /** Clears everything without logging; used before loading a new batch. */
    fun reset() {
        job?.cancel()
        job = null
        paused.value = false
        engine = null
        _state.value = BatchState()
    }

    fun report(): BatchReport {
        val s = _state.value
        return BatchReport(
            action = s.action,
            engine = s.engine,
            dryRun = s.dryRun,
            counts = TaskState.entries.associateWith { st -> s.tasks.count { it.state == st } },
            lines = s.tasks.map { t ->
                "#${t.id} ${t.input.displayText} → ${t.candidate?.label ?: "-"}: ${t.state.name}${t.message?.let { " — $it" } ?: ""}"
            },
        )
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun awaitNotPaused(activePhase: BatchPhase) {
        if (paused.value) {
            setPhase(BatchPhase.PAUSED)
            paused.first { !it }
            setPhase(activePhase)
        }
    }

    private fun shouldRetry(failure: Failure, attempt: Int): Boolean {
        if (!failure.isTransient || attempt >= config.maxRetries) return false
        if (failure.kind == FailureKind.RATE_LIMITED) {
            val wait = failure.retryAfterSeconds ?: return false
            return wait <= config.maxRateLimitWaitSeconds
        }
        return true
    }

    private suspend fun waitBeforeRetry(failure: Failure) {
        if (failure.kind == FailureKind.RATE_LIMITED) {
            val seconds = failure.retryAfterSeconds ?: 0
            log.log(LogLevel.WARN, "Telegram asked us to wait $seconds s. Waiting as requested…")
            delay(seconds * 1000L)
        } else {
            log.log(LogLevel.WARN, "${failure.message} — retrying shortly.")
            delay(config.networkRetryDelayMillis)
        }
    }

    private fun task(id: Int): ChannelTask = _state.value.tasks.first { it.id == id }

    private fun move(id: Int, to: TaskState, change: (ChannelTask) -> ChannelTask = { it }) {
        _state.update { s ->
            s.copy(
                tasks = s.tasks.map { t ->
                    if (t.id == id) change(t).copy(state = TaskStateMachine.transition(t.state, to)) else t
                },
            )
        }
    }

    private fun setPhase(phase: BatchPhase) = _state.update { it.copy(phase = phase) }
    private fun setCurrent(id: Int?) = _state.update { it.copy(currentTaskId = id) }

    private fun summaryLine(): String {
        val tasks = _state.value.tasks
        return TaskState.entries.mapNotNull { st ->
            val n = tasks.count { it.state == st }
            if (n > 0) "${st.name.lowercase()}: $n" else null
        }.joinToString(", ")
    }

    private fun describe(c: com.tcam.core.model.ChannelCandidate): String = buildString {
        append(c.label)
        append(" · ").append(c.role.name.lowercase().replace('_', ' '))
        c.memberCount?.let { append(" · ").append(it).append(" subscribers") }
    }

    companion object {
        fun verb(action: ChannelAction): String = when (action) {
            ChannelAction.PREVIEW -> "preview"
            ChannelAction.ARCHIVE -> "archive"
            ChannelAction.LEAVE -> "leave"
            ChannelAction.DELETE_OWNED -> "permanently delete"
            ChannelAction.REMOVE -> "remove (delete if owned, otherwise leave)"
        }
    }
}

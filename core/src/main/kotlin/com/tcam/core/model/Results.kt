package com.tcam.core.model

/** Why an engine call failed. Each kind maps to a user-facing explanation. */
enum class FailureKind {
    TELEGRAM_NOT_INSTALLED,
    ACCESSIBILITY_DISABLED,
    NOT_LOGGED_IN,
    LOGIN_EXPIRED,
    NOT_FOUND,
    NOT_A_CHANNEL,
    PERMISSION_DENIED,
    RATE_LIMITED,
    NETWORK,
    UI_CHANGED,
    UNSUPPORTED,
    CANCELLED,
    TELEGRAM_ERROR,
    UNKNOWN,
}

data class Failure(
    val kind: FailureKind,
    val message: String,
    /** For [FailureKind.RATE_LIMITED]: seconds Telegram asked us to wait. */
    val retryAfterSeconds: Int? = null,
) {
    /** Failures worth retrying after a short wait. Rate limits are waited out, never bypassed. */
    val isTransient: Boolean get() = kind == FailureKind.NETWORK || kind == FailureKind.RATE_LIMITED
}

sealed class ResolveResult {
    data class Found(val candidate: ChannelCandidate) : ResolveResult()
    data class Ambiguous(val candidates: List<ChannelCandidate>) : ResolveResult()
    /** Keyword search: every candidate is a match and becomes its own task. */
    data class Many(val candidates: List<ChannelCandidate>) : ResolveResult()
    data class NotFound(val reason: String) : ResolveResult()
    data class Error(val failure: Failure) : ResolveResult()
}

sealed class ActionResult {
    data class Done(val detail: String) : ActionResult()
    data class Failed(val failure: Failure) : ActionResult()
}

sealed class VerifyResult {
    data class Verified(val detail: String) : VerifyResult()
    data class NotVerified(val detail: String) : VerifyResult()
}

sealed class EngineAvailability {
    data object Ready : EngineAvailability()
    data class Unavailable(val failure: Failure) : EngineAvailability()
}

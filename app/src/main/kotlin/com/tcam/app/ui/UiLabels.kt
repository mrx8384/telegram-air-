package com.tcam.app.ui

import androidx.annotation.StringRes
import com.tcam.app.R
import com.tcam.core.model.AutomationMode
import com.tcam.core.model.ChannelAction
import com.tcam.core.model.FailureKind
import com.tcam.core.model.MemberRole
import com.tcam.core.state.BatchPhase
import com.tcam.core.state.TaskState

@StringRes fun BatchPhase.label(): Int = when (this) {
    BatchPhase.IDLE -> R.string.phase_IDLE
    BatchPhase.RESOLVING -> R.string.phase_RESOLVING
    BatchPhase.AWAITING_USER -> R.string.phase_AWAITING_USER
    BatchPhase.RUNNING -> R.string.phase_RUNNING
    BatchPhase.PAUSED -> R.string.phase_PAUSED
    BatchPhase.FINISHED -> R.string.phase_FINISHED
    BatchPhase.STOPPED -> R.string.phase_STOPPED
}

@StringRes fun TaskState.label(): Int = when (this) {
    TaskState.PENDING -> R.string.state_PENDING
    TaskState.RESOLVING -> R.string.state_RESOLVING
    TaskState.NEEDS_SELECTION -> R.string.state_NEEDS_SELECTION
    TaskState.RESOLVED -> R.string.state_RESOLVED
    TaskState.BLOCKED -> R.string.state_BLOCKED
    TaskState.AWAITING_CONFIRMATION -> R.string.state_AWAITING_CONFIRMATION
    TaskState.CONFIRMED -> R.string.state_CONFIRMED
    TaskState.EXECUTING -> R.string.state_EXECUTING
    TaskState.VERIFYING -> R.string.state_VERIFYING
    TaskState.SUCCEEDED -> R.string.state_SUCCEEDED
    TaskState.UNVERIFIED -> R.string.state_UNVERIFIED
    TaskState.FAILED -> R.string.state_FAILED
    TaskState.SKIPPED -> R.string.state_SKIPPED
    TaskState.CANCELLED -> R.string.state_CANCELLED
}

@StringRes fun MemberRole.label(): Int = when (this) {
    MemberRole.OWNER -> R.string.role_OWNER
    MemberRole.ADMIN -> R.string.role_ADMIN
    MemberRole.MEMBER -> R.string.role_MEMBER
    MemberRole.NOT_MEMBER -> R.string.role_NOT_MEMBER
    MemberRole.BANNED -> R.string.role_BANNED
    MemberRole.UNKNOWN -> R.string.role_UNKNOWN
}

@StringRes fun FailureKind.label(): Int = when (this) {
    FailureKind.TELEGRAM_NOT_INSTALLED -> R.string.failure_TELEGRAM_NOT_INSTALLED
    FailureKind.ACCESSIBILITY_DISABLED -> R.string.failure_ACCESSIBILITY_DISABLED
    FailureKind.NOT_LOGGED_IN -> R.string.failure_NOT_LOGGED_IN
    FailureKind.LOGIN_EXPIRED -> R.string.failure_LOGIN_EXPIRED
    FailureKind.NOT_FOUND -> R.string.failure_NOT_FOUND
    FailureKind.NOT_A_CHANNEL -> R.string.failure_NOT_A_CHANNEL
    FailureKind.PERMISSION_DENIED -> R.string.failure_PERMISSION_DENIED
    FailureKind.RATE_LIMITED -> R.string.failure_RATE_LIMITED
    FailureKind.NETWORK -> R.string.failure_NETWORK
    FailureKind.UI_CHANGED -> R.string.failure_UI_CHANGED
    FailureKind.UNSUPPORTED -> R.string.failure_UNSUPPORTED
    FailureKind.CANCELLED -> R.string.failure_CANCELLED
    FailureKind.TELEGRAM_ERROR -> R.string.failure_TELEGRAM_ERROR
    FailureKind.UNKNOWN -> R.string.failure_UNKNOWN
}

@StringRes fun ChannelAction.label(): Int = when (this) {
    ChannelAction.PREVIEW -> R.string.action_preview
    ChannelAction.ARCHIVE -> R.string.action_archive
    ChannelAction.LEAVE -> R.string.action_leave
    ChannelAction.DELETE_OWNED -> R.string.action_delete
    ChannelAction.REMOVE -> R.string.action_remove
}

@StringRes fun ChannelAction.description(): Int = when (this) {
    ChannelAction.PREVIEW -> R.string.action_preview_desc
    ChannelAction.ARCHIVE -> R.string.action_archive_desc
    ChannelAction.LEAVE -> R.string.action_leave_desc
    ChannelAction.DELETE_OWNED -> R.string.action_delete_desc
    ChannelAction.REMOVE -> R.string.action_remove_desc
}

@StringRes fun AutomationMode.label(): Int = when (this) {
    AutomationMode.AUTO -> R.string.mode_auto
    AutomationMode.BACKGROUND -> R.string.mode_background
    AutomationMode.SCREEN -> R.string.mode_screen
}

@StringRes fun AutomationMode.description(): Int = when (this) {
    AutomationMode.AUTO -> R.string.mode_auto_desc
    AutomationMode.BACKGROUND -> R.string.mode_background_desc
    AutomationMode.SCREEN -> R.string.mode_screen_desc
}

package com.tcam.tdlib

import com.tcam.core.model.Failure
import com.tcam.core.model.FailureKind

/** Maps TDLib error codes/messages to app failure kinds. */
object TdlibErrors {
    private val RETRY_AFTER = Regex("retry after (\\d+)", RegexOption.IGNORE_CASE)
    private val FLOOD_WAIT = Regex("FLOOD_WAIT_(\\d+)")

    private val NOT_FOUND = listOf(
        "USERNAME_NOT_OCCUPIED", "USERNAME_INVALID", "INVITE_HASH_EXPIRED", "INVITE_HASH_INVALID",
        "CHANNEL_INVALID", "Chat not found", "CHANNEL_PRIVATE", "Supergroup not found",
    )
    private val PERMISSION = listOf(
        "CHAT_ADMIN_REQUIRED", "CHANNEL_PRIVATE_FORBIDDEN", "CHAT_FORBIDDEN", "Not enough rights",
        "Can't delete", "CHANNEL_TOO_LARGE", "USER_NOT_PARTICIPANT", "Chat can't be deleted",
    )

    fun map(code: Int, message: String): Failure {
        RETRY_AFTER.find(message)?.let {
            return Failure(FailureKind.RATE_LIMITED, "Telegram rate limit: $message", it.groupValues[1].toIntOrNull())
        }
        FLOOD_WAIT.find(message)?.let {
            return Failure(FailureKind.RATE_LIMITED, "Telegram rate limit: $message", it.groupValues[1].toIntOrNull())
        }
        return when {
            code == 429 -> Failure(FailureKind.RATE_LIMITED, "Telegram rate limit: $message")
            code == 401 -> Failure(FailureKind.LOGIN_EXPIRED, "Your Telegram session expired. Log in again. ($message)")
            NOT_FOUND.any { message.contains(it, ignoreCase = true) } -> Failure(FailureKind.NOT_FOUND, message)
            code == 403 || PERMISSION.any { message.contains(it, ignoreCase = true) } -> Failure(FailureKind.PERMISSION_DENIED, message)
            message.contains("timeout", ignoreCase = true) || message.contains("network", ignoreCase = true) ->
                Failure(FailureKind.NETWORK, message)
            else -> Failure(FailureKind.TELEGRAM_ERROR, "Telegram error $code: $message")
        }
    }
}

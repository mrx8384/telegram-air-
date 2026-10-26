package com.tcam.core.model

/** How the app talks to Telegram. */
enum class AutomationMode {
    /** Drives the official Telegram app through an AccessibilityService. */
    SCREEN,

    /** Uses TDLib with the user's own logged-in account. */
    BACKGROUND,

    /** Picks BACKGROUND when possible, otherwise SCREEN. */
    AUTO,
}

/** The concrete engine that performs the work (AUTO resolves to one of these). */
enum class EngineType { SCREEN, BACKGROUND }

/**
 * What to do with a channel.
 *
 * - [PREVIEW]: find and show information only. Never changes anything.
 * - [ARCHIVE]: move the chat to the Archive list. You stay subscribed; the channel is untouched.
 * - [LEAVE]: unsubscribe. The channel keeps existing for everyone else.
 * - [DELETE_OWNED]: permanently delete the channel for all subscribers. Owner only.
 * - [REMOVE]: clear the channel out of your Telegram: delete it if you own it, otherwise leave it.
 */
enum class ChannelAction {
    PREVIEW,
    ARCHIVE,
    LEAVE,
    DELETE_OWNED,
    REMOVE;

    val isDestructive: Boolean get() = this == LEAVE || this == DELETE_OWNED || this == REMOVE

    /** Actions that may permanently delete a channel and therefore need an explicit typed confirmation. */
    val mayDelete: Boolean get() = this == DELETE_OWNED || this == REMOVE
    val changesAnything: Boolean get() = this != PREVIEW
}

/** One line of user input after parsing. */
sealed class ChannelInput {
    /** The original text the user typed, kept for logs and reports. */
    abstract val raw: String

    /** A public username, from "@name", "t.me/name" or "tg://resolve?domain=name". */
    data class Username(val username: String, override val raw: String) : ChannelInput()

    /** A private invite link such as "t.me/+AbC" or "t.me/joinchat/AbC". */
    data class InviteLink(val link: String, override val raw: String) : ChannelInput()

    /** Free text: a channel title. Can match several channels. */
    data class Title(val title: String, override val raw: String) : ChannelInput()

    /**
     * A word to search for: every channel in the user's own Telegram whose name
     * contains it becomes a task. The user reviews the list and confirms by typing the word.
     */
    data class Keyword(val keyword: String, override val raw: String) : ChannelInput()

    val displayText: String
        get() = when (this) {
            is Username -> "@$username"
            is InviteLink -> link
            is Title -> title
            is Keyword -> "🔎 $keyword"
        }
}

/** The account's relationship with a channel. */
enum class MemberRole { OWNER, ADMIN, MEMBER, NOT_MEMBER, BANNED, UNKNOWN }

/** How strongly a candidate is known to be the channel the user meant. */
enum class MatchQuality {
    /** Username or invite link resolved to exactly this chat. */
    EXACT_IDENTIFIER,

    /** The title matches exactly (after normalisation). Several channels may share a title. */
    EXACT_TITLE,

    /** Only part of the title matches. Never used without the user picking it. */
    PARTIAL,

    /** The user picked this candidate from a list. */
    USER_SELECTED,

    /** The name contains the keyword the user asked for (keyword search in the user's own chats). */
    KEYWORD,
}

/** A channel found by an engine. */
data class ChannelCandidate(
    /** Engine-specific stable key (TDLib chat id, or a UI search-result key). */
    val key: String,
    val title: String,
    val username: String?,
    val isChannel: Boolean,
    val role: MemberRole,
    /** True only when the engine confirmed Telegram will allow deleting it for everyone. */
    val canDeleteForEveryone: Boolean,
    val memberCount: Int?,
    val description: String?,
    val match: MatchQuality,
    val engine: EngineType,
) {
    val label: String get() = if (username.isNullOrBlank()) title else "$title (@$username)"
}

package com.tcam.tdlib

import com.tcam.core.engine.ChannelEngine
import com.tcam.core.engine.ChannelMatcher
import com.tcam.core.engine.LogLevel
import com.tcam.core.engine.LogSink
import com.tcam.core.model.ActionResult
import com.tcam.core.model.ChannelAction
import com.tcam.core.model.ChannelCandidate
import com.tcam.core.model.ChannelInput
import com.tcam.core.model.EngineAvailability
import com.tcam.core.model.EngineType
import com.tcam.core.model.Failure
import com.tcam.core.model.FailureKind
import com.tcam.core.model.MatchQuality
import com.tcam.core.model.MemberRole
import com.tcam.core.model.ResolveResult
import com.tcam.core.model.VerifyResult
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.ChatListArchive
import dev.g000sha256.tdl.dto.ChatMemberStatus
import dev.g000sha256.tdl.dto.ChatMemberStatusAdministrator
import dev.g000sha256.tdl.dto.ChatMemberStatusBanned
import dev.g000sha256.tdl.dto.ChatMemberStatusCreator
import dev.g000sha256.tdl.dto.ChatMemberStatusLeft
import dev.g000sha256.tdl.dto.ChatMemberStatusMember
import dev.g000sha256.tdl.dto.ChatMemberStatusRestricted
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.InviteLinkChatTypeChannel
import dev.g000sha256.tdl.dto.SearchChatTypeFilterChannel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Background automation over TDLib, using only documented TDLib functions:
 * searchPublicChat, checkChatInviteLink, searchChats, searchChatsOnServer,
 * searchPublicChats, getChat, getSupergroup, getSupergroupFullInfo,
 * leaveChat, deleteChat and addChatToList.
 */
class TdlibChannelEngine(
    private val session: TdlibSession,
    private val log: LogSink,
    private val requestTimeoutMillis: Long = 30_000,
) : ChannelEngine {

    override val type = EngineType.BACKGROUND

    override suspend fun availability(): EngineAvailability = when (val a = session.auth.value) {
        AuthStep.Ready -> EngineAvailability.Ready
        is AuthStep.NotConfigured -> EngineAvailability.Unavailable(Failure(FailureKind.NOT_LOGGED_IN, a.reason))
        AuthStep.Closed, AuthStep.LoggingOut -> EngineAvailability.Unavailable(Failure(FailureKind.LOGIN_EXPIRED, "Telegram session ended. Log in again."))
        else -> EngineAvailability.Unavailable(Failure(FailureKind.NOT_LOGGED_IN, "Not logged in to Telegram"))
    }

    override fun supports(action: ChannelAction) = true

    // ---------------------------------------------------------------- resolve

    override suspend fun resolve(input: ChannelInput): ResolveResult {
        val client = session.clientOrNull()
            ?: return ResolveResult.Error(Failure(FailureKind.NOT_LOGGED_IN, "Not logged in to Telegram"))
        return try {
            when (input) {
                is ChannelInput.Username -> resolveUsername(client, input)
                is ChannelInput.InviteLink -> resolveInvite(client, input)
                is ChannelInput.Title -> resolveTitle(client, input)
            }
        } catch (e: TdlibCallException) {
            if (e.failure.kind == FailureKind.NOT_FOUND) ResolveResult.NotFound("Not found: ${input.displayText} (${e.failure.message})")
            else ResolveResult.Error(e.failure)
        }
    }

    private suspend fun resolveUsername(client: TdlClient, input: ChannelInput.Username): ResolveResult {
        val chat = req { client.searchPublicChat(username = input.username) }
        val candidate = toCandidate(client, chat, MatchQuality.EXACT_IDENTIFIER)
        return ChannelMatcher.classify(input, listOf(candidate))
    }

    private suspend fun resolveInvite(client: TdlClient, input: ChannelInput.InviteLink): ResolveResult {
        val info = req { client.checkChatInviteLink(inviteLink = input.link) }
        if (info.type !is InviteLinkChatTypeChannel) {
            return ResolveResult.NotFound("The invite link leads to \"${info.title}\", which is not a channel.")
        }
        if (info.chatId != 0L) {
            val chat = req { client.getChat(chatId = info.chatId) }
            return ChannelMatcher.classify(input, listOf(toCandidate(client, chat, MatchQuality.EXACT_IDENTIFIER)))
        }
        // Not a member and the chat is not accessible: preview data only.
        val candidate = ChannelCandidate(
            key = "invite:${input.link}",
            title = info.title,
            username = null,
            isChannel = true,
            role = MemberRole.NOT_MEMBER,
            canDeleteForEveryone = false,
            memberCount = info.memberCount,
            description = info.description.ifBlank { null },
            match = MatchQuality.EXACT_IDENTIFIER,
            engine = type,
        )
        return ResolveResult.Found(candidate)
    }

    private suspend fun resolveTitle(client: TdlClient, input: ChannelInput.Title): ResolveResult {
        val ids = LinkedHashSet<Long>()
        // Chats already in the user's lists (the ones they can leave or own) first.
        reqOrNull { client.searchChats(query = input.title, limit = 50) }?.chatIds?.let { ids.addAll(it.toList()) }
        reqOrNull { client.searchChatsOnServer(query = input.title, limit = 50) }?.chatIds?.let { ids.addAll(it.toList()) }
        // Then public channels, useful for preview.
        reqOrNull { client.searchPublicChats(query = input.title, typeFilter = SearchChatTypeFilterChannel()) }?.chatIds?.let { ids.addAll(it.toList()) }

        val candidates = ids.take(60).mapNotNull { id ->
            val chat = reqOrNull { client.getChat(chatId = id) } ?: return@mapNotNull null
            val t = chat.type as? ChatTypeSupergroup ?: return@mapNotNull null
            if (!t.isChannel) return@mapNotNull null
            toCandidate(client, chat, MatchQuality.EXACT_TITLE)
        }
        return ChannelMatcher.classify(input, candidates)
    }

    private suspend fun toCandidate(client: TdlClient, chat: Chat, match: MatchQuality): ChannelCandidate {
        val t = chat.type as? ChatTypeSupergroup
        if (t == null) {
            return ChannelCandidate(
                key = chat.id.toString(), title = chat.title, username = null, isChannel = false,
                role = MemberRole.UNKNOWN, canDeleteForEveryone = false, memberCount = null,
                description = null, match = match, engine = type,
            )
        }
        val sg = req { client.getSupergroup(supergroupId = t.supergroupId) }
        val full = reqOrNull { client.getSupergroupFullInfo(supergroupId = t.supergroupId) }
        val role = roleOf(sg.status)
        return ChannelCandidate(
            key = chat.id.toString(),
            title = chat.title,
            username = sg.usernames?.activeUsernames?.firstOrNull(),
            isChannel = sg.isChannel,
            role = role,
            canDeleteForEveryone = role == MemberRole.OWNER && chat.canBeDeletedForAllUsers,
            memberCount = (full?.memberCount ?: sg.memberCount).takeIf { it > 0 },
            description = full?.description?.ifBlank { null },
            match = match,
            engine = type,
        )
    }

    // ---------------------------------------------------------------- execute

    override suspend fun execute(candidate: ChannelCandidate, action: ChannelAction): ActionResult {
        if (action == ChannelAction.PREVIEW) return ActionResult.Done("Preview only — nothing changed.")
        val client = session.clientOrNull()
            ?: return ActionResult.Failed(Failure(FailureKind.NOT_LOGGED_IN, "Not logged in to Telegram"))
        val chatId = candidate.key.toLongOrNull()
            ?: return ActionResult.Failed(Failure(FailureKind.UNSUPPORTED, "This channel is only known from an invite link you have not joined; nothing to $action."))

        return try {
            // Re-read the live state right before acting.
            val chat = req { client.getChat(chatId = chatId) }
            val live = toCandidate(client, chat, candidate.match)
            when (action) {
                ChannelAction.LEAVE -> {
                    if (live.role == MemberRole.NOT_MEMBER) return ActionResult.Failed(Failure(FailureKind.PERMISSION_DENIED, "You are not a member of ${live.label}."))
                    req { client.leaveChat(chatId = chatId) }
                    ActionResult.Done("Left ${live.label}.")
                }
                ChannelAction.DELETE_OWNED -> {
                    if (live.role != MemberRole.OWNER) return ActionResult.Failed(Failure(FailureKind.PERMISSION_DENIED, "You are not the owner of ${live.label}."))
                    if (!chat.canBeDeletedForAllUsers) return ActionResult.Failed(Failure(FailureKind.PERMISSION_DENIED, "Telegram does not allow deleting ${live.label} from this account."))
                    log.log(LogLevel.WARN, "Deleting ${live.label} permanently…")
                    req { client.deleteChat(chatId = chatId) }
                    ActionResult.Done("Telegram accepted the deletion of ${live.label}.")
                }
                ChannelAction.ARCHIVE -> {
                    req { client.addChatToList(chatId = chatId, chatList = ChatListArchive()) }
                    ActionResult.Done("Moved ${live.label} to the Archive.")
                }
                ChannelAction.PREVIEW -> ActionResult.Done("Preview only.")
            }
        } catch (e: TdlibCallException) {
            ActionResult.Failed(e.failure)
        }
    }

    // ---------------------------------------------------------------- verify

    override suspend fun verify(candidate: ChannelCandidate, action: ChannelAction): VerifyResult {
        if (action == ChannelAction.PREVIEW) return VerifyResult.Verified("Nothing to verify.")
        val client = session.clientOrNull() ?: return VerifyResult.NotVerified("Not logged in")
        val chatId = candidate.key.toLongOrNull() ?: return VerifyResult.NotVerified("No chat id")

        val chat = reqOrNull { client.getChat(chatId = chatId) }
            ?: return VerifyResult.Verified("${candidate.label} is no longer accessible.")
        val t = chat.type as? ChatTypeSupergroup ?: return VerifyResult.NotVerified("Unexpected chat type.")
        val sg = reqOrNull { client.getSupergroup(supergroupId = t.supergroupId) }
        val role = sg?.let { roleOf(it.status) }

        return when (action) {
            ChannelAction.LEAVE ->
                if (role == MemberRole.NOT_MEMBER || role == MemberRole.BANNED || (sg?.status as? ChatMemberStatusCreator)?.isMember == false) {
                    VerifyResult.Verified("No longer a member of ${candidate.label}.")
                } else VerifyResult.NotVerified("Telegram still reports membership (${role?.name}).")
            ChannelAction.DELETE_OWNED ->
                if (chat.positions.isEmpty() && role != MemberRole.MEMBER && role != MemberRole.ADMIN &&
                    !((sg?.status as? ChatMemberStatusCreator)?.isMember ?: false)
                ) {
                    VerifyResult.Verified("${candidate.label} is gone from your chats.")
                } else VerifyResult.NotVerified("Telegram accepted the request, but the channel still shows in your chat list.")
            ChannelAction.ARCHIVE ->
                if (chat.chatLists.any { it is ChatListArchive }) VerifyResult.Verified("${candidate.label} is in the Archive.")
                else VerifyResult.NotVerified("The channel is not in the Archive list yet.")
            ChannelAction.PREVIEW -> VerifyResult.Verified("Nothing to verify.")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun roleOf(status: ChatMemberStatus): MemberRole = when (status) {
        is ChatMemberStatusCreator -> MemberRole.OWNER
        is ChatMemberStatusAdministrator -> MemberRole.ADMIN
        is ChatMemberStatusMember -> MemberRole.MEMBER
        is ChatMemberStatusRestricted -> if (status.isMember) MemberRole.MEMBER else MemberRole.NOT_MEMBER
        is ChatMemberStatusLeft -> MemberRole.NOT_MEMBER
        is ChatMemberStatusBanned -> MemberRole.BANNED
    }

    private class TdlibCallException(val failure: Failure) : Exception(failure.message)

    private suspend fun <T> req(block: suspend () -> TdlResult<T>): T {
        val r = try {
            withTimeout(requestTimeoutMillis) { block() }
        } catch (e: TimeoutCancellationException) {
            throw TdlibCallException(Failure(FailureKind.NETWORK, "Telegram did not answer in time. Check your connection."))
        }
        return when (r) {
            is TdlResult.Success -> r.result
            is TdlResult.Failure -> throw TdlibCallException(TdlibErrors.map(r.code, r.message))
        }
    }

    private suspend fun <T> reqOrNull(block: suspend () -> TdlResult<T>): T? = try {
        req(block)
    } catch (e: TdlibCallException) {
        if (e.failure.kind == FailureKind.RATE_LIMITED || e.failure.kind == FailureKind.LOGIN_EXPIRED || e.failure.kind == FailureKind.NETWORK) throw e
        null
    }
}

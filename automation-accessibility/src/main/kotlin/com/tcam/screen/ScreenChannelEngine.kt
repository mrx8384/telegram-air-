package com.tcam.screen

import android.content.Context
import com.tcam.core.engine.ChannelEngine
import com.tcam.core.engine.ChannelMatcher
import com.tcam.core.engine.LogLevel
import com.tcam.core.engine.LogSink
import com.tcam.core.input.TitleNormalizer
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Steps of the screen-automation state machine, exposed for the UI and floating panel. */
enum class ScreenStep {
    IDLE, CHECK_PREREQUISITES, OPEN_TELEGRAM, OPEN_SEARCH, TYPE_QUERY, READ_RESULTS,
    OPEN_CHANNEL, VERIFY_IDENTITY, OPEN_INFO, FIND_ACTION, PERFORM_ACTION, CONFIRM_DIALOG,
    VERIFY_RESULT, DONE, FAILED,
}

/**
 * Drives the official Telegram app. Public channels are opened with Telegram's
 * own `tg://resolve` deep link; titles are found with Telegram's search box.
 * Each step waits for an expected label and fails with [FailureKind.UI_CHANGED]
 * instead of guessing when it does not appear.
 */
class ScreenChannelEngine(
    private val context: Context,
    private val log: LogSink,
    private val driver: ScreenDriver = ScreenDriver(OcrReader(), log),
    private val stepTimeoutMillis: Long = 10_000,
) : ChannelEngine {

    override val type = EngineType.SCREEN

    private val _step = MutableStateFlow(ScreenStep.IDLE)
    val step: StateFlow<ScreenStep> = _step.asStateFlow()

    override fun supports(action: ChannelAction) = action != ChannelAction.ARCHIVE

    override suspend fun availability(): EngineAvailability {
        if (TelegramApps.installedPackage(context) == null) {
            return EngineAvailability.Unavailable(Failure(FailureKind.TELEGRAM_NOT_INSTALLED, "The official Telegram app is not installed."))
        }
        if (TelegramAccessibilityService.instance.value == null) {
            return EngineAvailability.Unavailable(Failure(FailureKind.ACCESSIBILITY_DISABLED, "Turn on the accessibility service for this app in system settings."))
        }
        return EngineAvailability.Ready
    }

    private fun step(s: ScreenStep, note: String? = null) {
        _step.value = s
        log.log(LogLevel.DEBUG, "[screen] ${s.name}${note?.let { ": $it" } ?: ""}")
    }

    private class StepFailure(val failure: Failure) : Exception(failure.message)

    private fun fail(kind: FailureKind, message: String): Nothing {
        step(ScreenStep.FAILED, message)
        throw StepFailure(Failure(kind, message))
    }

    private suspend fun prerequisites(): String {
        step(ScreenStep.CHECK_PREREQUISITES)
        val pkg = TelegramApps.installedPackage(context) ?: fail(FailureKind.TELEGRAM_NOT_INSTALLED, "The official Telegram app is not installed.")
        if (driver.service == null) fail(FailureKind.ACCESSIBILITY_DISABLED, "The accessibility service is off.")
        return pkg
    }

    // ---------------------------------------------------------------- channel screen

    /** What we can read from an open channel screen. */
    private data class ChannelScreen(val title: String, val subscribers: Int?, val role: MemberRole)

    /** Waits for a channel's chat screen, or a "not found" message. */
    private suspend fun awaitChannelScreen(): ChannelScreen? {
        val result = driver.waitFor(stepTimeoutMillis) { nodes ->
            if (nodes.any { UiText.containsAny(it.label, Labels.NOT_FOUND) }) return@waitFor Pair<ChannelScreen?, Boolean>(null, true)
            val header = nodes.firstOrNull { UiText.subscriberCount(it.label) != null } ?: return@waitFor null
            val title = headerTitle(header, nodes)
            val role = when {
                nodes.any { UiText.equalsAny(it.text, Labels.JOIN) || UiText.equalsAny(it.description, Labels.JOIN) } -> MemberRole.NOT_MEMBER
                nodes.any { UiText.equalsAny(it.text, Labels.MEMBER_BAR) || UiText.equalsAny(it.description, Labels.MEMBER_BAR) } -> MemberRole.MEMBER
                else -> MemberRole.UNKNOWN
            }
            Pair(ChannelScreen(title, UiText.subscriberCount(header.label), role), true)
        } ?: fail(FailureKind.UI_CHANGED, "Telegram did not show the channel screen in time. Its layout may have changed.")
        return result.first
    }

    /** The header announces "Title, 1 234 subscribers"; take the part before the count. */
    private fun headerTitle(header: UiNode, nodes: List<UiNode>): String {
        val label = header.label
        val beforeComma = label.split(',', '\n', '،').first().trim()
        if (beforeComma.isNotEmpty() && UiText.subscriberCount(beforeComma) == null) return beforeComma
        // Otherwise use the text node directly above the subscriber line.
        return nodes.filter { it.text != null && it.bounds.bottom <= header.bounds.top + 4 && it.bounds.top >= header.bounds.top - header.bounds.height() * 3 }
            .maxByOrNull { it.bounds.bottom }?.text ?: label
    }

    /** Opens the channel's info page and reads the username shown there. */
    private suspend fun openInfoAndReadUsername(): String? {
        step(ScreenStep.OPEN_INFO)
        val header = driver.snapshot().firstOrNull { UiText.subscriberCount(it.label) != null }
            ?: fail(FailureKind.UI_CHANGED, "Could not find the channel header to open its info page.")
        if (!driver.click(Target.Node(header))) fail(FailureKind.UI_CHANGED, "Could not open the channel info page.")
        delay(800)
        return driver.waitFor(4000) { nodes -> nodes.firstNotNullOfOrNull { UiText.usernameIn(it.label) } }
    }

    private suspend fun openByUsername(pkg: String, username: String): ChannelScreen {
        step(ScreenStep.OPEN_CHANNEL, "@$username")
        TelegramApps.openUsername(context, pkg, username)
        return awaitChannelScreen() ?: throw StepFailure(Failure(FailureKind.NOT_FOUND, "Telegram says @$username does not exist."))
    }

    // ---------------------------------------------------------------- resolve

    override suspend fun resolve(input: ChannelInput): ResolveResult = try {
        val pkg = prerequisites()
        val r = when (input) {
            is ChannelInput.Username -> resolveUsername(pkg, input)
            is ChannelInput.InviteLink -> resolveInvite(pkg, input)
            is ChannelInput.Title -> resolveTitle(pkg, input)
        }
        step(ScreenStep.DONE)
        r
    } catch (e: StepFailure) {
        if (e.failure.kind == FailureKind.NOT_FOUND) ResolveResult.NotFound(e.failure.message) else ResolveResult.Error(e.failure)
    }

    private suspend fun resolveUsername(pkg: String, input: ChannelInput.Username): ResolveResult {
        val screen = openByUsername(pkg, input.username)
        step(ScreenStep.VERIFY_IDENTITY)
        val shown = openInfoAndReadUsername()
        if (shown != null && !shown.equals(input.username, ignoreCase = true)) {
            fail(FailureKind.UI_CHANGED, "Opened @$shown instead of @${input.username}; stopping to be safe.")
        }
        driver.back()
        val candidate = ChannelCandidate(
            key = "u:${input.username}",
            title = screen.title,
            username = shown ?: input.username,
            isChannel = true,
            role = screen.role,
            canDeleteForEveryone = false,
            memberCount = screen.subscribers,
            description = null,
            match = MatchQuality.EXACT_IDENTIFIER,
            engine = type,
        )
        return ChannelMatcher.classify(input, listOf(candidate))
    }

    private suspend fun resolveInvite(pkg: String, input: ChannelInput.InviteLink): ResolveResult {
        step(ScreenStep.OPEN_CHANNEL, input.link)
        TelegramApps.openInvite(context, pkg, input.link)
        val screen = awaitChannelScreen() ?: return ResolveResult.NotFound("The invite link is invalid or expired.")
        val candidate = ChannelCandidate(
            key = "i:${input.link}", title = screen.title, username = null, isChannel = true,
            role = screen.role, canDeleteForEveryone = false, memberCount = screen.subscribers,
            description = null, match = MatchQuality.EXACT_IDENTIFIER, engine = type,
        )
        driver.back()
        return ResolveResult.Found(candidate)
    }

    private suspend fun searchFor(pkg: String, query: String): List<UiNode> {
        step(ScreenStep.OPEN_TELEGRAM)
        if (!TelegramApps.launchMain(context, pkg)) fail(FailureKind.TELEGRAM_NOT_INSTALLED, "Could not launch Telegram.")
        step(ScreenStep.OPEN_SEARCH)
        val search = driver.findTarget(Labels.SEARCH, stepTimeoutMillis)
            ?: fail(FailureKind.UI_CHANGED, "Could not find Telegram's search button.")
        driver.click(search)
        step(ScreenStep.TYPE_QUERY, query)
        if (!driver.typeIntoFocusedField(query)) fail(FailureKind.UI_CHANGED, "Could not type into Telegram's search box.")
        step(ScreenStep.READ_RESULTS)
        delay(2500) // let local and server results arrive
        val wanted = TitleNormalizer.normalize(query)
        return driver.snapshot().filter { n ->
            !n.editable && n.label.isNotBlank() && TitleNormalizer.normalize(n.label).contains(wanted)
        }
    }

    private suspend fun resolveTitle(pkg: String, input: ChannelInput.Title): ResolveResult {
        val rows = searchFor(pkg, input.title)
        val candidates = rows.mapIndexed { i, row ->
            val label = row.label
            ChannelCandidate(
                key = "t:${label.split(',', '\n').first().trim()}",
                title = label.split(',', '\n', '،').first().trim(),
                username = UiText.usernameIn(label),
                isChannel = UiText.containsAny(label, Labels.CHANNEL_WORD),
                role = MemberRole.UNKNOWN,
                canDeleteForEveryone = false,
                memberCount = UiText.subscriberCount(label),
                description = null,
                match = MatchQuality.EXACT_TITLE,
                engine = type,
            )
        }.distinctBy { it.key }
        driver.back()
        driver.back()
        return ChannelMatcher.classify(input, candidates)
    }

    // ---------------------------------------------------------------- execute

    /** Re-opens the channel and checks it is still the one the user confirmed. */
    private suspend fun reopenAndVerify(pkg: String, c: ChannelCandidate): ChannelScreen {
        val screen: ChannelScreen = when {
            !c.username.isNullOrBlank() -> openByUsername(pkg, c.username!!)
            c.key.startsWith("i:") -> {
                step(ScreenStep.OPEN_CHANNEL)
                TelegramApps.openInvite(context, pkg, c.key.removePrefix("i:"))
                awaitChannelScreen() ?: fail(FailureKind.NOT_FOUND, "The invite link no longer works.")
            }
            else -> {
                val rows = searchFor(pkg, c.title)
                val row = rows.firstOrNull { TitleNormalizer.normalize(it.label.split(',', '\n', '،').first()) == TitleNormalizer.normalize(c.title) }
                    ?: fail(FailureKind.NOT_FOUND, "\"${c.title}\" is no longer in the search results.")
                step(ScreenStep.OPEN_CHANNEL, c.title)
                driver.click(Target.Node(row))
                awaitChannelScreen() ?: fail(FailureKind.NOT_FOUND, "Could not open \"${c.title}\".")
            }
        }
        step(ScreenStep.VERIFY_IDENTITY)
        if (TitleNormalizer.normalize(screen.title) != TitleNormalizer.normalize(c.title)) {
            fail(FailureKind.UI_CHANGED, "The open channel is \"${screen.title}\", not \"${c.title}\". Stopping to be safe.")
        }
        return screen
    }

    override suspend fun execute(candidate: ChannelCandidate, action: ChannelAction): ActionResult = try {
        val pkg = prerequisites()
        val result = when (action) {
            ChannelAction.PREVIEW -> ActionResult.Done("Preview only — nothing changed.")
            ChannelAction.ARCHIVE -> ActionResult.Failed(Failure(FailureKind.UNSUPPORTED, "Archiving is only available in background mode."))
            ChannelAction.LEAVE -> leave(pkg, candidate)
            ChannelAction.DELETE_OWNED -> delete(pkg, candidate)
        }
        step(ScreenStep.DONE)
        result
    } catch (e: StepFailure) {
        ActionResult.Failed(e.failure)
    }

    private suspend fun leave(pkg: String, c: ChannelCandidate): ActionResult {
        val screen = reopenAndVerify(pkg, c)
        if (screen.role == MemberRole.NOT_MEMBER) return ActionResult.Failed(Failure(FailureKind.PERMISSION_DENIED, "You are not subscribed to ${c.label}."))

        step(ScreenStep.FIND_ACTION, "Leave channel")
        var leave = openMenuAndFind(Labels.LEAVE_CHANNEL)
        if (leave == null) {
            // Some versions only offer it on the info page.
            driver.back()
            openInfoAndReadUsername()
            leave = openMenuAndFind(Labels.LEAVE_CHANNEL)
        }
        leave ?: fail(FailureKind.UI_CHANGED, "Could not find \"Leave channel\" in Telegram's menu.")

        step(ScreenStep.PERFORM_ACTION)
        driver.click(leave)
        step(ScreenStep.CONFIRM_DIALOG)
        val confirm = driver.findTarget(Labels.LEAVE_CONFIRM, 5000)
            ?: fail(FailureKind.UI_CHANGED, "Telegram's confirmation dialog did not appear.")
        driver.click(confirm)
        delay(1500)
        return ActionResult.Done("Tapped \"Leave channel\" for ${c.label} and confirmed.")
    }

    private suspend fun delete(pkg: String, c: ChannelCandidate): ActionResult {
        reopenAndVerify(pkg, c)
        openInfoAndReadUsername()

        step(ScreenStep.FIND_ACTION, "Edit")
        val edit = driver.findTarget(Labels.EDIT, 5000)
            ?: fail(FailureKind.PERMISSION_DENIED, "Telegram shows no Edit button, so this account cannot manage ${c.label}.")
        driver.click(edit)

        step(ScreenStep.FIND_ACTION, "Delete channel")
        val del = driver.scrollUntil(Labels.DELETE_CHANNEL)
            ?: fail(FailureKind.PERMISSION_DENIED, "Telegram does not offer \"Delete channel\" — only the owner can delete it.")

        step(ScreenStep.PERFORM_ACTION)
        driver.click(Target.Node(del))
        step(ScreenStep.CONFIRM_DIALOG)
        val confirm = driver.findTarget(Labels.DELETE_CONFIRM, 5000)
            ?: fail(FailureKind.UI_CHANGED, "Telegram's delete confirmation did not appear.")
        driver.click(confirm)
        delay(2000)
        return ActionResult.Done("Tapped \"Delete channel\" for ${c.label} and confirmed.")
    }

    private suspend fun openMenuAndFind(labels: Set<String>): Target? {
        driver.findTarget(labels, 1000, allowOcr = false)?.let { return it }
        val more = driver.findTarget(Labels.MORE_OPTIONS, 4000) ?: return null
        driver.click(more)
        return driver.findTarget(labels, 4000)
    }

    // ---------------------------------------------------------------- verify

    override suspend fun verify(candidate: ChannelCandidate, action: ChannelAction): VerifyResult = try {
        val pkg = prerequisites()
        step(ScreenStep.VERIFY_RESULT)
        val username = candidate.username
        when {
            action == ChannelAction.PREVIEW -> VerifyResult.Verified("Nothing to verify.")
            username.isNullOrBlank() -> VerifyResult.NotVerified("Channels without a username cannot be re-checked on screen.")
            else -> {
                TelegramApps.openUsername(context, pkg, username)
                val screen = awaitChannelScreen()
                when (action) {
                    ChannelAction.LEAVE ->
                        if (screen?.role == MemberRole.NOT_MEMBER) VerifyResult.Verified("Telegram now shows \"Join\" for ${candidate.label}.")
                        else VerifyResult.NotVerified("Telegram does not show \"Join\" yet.")
                    ChannelAction.DELETE_OWNED ->
                        if (screen == null) VerifyResult.Verified("@$username no longer exists.")
                        else VerifyResult.NotVerified("@$username still opens a channel.")
                    else -> VerifyResult.NotVerified("Not checked.")
                }
            }
        }.also { step(ScreenStep.DONE) }
    } catch (e: StepFailure) {
        VerifyResult.NotVerified(e.failure.message)
    }
}

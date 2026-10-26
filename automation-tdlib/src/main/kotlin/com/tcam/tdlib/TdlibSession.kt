package com.tcam.tdlib

import com.tcam.core.engine.LogLevel
import com.tcam.core.engine.LogSink
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateClosing
import dev.g000sha256.tdl.dto.AuthorizationStateLoggingOut
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailAddress
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitOtherDeviceConfirmation
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPremiumPurchase
import dev.g000sha256.tdl.dto.AuthorizationStateWaitRegistration
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.EmailAddressAuthenticationCode
import dev.g000sha256.tdl.dto.Ok
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the official Telegram login flow currently stands. */
sealed class AuthStep {
    data object NotStarted : AuthStep()
    data class NotConfigured(val reason: String) : AuthStep()
    data object Initializing : AuthStep()
    data object WaitPhoneNumber : AuthStep()
    data class WaitCode(val phoneNumber: String, val codeType: String, val timeoutSeconds: Int) : AuthStep()
    data object WaitEmailAddress : AuthStep()
    data class WaitEmailCode(val emailPattern: String) : AuthStep()
    /** Two-step verification. The password goes only to TDLib's checkAuthenticationPassword. */
    data class WaitPassword(val hint: String, val hasRecoveryEmail: Boolean) : AuthStep()
    data class WaitOtherDevice(val link: String) : AuthStep()
    /** The number has no Telegram account. Registration is left to the official app. */
    data object WaitRegistration : AuthStep()
    data object WaitPremiumPurchase : AuthStep()
    data object Ready : AuthStep()
    data object LoggingOut : AuthStep()
    data object Closed : AuthStep()
    data class Error(val message: String) : AuthStep()
}

/**
 * Owns the single TDLib client and drives the login flow.
 * The phone number, code and 2FA password are passed straight to TDLib and are
 * never stored by this app. TDLib keeps the session in its own encrypted database.
 */
class TdlibSession(
    private val scope: CoroutineScope,
    private val configProvider: () -> TdlibConfig?,
    private val keyProvider: DatabaseKeyProvider,
    private val log: LogSink,
    private val clientFactory: () -> TdlClient = { TdlClient.create() },
) {
    private val _auth = MutableStateFlow<AuthStep>(AuthStep.NotStarted)
    val auth: StateFlow<AuthStep> = _auth.asStateFlow()

    private val lock = Mutex()
    private var _client: TdlClient? = null
    private var updatesJob: Job? = null

    /** The client, only while logged in. */
    val client: TdlClient? get() = _client?.takeIf { _auth.value == AuthStep.Ready }

    val isReady: Boolean get() = _auth.value == AuthStep.Ready

    /** Starts TDLib (or restarts it after it was closed). Safe to call repeatedly. */
    suspend fun start() = lock.withLock {
        val cfg = configProvider()
        if (cfg == null || !cfg.isComplete) {
            _auth.value = AuthStep.NotConfigured("Add your Telegram API ID and API hash (from my.telegram.org) in Settings first.")
            return@withLock
        }
        val current = _auth.value
        if (_client != null && current != AuthStep.Closed && current !is AuthStep.Error && current !is AuthStep.NotConfigured) return@withLock

        _auth.value = AuthStep.Initializing
        val client = try {
            clientFactory()
        } catch (t: Throwable) {
            _auth.value = AuthStep.Error("Could not load TDLib: ${t.message}")
            log.log(LogLevel.ERROR, "TDLib failed to load: ${t.message}")
            return@withLock
        }
        _client = client
        // Subscribe before asking for the state, so no update is missed.
        updatesJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.authorizationStateUpdates.collect { onState(client, it.authorizationState) }
        }
        when (val r = client.getAuthorizationState()) {
            is TdlResult.Success -> onState(client, r.result)
            is TdlResult.Failure -> _auth.value = AuthStep.Error(r.message)
        }
    }

    private suspend fun onState(client: TdlClient, state: AuthorizationState) {
        val step = when (state) {
            is AuthorizationStateWaitTdlibParameters -> {
                sendParameters(client)
                AuthStep.Initializing
            }
            is AuthorizationStateWaitPhoneNumber -> AuthStep.WaitPhoneNumber
            is AuthorizationStateWaitCode -> AuthStep.WaitCode(
                state.codeInfo.phoneNumber,
                state.codeInfo.type::class.simpleName?.removePrefix("AuthenticationCodeType") ?: "",
                state.codeInfo.timeout,
            )
            is AuthorizationStateWaitEmailAddress -> AuthStep.WaitEmailAddress
            is AuthorizationStateWaitEmailCode -> AuthStep.WaitEmailCode(state.codeInfo.emailAddressPattern)
            is AuthorizationStateWaitPassword -> AuthStep.WaitPassword(state.passwordHint, state.hasRecoveryEmailAddress)
            is AuthorizationStateWaitOtherDeviceConfirmation -> AuthStep.WaitOtherDevice(state.link)
            is AuthorizationStateWaitRegistration -> AuthStep.WaitRegistration
            is AuthorizationStateWaitPremiumPurchase -> AuthStep.WaitPremiumPurchase
            is AuthorizationStateReady -> AuthStep.Ready
            is AuthorizationStateLoggingOut -> AuthStep.LoggingOut
            is AuthorizationStateClosing -> AuthStep.LoggingOut
            is AuthorizationStateClosed -> AuthStep.Closed
        }
        if (step == AuthStep.Closed) {
            updatesJob?.cancel()
            _client = null
        }
        if (_auth.value != step) {
            log.log(LogLevel.INFO, "Telegram login: ${step::class.simpleName}")
        }
        _auth.value = step
    }

    private suspend fun sendParameters(client: TdlClient) {
        val cfg = configProvider() ?: return
        val r = client.setTdlibParameters(
            useTestDc = cfg.useTestDc,
            databaseDirectory = cfg.databaseDirectory,
            filesDirectory = cfg.filesDirectory,
            databaseEncryptionKey = keyProvider.databaseKey(),
            useFileDatabase = false,
            useChatInfoDatabase = true,
            useMessageDatabase = false,
            useSecretChats = false,
            apiId = cfg.apiId,
            apiHash = cfg.apiHash,
            systemLanguageCode = cfg.systemLanguageCode,
            deviceModel = cfg.deviceModel,
            systemVersion = cfg.systemVersion,
            applicationVersion = cfg.applicationVersion,
        )
        if (r is TdlResult.Failure) {
            _auth.value = AuthStep.Error("TDLib rejected the parameters: ${r.message}")
            log.log(LogLevel.ERROR, "setTdlibParameters failed: ${r.code} ${r.message}")
        }
    }

    /** Closes the current client (if any) and starts again, e.g. after new API credentials were saved. */
    suspend fun restart() {
        val old = lock.withLock { _client }
        if (old != null) {
            old.close()
            withTimeoutOrNull(10_000) { auth.first { it == AuthStep.Closed } }
            lock.withLock {
                updatesJob?.cancel()
                _client = null
                _auth.value = AuthStep.Closed
            }
        }
        start()
    }

    suspend fun submitPhoneNumber(phone: String): Result<Unit> =
        call { it.setAuthenticationPhoneNumber(phoneNumber = phone.trim()) }

    suspend fun submitCode(code: String): Result<Unit> =
        call { it.checkAuthenticationCode(code = code.trim()) }

    suspend fun resendCode(): Result<Unit> = call { it.resendAuthenticationCode() }

    suspend fun submitEmailAddress(email: String): Result<Unit> =
        call { it.setAuthenticationEmailAddress(emailAddress = email.trim()) }

    suspend fun submitEmailCode(code: String): Result<Unit> =
        call { it.checkAuthenticationEmailCode(code = EmailAddressAuthenticationCode(code.trim())) }

    suspend fun submitPassword(password: String): Result<Unit> =
        call { it.checkAuthenticationPassword(password = password) }

    suspend fun logOut(): Result<Unit> = call { it.logOut() }

    private suspend fun call(block: suspend (TdlClient) -> TdlResult<Ok>): Result<Unit> {
        val c = _client ?: return Result.failure(IllegalStateException("TDLib is not running"))
        return when (val r = block(c)) {
            is TdlResult.Success -> Result.success(Unit)
            is TdlResult.Failure -> Result.failure(IllegalStateException(TdlibErrors.map(r.code, r.message).message))
        }
    }

    /** Raw access for the engine. */
    internal fun clientOrNull(): TdlClient? = client
}

package com.tcam.tdlib

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.OptionValueString
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Loads the real TDLib native library and makes offline calls. Needs no
 * network and no Telegram account. Skipped where the JVM artifact ships no
 * natives for the host OS.
 */
class TdlibNativeSmokeTest {
    @Test fun realTdlibAnswers(): Unit = runBlocking {
        val os = System.getProperty("os.name").lowercase()
        assumeTrue(os.contains("linux") || os.contains("mac") || os.contains("windows"))

        val client = TdlClient.create()
        val closed = async(start = CoroutineStart.UNDISPATCHED) {
            client.authorizationStateUpdates.firstOrNull { it.authorizationState is AuthorizationStateClosed }
        }
        withTimeout(30_000) {
            val state = client.getAuthorizationState()
            assertTrue("state: $state", state is TdlResult.Success && state.result is AuthorizationStateWaitTdlibParameters)
            val version = client.getOption("version")
            assertTrue(version is TdlResult.Success)
            assertEquals(TdlClient.TDL_VERSION, ((version as TdlResult.Success).result as OptionValueString).value)
            // Shut TDLib down cleanly so its native threads do not outlive the JVM.
            client.close()
            closed.await()
        }
    }
}

package com.tcam.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.tcam.app.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Launches the real activity on Robolectric and drives the Compose UI:
 * adds channels, tries to start without any engine, and opens every tab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MainActivitySmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val hint = "@username, t.me link, invite link or channel name — one per line"

    private fun scrollTo(text: String, substring: Boolean = false) {
        compose.onNodeWithTag("channels_list").performScrollToNode(hasText(text, substring = substring))
    }

    @Test fun mainScreenAddsChannelsToTheQueue() {
        compose.onNodeWithText("Channels to process").assertIsDisplayed()
        compose.onNode(hasText(hint)).performTextInput("@channel_one\nhttps://t.me/channel_two\nMy Channel")
        compose.onNodeWithText("Add").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("3 channel(s) in the list").assertIsDisplayed()
        compose.onNodeWithText("@channel_one").assertIsDisplayed()
        compose.onNodeWithText("@channel_two").assertIsDisplayed()
        assertEquals(
            "@channel_one\nhttps://t.me/channel_two\nMy Channel",
            compose.activity.application.container.settings.channelText,
        )

        scrollTo("Delete owned channel")
        compose.onNodeWithText("Delete owned channel").assertIsDisplayed()
        scrollTo("Dry run")
        compose.onNodeWithText("Dry run").assertIsDisplayed()
    }

    @Test fun keywordIsAddedToTheQueue() {
        compose.onNodeWithText("Search by keyword").assertIsDisplayed()
        compose.onNode(hasText("A word in the channel names, e.g. crypto")).performTextInput("crypto")
        compose.onNodeWithText("Add keyword").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("🔎 crypto").assertIsDisplayed()
        compose.waitForIdle()
        val stored = compose.activity.application.container.settings.channelText
        assertEquals("keyword: crypto", stored)
        scrollTo("Remove from my Telegram")
        compose.onNodeWithText("Remove from my Telegram").assertIsDisplayed()
    }

    @Test fun startWithoutAnyEngineExplainsWhy() {
        compose.onNode(hasText(hint)).performTextInput("@channel_one")
        scrollTo("Find channels")
        compose.onNodeWithText("Find channels").performClick()
        // The test device has no TDLib credentials, no accessibility service and no Telegram app.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Cannot start", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun otherTabsRender() {
        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithText("Telegram account").assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Add your Telegram API ID", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("No runs yet.").assertIsDisplayed()

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Telegram API credentials").assertIsDisplayed()
        compose.onNodeWithText("Permissions").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Screen recording").performScrollTo().assertIsDisplayed()
    }
}

package com.tcam.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTextTest {
    @Test fun subscriberCounts() {
        assertEquals(12345, UiText.subscriberCount("12,345 subscribers"))
        assertEquals(1200, UiText.subscriberCount("News, 1.2K subscribers"))
        assertEquals(12, UiText.subscriberCount("۱۲ مشترک"))
        assertNull(UiText.subscriberCount("Hello"))
    }

    @Test fun labelMatching() {
        assertTrue(UiText.equalsAny("LEAVE CHANNEL", Labels.LEAVE_CONFIRM))
        assertTrue(UiText.equalsAny("حذف کانال", Labels.DELETE_CHANNEL))
        assertTrue(UiText.containsAny("Sorry, this username doesn't seem to exist.", Labels.NOT_FOUND))
    }

    @Test fun usernameInLabel() {
        assertEquals("my_channel", UiText.usernameIn("t.me/my_channel"))
        assertEquals("abcde", UiText.usernameIn("Link @abcde"))
    }
}

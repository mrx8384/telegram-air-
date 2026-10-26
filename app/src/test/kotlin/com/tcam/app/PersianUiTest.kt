package com.tcam.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tcam.app.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The UI is fully translated to Persian (right-to-left). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fa-w411dp-h891dp-xxhdpi")
class PersianUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun showsPersianStrings() {
        compose.onNodeWithText("کانال‌ها برای پردازش").assertIsDisplayed()
        compose.onNodeWithText("افزودن").assertIsDisplayed()
        compose.onNodeWithText("تنظیمات").performClick()
        compose.onNodeWithText("مشخصات API تلگرام").assertIsDisplayed()
    }
}

package com.tcam.app

import com.tcam.app.service.VideoSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSizesTest {
    @Test fun tallPhoneFitsEncoderLimits() {
        val (w, h) = VideoSizes.fit(1080, 2400)
        assertTrue("$w x $h", w <= 1080 && h <= 1920)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        // aspect ratio kept within rounding
        assertEquals(1080.0 / 2400, w.toDouble() / h, 0.02)
    }

    @Test fun landscapeAndSmallScreens() {
        val (w, h) = VideoSizes.fit(2400, 1080)
        assertTrue(w > h && w <= 1920 && h <= 1080)
        assertEquals(720 to 1280, VideoSizes.fit(720, 1280))
    }
}

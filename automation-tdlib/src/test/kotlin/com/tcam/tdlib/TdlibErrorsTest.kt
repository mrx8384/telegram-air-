package com.tcam.tdlib

import com.tcam.core.model.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Test

class TdlibErrorsTest {
    @Test fun mapsRateLimits() {
        val f = TdlibErrors.map(429, "Too Many Requests: retry after 37")
        assertEquals(FailureKind.RATE_LIMITED, f.kind)
        assertEquals(37, f.retryAfterSeconds)
        assertEquals(120, TdlibErrors.map(420, "FLOOD_WAIT_120").retryAfterSeconds)
    }

    @Test fun mapsCommonErrors() {
        assertEquals(FailureKind.NOT_FOUND, TdlibErrors.map(400, "USERNAME_NOT_OCCUPIED").kind)
        assertEquals(FailureKind.PERMISSION_DENIED, TdlibErrors.map(400, "CHAT_ADMIN_REQUIRED").kind)
        assertEquals(FailureKind.LOGIN_EXPIRED, TdlibErrors.map(401, "Unauthorized").kind)
        assertEquals(FailureKind.TELEGRAM_ERROR, TdlibErrors.map(500, "Internal").kind)
    }
}

package com.metrolist.shared.utils

import kotlin.test.Test
import kotlin.test.assertFalse

class CrashReportingTest {
    /** abcd never sends crash reports (upstream's Sentry project is not ours). */
    @Test
    fun crashReportingIsNeverAvailable() {
        assertFalse(isCrashReportingAvailable("14.0.0"))
        assertFalse(isCrashReportingAvailable("1.0.0-alpha1"))
        assertFalse(isCrashReportingAvailable("14.0.0+24897bc"))
    }
}

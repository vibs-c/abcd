package com.metrolist.shared.utils

/**
 * abcd: crash reporting is switched off for good. Upstream's Sentry project belongs to the original app's
 * developers, so abcd must never send reports there. With this false, the onboarding "Help improve" step
 * and the Settings toggle are hidden, and Sentry is never initialised (auto-init is off in the manifest).
 */
@Suppress("UNUSED_PARAMETER")
fun isCrashReportingAvailable(appVersion: String = ""): Boolean = false

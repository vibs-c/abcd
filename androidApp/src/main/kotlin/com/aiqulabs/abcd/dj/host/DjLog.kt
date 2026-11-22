package com.aiqulabs.abcd.dj.host

import timber.log.Timber
import android.util.Log
import android.os.Build
import android.os.Process
import com.metrolist.android.BuildConfig

/**
 * Collects this app's recent DJ log lines into a text report the owner can paste into a bug report
 * (Lab → "Copy DJ log"). Reads only this process's own log, which every app may do; nothing is sent
 * anywhere. Includes failure reasons and stack traces from preparation, decoding, analysis and the engine.
 */
internal object DjLog {
    private val tags =
        listOf(
            "DjController",
            "DjTrackPreparer",
            "DjAudioProvider",
            "DjPlayback",
            "DjMixEngine",
            "DjLibraryAnalyzer",
            "DjLabController",
            "DjAnalysisService",
            "AbcdDecoder",
            "AbcdAnalysis",
            "AudioCacheManager",
            "AndroidRuntime",
        )
    /**
     * Release builds have no Timber tree, so the DJ's lines would be lost. This one passes only the DJ tags above,
     * at INFO and higher, to the local log (still read only by [collect]; nothing leaves the phone).
     */
    fun plantReleaseTree() {
        Timber.plant(
            object : Timber.Tree() {
                override fun isLoggable(
                    tag: String?,
                    priority: Int,
                ): Boolean = priority >= Log.INFO && tag != null && tag in djTags

                override fun log(
                    priority: Int,
                    tag: String?,
                    message: String,
                    t: Throwable?,
                ) {
                    Log.println(priority, tag, if (t == null) message else message + System.lineSeparator() + Log.getStackTraceString(t))
                }
            },
        )
    }

    private val djTags get() = tags.toSet() + setOf("DjPlaybackHost", "DjFeedback", "DjTags", "DjLyrics")

    private val tagPattern = Regex("""^\S+ \S+ [VDIWEF]/([^(:]+)""")

    fun collect(): String {
        val lines =
            runCatching {
                val process =
                    ProcessBuilder("logcat", "-d", "-v", "time", "-t", "6000", "--pid=${Process.myPid()}")
                        .redirectErrorStream(true)
                        .start()
                process.inputStream.bufferedReader().use { it.readLines() }.also { process.destroy() }
            }.getOrElse { return header() + "Could not read the log: $it\n" }
        val kept =
            lines
                .filter { line ->
                    val tag = tagPattern.find(line)?.groupValues?.get(1)?.trim() ?: return@filter false
                    tags.any { tag.startsWith(it) }
                }.takeLast(MAX_LINES)
                .map { it.take(MAX_LINE_CHARS) }
        return header() + if (kept.isEmpty()) "(no DJ log lines yet: start a mix first)\n" else kept.joinToString("\n", postfix = "\n")
    }

    private fun header(): String =
        "abcd DJ log | app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | " +
            "${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | " +
            "ABI ${Build.SUPPORTED_ABIS.firstOrNull()}\n"

    private const val MAX_LINES = 600
    private const val MAX_LINE_CHARS = 400
}

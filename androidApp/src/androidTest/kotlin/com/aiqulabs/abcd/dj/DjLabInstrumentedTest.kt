package com.aiqulabs.abcd.dj

import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.aiqulabs.abcd.dj.data.DjData
import com.aiqulabs.abcd.dj.host.DjLabController
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Lab's own code path (the same one the screen uses) on a real music file: pick a file, analyse it,
 * see the saved result, delete it. Needs `abcd_song.mp3` in the app's external files folder (see
 * dj-native/README.md); skipped without it.
 */
class DjLabInstrumentedTest {
    @Test
    fun analysingAPickedFileSavesItAndOpensTheResult() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(checkNotNull(context.getExternalFilesDir(null)), "abcd_song.mp3")
            assumeTrue("push abcd_song.mp3 first", file.exists())

            DjLabController.attach(context)
            DjLabController.analyseFile(Uri.fromFile(file).toString(), "Shiny Tech")

            val detail = waitFor(120_000) { DjLabController.state.value.detail }
            assertNotNull("the analysis finished and the detail page opened", detail)
            checkNotNull(detail)
            val result = detail.stored.result
            Log.i(
                "AbcdLab",
                "bpm=${result.bpm} key=${result.key.camelot} sections=${result.sections.size} choruses=${result.chorusCandidates.size}",
            )
            assertEquals("Shiny Tech", detail.title)
            assertEquals(138.0, result.bpm, 1.5)
            assertTrue(result.sections.isNotEmpty())
            assertTrue(result.waveformPeaks.isNotEmpty())

            val repository = DjData.analysisRepository(context)
            val saved = repository.load(detail.videoId)
            assertNotNull("saved in the DJ database", saved)
            assertEquals("the name is remembered for the Lab list", "Shiny Tech", repository.trackInfo(detail.videoId)?.title)
            assertTrue(
                waitFor(10_000) {
                    DjLabController.state.value.analysed
                        .firstOrNull { it.videoId == detail.videoId }
                } != null,
            )

            DjLabController.delete(detail.videoId)
            waitFor(10_000) { if (repository.load(detail.videoId) == null) true else null }
            assertNull(repository.load(detail.videoId))
        }

    @Test
    fun anUnreadableFileShowsAMessageInsteadOfCrashing() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val junk = File(context.cacheDir, "not-audio.mp3").apply { writeText("this is not audio") }
            DjLabController.attach(context)
            DjLabController.dismissMessage()
            DjLabController.analyseFile(Uri.fromFile(junk).toString(), "Broken")
            val message = waitFor(30_000) { DjLabController.state.value.message }
            assertNotNull(message)
            assertNull("no stuck progress card", DjLabController.state.value.busy)
        }

    /** Polls [read] every 250 ms until it returns something, or returns null after [timeoutMs]. */
    private suspend fun <T : Any> waitFor(
        timeoutMs: Long,
        read: suspend () -> T?,
    ): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            delay(250)
        }
        return null
    }
}

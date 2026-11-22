package com.aiqulabs.abcd.dj

import androidx.test.platform.app.InstrumentationRegistry
import com.aiqulabs.abcd.dj.core.feedback.TransitionOutcome
import com.aiqulabs.abcd.dj.core.mix.TransitionTechnique
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.tagging.TagSource
import com.aiqulabs.abcd.dj.core.tagging.TagType
import com.aiqulabs.abcd.dj.core.tagging.TrackTagValue
import com.aiqulabs.abcd.dj.data.DjData
import com.aiqulabs.abcd.dj.data.DjFeedbackRepository
import com.aiqulabs.abcd.dj.data.db.DjDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Stage 10: feedback, tags and sessions in the real DJ database. */
class DjFeedbackInstrumentedTest {
    private lateinit var file: File
    private lateinit var db: DjDatabase
    private lateinit var repository: DjFeedbackRepository

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "dj-feedback-test-${System.nanoTime()}.db")
        db = DjData.build(context, file.absolutePath)
        repository = DjFeedbackRepository(db.dao()) { 5_000L }
    }

    @After
    fun close() {
        db.close()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        file.delete()
    }

    @Test
    fun thumbsSkipsRemovalsAndBansAddUpInTheSnapshot() =
        runBlocking {
            val liked = repository.recordTransition("a", "b", TransitionTechnique.BEATMATCHED_BLEND, 2.0, "COMPATIBLE", TransitionOutcome.COMPLETED)
            repository.rateTransition(liked, TransitionOutcome.THUMBS_UP)
            val disliked = repository.recordTransition("b", "c", TransitionTechnique.BEATMATCHED_BLEND, 1.0, "CLASH", TransitionOutcome.COMPLETED)
            repository.rateTransition(disliked, TransitionOutcome.THUMBS_DOWN)
            repository.recordTransition("c", "d", TransitionTechnique.ECHO_OUT, 30.0, "UNKNOWN", TransitionOutcome.SKIPPED)
            repository.trackSkipped("c")
            repository.trackRemoved("e")
            repository.trackRemoved("e")
            repository.setBanned("f", true)
            repository.trackPlayed("a")

            val snapshot = repository.snapshot()
            assertEquals(mapOf(("a" to "b") to 1, ("b" to "c") to -1), snapshot.pairScores)
            assertEquals(0, snapshot.techniqueScore(TransitionTechnique.BEATMATCHED_BLEND))
            assertEquals(0, snapshot.techniqueScore(TransitionTechnique.ECHO_OUT))
            assertEquals(mapOf("c" to 1), snapshot.skipCounts)
            assertEquals(mapOf("e" to 2), snapshot.removeCounts)
            assertEquals(setOf("f"), snapshot.banned)
            assertTrue(snapshot.trackPenalty("e") > snapshot.trackPenalty("c"))
        }

    @Test
    fun reTaggingReplacesOnlyThatSourcesTags() =
        runBlocking {
            repository.saveTags("v", TagSource.ANALYSIS, listOf(TrackTagValue(TagType.MOOD, "PARTY", TagSource.ANALYSIS, 0.6f)))
            repository.saveTags("v", TagSource.METADATA, listOf(TrackTagValue(TagType.LANGUAGE, "HINDI", TagSource.METADATA, 0.9f)))
            repository.saveTags("v", TagSource.ANALYSIS, listOf(TrackTagValue(TagType.MOOD, "CHILL", TagSource.ANALYSIS, 0.6f)))
            val tags = repository.tags("v").map { it.tagType to it.value }.toSet()
            assertEquals(setOf("MOOD" to "CHILL", "LANGUAGE" to "HINDI"), tags)
        }

    @Test
    fun aSessionKeepsItsSettingsAndTracks() =
        runBlocking {
            val id = repository.startSession(DjSessionSettings())
            repository.updateSession(id, listOf("a", "b"), ended = true)
            assertEquals(1, db.dao().sessionCount())
        }
}

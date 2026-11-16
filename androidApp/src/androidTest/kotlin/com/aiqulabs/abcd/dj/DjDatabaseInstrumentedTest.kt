package com.aiqulabs.abcd.dj

import androidx.test.platform.app.InstrumentationRegistry
import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.analysis.ChorusCandidate
import com.aiqulabs.abcd.dj.core.analysis.ChorusSignal
import com.aiqulabs.abcd.dj.core.analysis.ChorusSource
import com.aiqulabs.abcd.dj.core.analysis.KeyMode
import com.aiqulabs.abcd.dj.core.analysis.KeyResult
import com.aiqulabs.abcd.dj.core.analysis.SectionResult
import com.aiqulabs.abcd.dj.core.analysis.SectionType
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.data.DjAnalysisRepository
import com.aiqulabs.abcd.dj.data.DjData
import com.aiqulabs.abcd.dj.data.db.ChorusSegmentEntity
import com.aiqulabs.abcd.dj.data.db.DjDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Runs the DJ database on a device or emulator: saving, loading and the "never overwrite user edits" rule. */
class DjDatabaseInstrumentedTest {
    private lateinit var file: File
    private lateinit var db: DjDatabase
    private lateinit var repository: DjAnalysisRepository

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "dj-test-${System.nanoTime()}.db")
        db = DjData.build(context, file.absolutePath)
        repository = DjAnalysisRepository(db.dao()) { 1_000L }
    }

    @After
    fun close() {
        db.close()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        file.delete()
    }

    private fun result(
        version: Int = 1,
        bpm: Double = 126.0,
        choruses: List<ChorusCandidate> = listOf(auto(22_857.0, 38_095.0, 12, 20, main = true), auto(53_333.0, 68_571.0, 28, 36)),
    ) = AnalysisResult(
        analysisVersion = version,
        durationMs = 99_000.0,
        bpm = bpm,
        bpmConfidence = 0.9,
        bpmConfidenceRaw = 3.5,
        beatsMs = listOf(0.0, 476.190476190476, 952.380952380952),
        barStartsMs = listOf(0.0, 1904.76190476),
        phraseStartsMs = listOf(0.0),
        key = KeyResult("A", KeyMode.MINOR, "8A", 9, 0.89),
        lufs = -21.2,
        energyCurve = listOf(0.4, 1.0),
        waveformPeaks = listOf(0, 128, 255),
        introMixableEndMs = 7_619.0,
        outroMixableStartMs = 91_428.0,
        sections = listOf(SectionResult(SectionType.INTRO, 0.0, 7_619.0, 0.7), SectionResult(SectionType.CHORUS, 22_857.0, 38_095.0, 0.7)),
        chorusCandidates = choruses,
    )

    private fun auto(
        startMs: Double,
        endMs: Double,
        startBar: Int,
        endBar: Int,
        main: Boolean = false,
    ) = ChorusCandidate(startMs, endMs, startBar, endBar, 0.99, listOf(ChorusSignal.REPETITION, ChorusSignal.ENERGY), main)

    private fun userRow(
        startMs: Double,
        endMs: Double,
        main: Boolean,
    ) = ChorusSegmentEntity(
        videoId = ID,
        startMs = startMs,
        endMs = endMs,
        startBar = 11,
        endBar = 19,
        isMainChorus = main,
        score = 1.0,
        signals = "",
        source = ChorusSource.USER.name,
        editedAt = 5_000L,
    )

    @Test
    fun savedAnalysisComesBackIdentical() =
        runBlocking {
            val original = result()
            repository.save(ID, original, DjTrack(ID, "Tum Hi Ho", listOf("Arijit Singh"), "Aashiqui 2", 262_000L, null))
            val stored = checkNotNull(repository.load(ID))
            assertEquals(original, stored.result)
            assertEquals(1_000L, stored.analyzedAt)
            assertFalse(stored.hasUserChorus)
        }

    @Test
    fun unknownTrackLoadsAsNull() =
        runBlocking {
            assertNull(repository.load("nope"))
        }

    @Test
    fun reanalysisReplacesSectionsAndAutomaticChoruses() =
        runBlocking {
            repository.save(ID, result())
            repository.save(ID, result(version = 2, bpm = 128.0, choruses = listOf(auto(10_000.0, 20_000.0, 5, 10, main = true))))
            val stored = checkNotNull(repository.load(ID))
            assertEquals(128.0, stored.result.bpm, 0.0)
            assertEquals(2, stored.result.analysisVersion)
            assertEquals(listOf(10_000.0), stored.result.chorusCandidates.map { it.startMs })
        }

    @Test
    fun reanalysisNeverOverwritesTheUsersChorusEdits() =
        runBlocking {
            repository.save(ID, result())
            // The user fixes the chorus (what the chorus editor will do in a later stage).
            db.dao().insertChoruses(listOf(userRow(23_500.0, 38_900.0, main = true)))

            repository.save(ID, result(version = 2, bpm = 130.0, choruses = listOf(auto(1_000.0, 9_000.0, 0, 4, main = true))))

            val stored = checkNotNull(repository.load(ID))
            assertEquals("tempo is refreshed", 130.0, stored.result.bpm, 0.0)
            assertTrue(stored.hasUserChorus)
            val user = stored.result.chorusCandidates.single { it.source == ChorusSource.USER }
            assertEquals(23_500.0, user.startMs, 0.0)
            assertEquals(5_000L, user.editedAt)
            // The old automatic rows stay exactly as they were; the new automatic result was not applied.
            val autos = stored.result.chorusCandidates.filter { it.source == ChorusSource.AUTO }
            assertEquals(listOf(22_857.0, 53_333.0), autos.map { it.startMs })
            // And the user's chorus is the main one, even though an automatic row was marked main.
            assertEquals(listOf(user), stored.result.chorusCandidates.filter { it.isMain })
        }

    @Test
    fun deletingAnAnalysisKeepsTheUsersChorus() =
        runBlocking {
            repository.save(ID, result())
            db.dao().insertChoruses(listOf(userRow(23_500.0, 38_900.0, main = true)))
            repository.delete(ID)
            assertNull(repository.load(ID))
            assertEquals(1, db.dao().userChorusCount(ID))
            assertEquals(0, db.dao().choruses(ID).count { it.source == ChorusSource.AUTO.name })
        }

    @Test
    fun versionGateTellsPreAnalysisWhatToSkip() =
        runBlocking {
            repository.save(ID, result(version = 3))
            assertTrue(repository.isAnalysed(ID, 3))
            assertFalse(repository.isAnalysed(ID, 4))
            assertEquals(setOf(ID), repository.analysedIds(2))
            assertTrue(repository.analysedIds(4).isEmpty())
        }

    @Test
    fun labListShowsNamesAndCountsCoverEveryTable() =
        runBlocking {
            repository.save(ID, result(), DjTrack(ID, "Tum Hi Ho", listOf("Arijit Singh", "Mithoon"), null, null, null))
            repository.save("b", result())
            db.dao().insertChoruses(listOf(userRow(1.0, 2.0, main = false)))
            val list = db.dao().observeSummaries().first()
            assertEquals(2, list.size)
            val named = list.single { it.videoId == ID }
            assertEquals("Tum Hi Ho", named.title)
            assertNotNull(named.artists)
            assertNull(list.single { it.videoId == "b" }.title)

            val counts = repository.counts()
            assertEquals(2, counts.analyses)
            assertEquals(4, counts.sections)
            assertEquals(4, counts.autoChoruses)
            assertEquals(1, counts.userChoruses)
            assertEquals(0, counts.sessions)
        }

    private companion object {
        const val ID = "dQw4w9WgXcQ"
    }
}

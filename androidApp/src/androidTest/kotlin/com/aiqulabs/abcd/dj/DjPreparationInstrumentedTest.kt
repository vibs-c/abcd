package com.aiqulabs.abcd.dj

import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.aiqulabs.abcd.dj.analysis.MediaCodecPcmDecoder
import com.aiqulabs.abcd.dj.analysis.NativeAudioAnalyzer
import com.aiqulabs.abcd.dj.core.analysis.AudioSource
import com.aiqulabs.abcd.dj.core.brain.PlaylistPlan
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.core.prep.SessionPreparer
import com.aiqulabs.abcd.dj.core.prep.TrackPreparation
import com.aiqulabs.abcd.dj.data.DjData
import com.aiqulabs.abcd.dj.host.DjAudioFile
import com.aiqulabs.abcd.dj.host.DjAudioProvider
import com.aiqulabs.abcd.dj.host.DjAudioSource
import com.aiqulabs.abcd.dj.host.DjTrackPreparer
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 6 on a real device: the preparation pipeline (reuse, analyse, save, drop failures, clean up)
 * with a real music file, and one test of the real download path that skips itself without a network.
 * The music file is `abcd_song.mp3` in the app's external files folder (see dj-native/README.md).
 */
class DjPreparationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = DjData.analysisRepository(context)

    private fun songFile(): File {
        val file = File(checkNotNull(context.getExternalFilesDir(null)), "abcd_song.mp3")
        assumeTrue("push abcd_song.mp3 first", file.exists())
        return file
    }

    private fun song(id: String) = SongItem(id = id, title = "Test $id", thumbnail = null, artists = emptyList(), album = null)

    private fun track(id: String) = DjTrack(videoId = id, title = "Test $id", artists = listOf("Artist $id"))

    /** Hands out a private copy of the test song per call (so "release" can delete it), counting the calls. */
    private inner class CopyingSource(
        private val original: File,
        private val failFor: Set<String> = emptySet(),
    ) : DjAudioSource {
        val calls = AtomicInteger()
        val copies = mutableListOf<File>()

        override suspend fun acquire(
            song: SongItem,
            reuseMetrolistFile: Boolean,
        ): DjAudioFile {
            calls.incrementAndGet()
            check(song.id !in failFor) { "no stream for ${song.id}" }
            val copy = File(context.cacheDir, "prep-${song.id}.mp3")
            original.copyTo(copy, overwrite = true)
            synchronized(copies) { copies += copy }
            return DjAudioFile(Uri.fromFile(copy).toString(), copy)
        }
    }

    private fun preparer(
        source: DjAudioSource,
        ids: List<String>,
        keepAudio: Boolean,
    ) = DjTrackPreparer(
        repository = repository,
        audio = source,
        decoder = MediaCodecPcmDecoder(context),
        analyzer = NativeAudioAnalyzer(),
        songFor = { id -> if (id in ids) song(id) else null },
        keepAudio = keepAudio,
    )

    @Test
    fun analysesOnceThenReusesTheSavedResultAndDeletesItsOwnFile() =
        runBlocking<Unit> {
            val id = "prep-once"
            repository.delete(id)
            val source = CopyingSource(songFile())
            val preparer = preparer(source, listOf(id), keepAudio = false)

            val first = preparer.prepare(track(id))
            assertTrue("first run analyses: $first", first is TrackPreparation.Ready && !first.alreadyKnown)
            assertEquals(138.0, (first as TrackPreparation.Ready).analysis.bpm, 1.5)
            assertNotNull("saved in the DJ database", repository.load(id))
            assertFalse("the downloaded copy is deleted after analysis", source.copies.single().exists())

            val second = preparer.prepare(track(id))
            assertTrue("second run reuses the saved analysis: $second", second is TrackPreparation.Ready && second.alreadyKnown)
            assertEquals("no audio was fetched the second time", 1, source.calls.get())
            repository.delete(id)
        }

    /** Regression: `File.toURI()` gives `file:/path` (one slash); the decoder once mistook it for a plain path and failed. */
    @Test
    fun theDecoderAcceptsAFileUriWithASingleSlash() =
        runBlocking<Unit> {
            val location = songFile().toURI().toString()
            assertTrue("test setup: $location", location.startsWith("file:/") && !location.contains("://"))
            val pcm = MediaCodecPcmDecoder(context).decodeToMono(AudioSource(location))
            assertTrue("decoded ${pcm.durationMs} ms", pcm.durationMs > 30_000)
        }

    @Test
    fun keepsTheFileForASessionWhenAskedTo() =
        runBlocking<Unit> {
            val id = "prep-keep"
            repository.delete(id)
            val source = CopyingSource(songFile())
            val outcome = preparer(source, listOf(id), keepAudio = true).prepare(track(id))
            assertTrue(outcome is TrackPreparation.Ready)
            assertTrue("session files stay for the mixing engine", source.copies.single().exists())
            source.copies.forEach { it.delete() }
            repository.delete(id)
        }

    @Test
    fun unavailableAndUnreadableTracksFailWithoutThrowing() =
        runBlocking<Unit> {
            val junk = File(context.cacheDir, "prep-junk.mp3").apply { writeText("not audio at all") }
            val unavailable = preparer(CopyingSource(junk, failFor = setOf("prep-gone")), listOf("prep-gone"), keepAudio = false)
            assertTrue(unavailable.prepare(track("prep-gone")) is TrackPreparation.Failed)

            val unreadable = preparer(CopyingSource(junk), listOf("prep-junk"), keepAudio = false)
            assertTrue(unreadable.prepare(track("prep-junk")) is TrackPreparation.Failed)

            val unknown = preparer(CopyingSource(junk), emptyList(), keepAudio = false)
            assertTrue("a song we have no details for", unknown.prepare(track("prep-nobody")) is TrackPreparation.Failed)
            junk.delete()
        }

    @Test
    fun aSessionDropsAFailedTrackAndFillsItFromTheSpares() =
        runBlocking<Unit> {
            val ids = listOf("prep-s1", "prep-s2-bad", "prep-s3", "prep-s4")
            ids.forEach { repository.delete(it) }
            val source = CopyingSource(songFile(), failFor = setOf("prep-s2-bad"))
            val plan = PlaylistPlan(DjSessionSettings(), ids.map(::track), coreCount = 3, targetCount = 4)

            val result = SessionPreparer(preparer(source, ids, keepAudio = false)).prepare(plan)

            Log.i("AbcdPrep", "ready=${result.tracks.map { it.track.videoId }} failed=${result.failed.map { it.videoId }}")
            assertEquals(listOf("prep-s1", "prep-s3", "prep-s4"), result.tracks.map { it.track.videoId })
            assertEquals(listOf("prep-s2-bad"), result.failed.map { it.videoId })
            assertFalse(result.isShort)
            ids.forEach { repository.delete(it) }
        }

    /**
     * The real path: search, resolve, download into abcd's folder with `AudioCacheManager.downloadToFile`,
     * decode, analyse. Skipped when offline or when YouTube refuses (not a failure of our code).
     */
    @Test
    fun downloadsARealSongIntoOurFolderAndAnalysesIt() =
        runBlocking<Unit> {
            val found =
                try {
                    GlobalYouTubeRepository.instance
                        .searchFiltered("Daft Punk One More Time", SearchFilter.SONG, allowOfflineFallback = false)
                        .items
                        .filterIsInstance<SongItem>()
                        .firstOrNull { (it.duration ?: Long.MAX_VALUE) < 420 }
                } catch (e: Exception) {
                    null
                }
            assumeTrue("needs a network connection", found != null)
            checkNotNull(found)

            val provider = DjAudioProvider.get(context)
            provider.clear()
            val audio =
                try {
                    provider.acquire(found, reuseMetrolistFile = false)
                } catch (e: Exception) {
                    Log.w("AbcdPrep", "download failed: $e")
                    assumeTrue("YouTube did not serve the audio: $e", false)
                    return@runBlocking
                }
            val file = checkNotNull(audio.ownedFile)
            Log.i("AbcdPrep", "downloaded ${found.title}: ${file.length() / 1024} KB at ${file.name}")
            assertTrue("a real song is more than 100 KB", file.length() > 100 * 1024)
            assertTrue("we stored it in our own folder", provider.usedBytes() >= file.length())

            val pcm = MediaCodecPcmDecoder(context).decodeToMono(AudioSource(audio.location))
            val result = NativeAudioAnalyzer().analyze(pcm)
            Log.i("AbcdPrep", "analysed ${found.title}: bpm=${result.bpm} key=${result.key.camelot} chorus=${result.mainChorus}")
            assertTrue(result.bpm in 50.0..220.0)

            provider.clear()
            assertEquals("clearing removes our files", 0L, provider.usedBytes())
        }
}

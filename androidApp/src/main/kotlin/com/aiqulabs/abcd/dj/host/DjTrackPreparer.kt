package com.aiqulabs.abcd.dj.host

import com.aiqulabs.abcd.dj.analysis.AnalysisGate
import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.analysis.AudioAnalyzer
import com.aiqulabs.abcd.dj.core.analysis.AudioSource
import com.aiqulabs.abcd.dj.core.analysis.PcmDecoder
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.core.prep.TrackPreparation
import com.aiqulabs.abcd.dj.core.prep.TrackPreparer
import com.aiqulabs.abcd.dj.data.DjAnalysisRepository
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Makes one track ready for the DJ (brief §5.1): reuses a saved analysis if it is current, otherwise
 * finds or downloads the audio, decodes it, analyses it and saves the result.
 *
 * [keepAudio] keeps abcd's own downloaded file after analysis (a session's tracks, which the mixing
 * engine will play); batch library analysis passes false and deletes each file straight away.
 */
internal class DjTrackPreparer(
    private val repository: DjAnalysisRepository,
    private val audio: DjAudioSource,
    private val decoder: PcmDecoder,
    private val analyzer: AudioAnalyzer,
    private val songFor: (videoId: String) -> SongItem?,
    private val keepAudio: Boolean,
) : TrackPreparer {
    override suspend fun prepare(track: DjTrack): TrackPreparation =
        try {
            val saved = repository.load(track.videoId)
            if (saved != null && saved.result.analysisVersion >= analyzer.analysisVersion) {
                TrackPreparation.Ready(saved.result, alreadyKnown = true)
            } else {
                // One stalled download or decoder must not freeze "Preparing your mix" on this song forever.
                withTimeoutOrNull(TRACK_TIMEOUT_MS) { analyseFresh(track) }
                    ?: TrackPreparation.Failed("Timed out after ${TRACK_TIMEOUT_MS / 60_000} min (download or decode stalled)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not prepare %s (%s)", track.videoId, track.title)
            TrackPreparation.Failed(describeFailure(e))
        }

    private suspend fun analyseFresh(track: DjTrack): TrackPreparation {
        val song = songFor(track.videoId) ?: return TrackPreparation.Failed("Unknown song")
        val started = System.nanoTime()
        val result = decodeAndAnalyse(song)
        repository.save(track.videoId, result, track)
        Timber.tag(TAG).d("%s analysed in %d ms", track.videoId, (System.nanoTime() - started) / 1_000_000)
        return TrackPreparation.Ready(result, alreadyKnown = false)
    }

    /**
     * Decodes and analyses [song]. If the file Metrolist held cannot be decoded (an unusual cache format),
     * the audio is downloaded fresh into abcd's folder and tried once more.
     */
    private suspend fun decodeAndAnalyse(song: SongItem): AnalysisResult {
        val first = audio.acquire(song)
        try {
            return analyse(first)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (first.ownedFile != null) {
                first.release() // an unreadable file of ours must not be reused next time
                throw e
            }
            Timber.tag(TAG).w(e, "Metrolist's file for %s could not be read, downloading it instead", song.id)
        } finally {
            if (!keepAudio) first.release()
        }
        val second = audio.acquire(song, reuseMetrolistFile = false)
        try {
            return analyse(second)
        } catch (e: Exception) {
            second.release()
            throw e
        } finally {
            if (!keepAudio) second.release()
        }
    }

    /** Decode + analyse under [AnalysisGate], so only one decoded song is in memory at a time. */
    private suspend fun analyse(file: DjAudioFile): AnalysisResult =
        AnalysisGate.run { analyzer.analyze(decoder.decodeToMono(AudioSource(file.location))) }

    private companion object {
        const val TAG = "DjTrackPreparer"
        const val TRACK_TIMEOUT_MS = 4 * 60_000L
    }
}

/**
 * A short technical description of why something failed, for the screen and bug reports: the outermost
 * and the root cause (e.g. "DownloadTransferException ← IllegalStateException: HTTP 403").
 */
internal fun describeFailure(e: Throwable): String {
    var root = e
    while (root.cause != null && root.cause !== root) root = root.cause!!
    val outer = e.javaClass.simpleName
    val inner = "${root.javaClass.simpleName}: ${root.message ?: "no message"}"
    return (if (root === e) inner else "$outer ← $inner").take(180)
}

package com.aiqulabs.abcd.dj.host

import android.content.Context
import android.net.Uri
import com.metrolist.android.playback.AudioCacheManager
import com.metrolist.android.playback.DownloadTransferException
import com.metrolist.android.playback.forAudioDownload
import com.metrolist.android.playback.playbackAudioQuality
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.PlaybackFailureClassifier
import com.metrolist.shared.playback.findPlaybackHttpStatusCode
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.youtube.extraction.models.toContentHints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A song's audio file for analysis (and, from stage 8, for the mixing engine).
 *
 * [location] is a `file://` or `content://` URI. [ownedFile] is set only when abcd downloaded the file
 * into its own folder; files Metrolist already holds (downloads, cache) are never ours to delete.
 */
internal class DjAudioFile(
    val location: String,
    val ownedFile: File?,
    private val isPinned: (File) -> Boolean = { false },
) {
    /**
     * Deletes the file if abcd downloaded it and no running DJ mix still needs it ([DjAudioProvider.pin]).
     * Never touches Metrolist's own audio.
     */
    fun release() {
        val file = ownedFile ?: return
        if (!isPinned(file)) file.delete()
    }
}

/** Where a song's audio comes from. [DjAudioProvider] is the real one; tests use a fake. */
internal interface DjAudioSource {
    /**
     * Returns the audio of [song]. With [reuseMetrolistFile] false, a file Metrolist holds is ignored and
     * the audio is downloaded again (used when the held file turned out to be unreadable).
     */
    suspend fun acquire(
        song: SongItem,
        reuseMetrolistFile: Boolean = true,
    ): DjAudioFile
}

/**
 * Finds or fetches the audio of a song for DJ analysis (see DECISIONS.md, "Stage 6").
 *
 * 1. If Metrolist already holds the song (downloaded or cached), that file is used as it is.
 * 2. Otherwise the audio is downloaded into abcd's own folder (`cache/dj-audio`) through
 *    [AudioCacheManager.downloadToFile]. That never touches Metrolist's cache, library or downloads.
 *
 * Files abcd downloaded stay until the next session replaces them ([prune]) or the user clears them,
 * so the mixing engine can reuse them. The folder is under `cacheDir`, so Android may also reclaim it
 * when storage is low; callers must treat a missing file as "download again".
 */
internal class DjAudioProvider private constructor(
    context: Context,
    private val audioCache: AudioCacheManager,
) : DjAudioSource {
    private val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }

    // One download per song at a time: session preparation, "Analyse my library" and the engine's look-ahead
    // can ask for the same song together, and two writers on one `.part` file corrupt it.
    private val songLocks = ConcurrentHashMap<String, Mutex>()

    // Files the running DJ mix plays from; prune() and release() leave them alone.
    @Volatile private var pinned: Set<String> = emptySet()

    override suspend fun acquire(
        song: SongItem,
        reuseMetrolistFile: Boolean,
    ): DjAudioFile =
        withContext(Dispatchers.IO) {
            if (reuseMetrolistFile) {
                val held = audioCache.getCachedPlaybackResource(song, touch = false)?.url
                if (held != null && (held.startsWith("file:") || held.startsWith("content:"))) {
                    return@withContext DjAudioFile(held, ownedFile = null)
                }
            }
            val target = fileFor(song.id)
            songLocks.getOrPut(song.id) { Mutex() }.withLock {
                if (target.length() < MIN_USABLE_BYTES) download(song, target)
            }
            DjAudioFile(Uri.fromFile(target).toString(), target, ::isPinned)
        }

    /** Protects the files of [videoIds] (the mix that is playing) from [prune] and [DjAudioFile.release]. */
    fun pin(videoIds: Set<String>) {
        pinned = videoIds.mapTo(HashSet()) { fileFor(it).name }
    }

    /** The track of [videoId] has played: its file may go (stage 6: files are kept only until played). */
    fun unpin(videoId: String) {
        pinned = pinned - fileFor(videoId).name
    }

    private fun isPinned(file: File): Boolean = file.name in pinned

    private suspend fun download(
        song: SongItem,
        target: File,
    ) {
        val resolver = GlobalYouTubeRepository.streamResolver ?: error("The stream resolver is not ready")
        val partial = File(target.path + PART_SUFFIX)
        val started = System.nanoTime()
        // Like playback (2026-11-23): when YouTube refuses a stream (HTTP 403 part-way, or SABR attestation), the
        // failure is reported so the resolver sets that client aside for a while, and the next attempt resolves a
        // different one (today the tokenless VISIONOS client). Forcing SABR on the retry only hit attestation again.
        val hints = song.toContentHints().forAudioDownload(preferSabr = false)
        var lastFailure: Exception? = null
        for (attempt in 0 until DOWNLOAD_ATTEMPTS) {
            val resource =
                resolver.resolveRemote(song.id, hints, playbackAudioQuality(), sourceSong = song, refresh = attempt > 0)
                    ?: error("No playable stream")
            try {
                audioCache.downloadToFile(resource, partial)
                lastFailure = null
                break
            } catch (e: DownloadTransferException) {
                lastFailure = e
                Timber.tag(TAG).w("Download of %s via %s failed (attempt %d): %s", song.id, resource.profileId, attempt + 1, e.cause?.message ?: e.message)
                resolver.reportPlaybackFailure(
                    videoId = song.id,
                    clientName = resource.clientName,
                    responseCode = e.findPlaybackHttpStatusCode(),
                    profileId = resource.profileId,
                    hints = hints,
                    category = PlaybackFailureClassifier.fromThrowable(e),
                )
            }
        }
        lastFailure?.let { throw it }
        // Only a complete download gets the final name, so a crash mid-download never leaves a file that looks usable.
        check(partial.renameTo(target)) { "Could not store the downloaded audio" }
        Timber.tag(TAG).d("Downloaded %s: %d KB in %d ms", song.id, target.length() / 1024, (System.nanoTime() - started) / 1_000_000)
    }

    /** Deletes every downloaded file except those of [keepVideoIds] (the new session's tracks) and stale leftovers. */
    fun prune(keepVideoIds: Set<String>) {
        val keep = keepVideoIds.mapTo(HashSet()) { fileFor(it).name } + pinned
        directory.listFiles()?.forEach { file -> if (file.name !in keep && !file.name.endsWith(PART_SUFFIX)) file.delete() }
    }

    /** Total size of the files abcd downloaded, for the Lab. */
    fun usedBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes everything abcd downloaded. */
    fun clear() {
        directory.listFiles()?.forEach { if (!isPinned(it)) it.delete() }
    }

    /** True when the phone has room for [trackCount] more downloads plus a safety margin. */
    fun hasSpaceFor(trackCount: Int): Boolean = directory.usableSpace >= trackCount * ESTIMATED_BYTES_PER_TRACK + SAFETY_MARGIN_BYTES

    private fun fileFor(videoId: String) = File(directory, videoId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".audio")

    companion object {
        private const val TAG = "DjAudioProvider"
        private const val DIRECTORY = "dj-audio"
        private const val PART_SUFFIX = ".part"

        /** A refused client is reported and the next attempt resolves another one. */
        private const val DOWNLOAD_ATTEMPTS = 3

        /** A real audio file is far bigger than this; smaller means a broken download. */
        private const val MIN_USABLE_BYTES = 4096L

        /** A 4-minute YouTube Music track is about 4 to 6 MB; 10 MB covers long ones. */
        private const val ESTIMATED_BYTES_PER_TRACK = 10L * 1024 * 1024
        private const val SAFETY_MARGIN_BYTES = 64L * 1024 * 1024

        @Volatile
        private var instance: DjAudioProvider? = null

        /** The app-wide provider, so the session, the library batch and the Lab share one folder. */
        fun get(context: Context): DjAudioProvider =
            instance ?: synchronized(this) {
                instance ?: DjAudioProvider(context.applicationContext, KoinPlatform.getKoin().get()).also { instance = it }
            }
    }
}

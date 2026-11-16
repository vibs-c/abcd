package com.aiqulabs.abcd.dj.host

import android.content.Context
import android.net.Uri
import com.metrolist.android.playback.AudioCacheManager
import com.metrolist.android.playback.downloadWithResourceRefresh
import com.metrolist.android.playback.forAudioDownload
import com.metrolist.android.playback.playbackAudioQuality
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.youtube.extraction.models.toContentHints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import timber.log.Timber
import java.io.File

/**
 * A song's audio file for analysis (and, from stage 8, for the mixing engine).
 *
 * [location] is a `file://` or `content://` URI. [ownedFile] is set only when abcd downloaded the file
 * into its own folder; files Metrolist already holds (downloads, cache) are never ours to delete.
 */
internal class DjAudioFile(
    val location: String,
    val ownedFile: File?,
) {
    /** Deletes the file if abcd downloaded it. Never touches Metrolist's own audio. */
    fun release() {
        ownedFile?.delete()
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
            if (target.length() >= MIN_USABLE_BYTES) return@withContext DjAudioFile(Uri.fromFile(target).toString(), target)
            download(song, target)
            DjAudioFile(Uri.fromFile(target).toString(), target)
        }

    private suspend fun download(
        song: SongItem,
        target: File,
    ) {
        val resolver = GlobalYouTubeRepository.streamResolver ?: error("The stream resolver is not ready")
        val partial = File(target.path + ".part")
        val started = System.nanoTime()
        downloadWithResourceRefresh(
            resolve = { refresh ->
                resolver.resolveRemote(
                    song.id,
                    song.toContentHints().forAudioDownload(preferSabr = refresh),
                    playbackAudioQuality(),
                    sourceSong = song,
                    refresh = refresh,
                )
            },
        ) { resource -> audioCache.downloadToFile(resource, partial) }
        // Only a complete download gets the final name, so a crash mid-download never leaves a file that looks usable.
        check(partial.renameTo(target)) { "Could not store the downloaded audio" }
        Timber.tag(TAG).d("Downloaded %s: %d KB in %d ms", song.id, target.length() / 1024, (System.nanoTime() - started) / 1_000_000)
    }

    /** Deletes every downloaded file except those of [keepVideoIds] (the new session's tracks) and stale leftovers. */
    fun prune(keepVideoIds: Set<String>) {
        val keep = keepVideoIds.mapTo(HashSet()) { fileFor(it).name }
        directory.listFiles()?.forEach { file -> if (file.name !in keep) file.delete() }
    }

    /** Total size of the files abcd downloaded, for the Lab. */
    fun usedBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes everything abcd downloaded. */
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    /** True when the phone has room for [trackCount] more downloads plus a safety margin. */
    fun hasSpaceFor(trackCount: Int): Boolean = directory.usableSpace >= trackCount * ESTIMATED_BYTES_PER_TRACK + SAFETY_MARGIN_BYTES

    private fun fileFor(videoId: String) = File(directory, videoId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".audio")

    companion object {
        private const val TAG = "DjAudioProvider"
        private const val DIRECTORY = "dj-audio"

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

package com.aiqulabs.abcd.dj.host

import android.content.Context
import com.aiqulabs.abcd.dj.analysis.MediaCodecPcmDecoder
import com.aiqulabs.abcd.dj.analysis.NativeAudioAnalyzer
import com.aiqulabs.abcd.dj.core.brain.DjPlaylistBuilder
import com.aiqulabs.abcd.dj.core.brain.PlaylistOrderer
import com.aiqulabs.abcd.dj.core.feedback.FeedbackSnapshot
import com.aiqulabs.abcd.dj.core.mix.MixPlanner
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import com.aiqulabs.abcd.dj.core.prep.SessionPreparer
import com.aiqulabs.abcd.dj.core.brain.LanguageGate
import com.aiqulabs.abcd.dj.core.tagging.MetadataTrackTagger
import com.aiqulabs.abcd.dj.core.tagging.StoredTagTagger
import com.aiqulabs.abcd.dj.core.tagging.TagType
import com.aiqulabs.abcd.dj.data.DjData
import com.aiqulabs.abcd.dj.ui.setup.DjStartState
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.shared.settings.BlockedContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Runs "Start DJ": builds the playlist from the setup choices (stage 3), analyses every track before
 * playback (stage 6, brief §5.1), orders the analysed tracks (brief §7.2) and hands them to the DJ engine
 * through [DjPlayback] (stage 8).
 *
 * Application-wide on purpose: the work keeps running if the user switches tabs, and its progress is
 * shown again when they come back.
 */
object DjController {
    /** Fewer playable songs than this is reported as a failure instead of playing a tiny mix. */
    internal const val MIN_PLAYABLE_TRACKS = 5

    private const val TAG = "DjController"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow<DjStartState>(DjStartState.Idle)
    private var job: Job? = null

    val state: StateFlow<DjStartState> = mutableState.asStateFlow()

    /** True while a mix is being built or prepared (library analysis waits for it). */
    internal val isPreparing: Boolean
        get() = mutableState.value.let { it is DjStartState.Building || it is DjStartState.Preparing }

    /** Starts building, preparing and playing a mix. Ignored while one is already running. */
    fun start(
        context: Context,
        settings: DjSessionSettings,
        playerConnection: PlayerConnection,
    ) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        mutableState.value = DjStartState.Building
        job =
            scope.launch {
                val result =
                    try {
                        buildPrepareAndPlay(appContext, settings, playerConnection)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Building the DJ mix failed")
                        DjStartState.Failed(DjStartState.Reason.ERROR, detail = describeFailure(e))
                    }
                mutableState.value = result
            }
    }

    /** Stops a running build or preparation and returns to the idle setup screen. Analysed songs are kept. */
    fun cancel() {
        job?.cancel()
        job = null
        mutableState.value = DjStartState.Idle
    }

    /** Clears a finished or failed status card. Does not touch a running build. */
    fun dismissResult() {
        val current = mutableState.value
        if (current !is DjStartState.Building && current !is DjStartState.Preparing) mutableState.value = DjStartState.Idle
    }

    private suspend fun buildPrepareAndPlay(
        context: Context,
        settings: DjSessionSettings,
        playerConnection: PlayerConnection,
    ): DjStartState {
        val source = YouTubeCandidateSource()
        // Feedback (brief §6.3): banned songs stay out, often-removed ones sink, liked pairs and techniques win.
        val feedback =
            try {
                DjData.feedbackRepository(context).snapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not read feedback; mixing without it")
                FeedbackSnapshot.EMPTY
            }
        val feedbackStore = DjData.feedbackRepository(context)
        // Title tags plus what earlier analyses learnt (lyrics language) and the user's own corrections (stage 12).
        val tagger = StoredTagTagger(MetadataTrackTagger()) { id -> runCatching { feedbackStore.storedTags(id) }.getOrDefault(emptyList()) }
        val built = DjPlaylistBuilder(source, tagger).build(settings, bannedVideoIds = feedback.banned)
        val playableIds =
            BlockedContent
                .filterPlayableSongs(
                    built.tracks.mapNotNull { source.songFor(it.videoId) },
                ).mapTo(HashSet()) { it.id }
        val plan = built.copy(tracks = built.tracks.filter { it.videoId in playableIds })
        if (plan.tracks.isEmpty() && source.requestFailed) return DjStartState.Failed(DjStartState.Reason.ERROR)
        if (plan.tracks.size < MIN_PLAYABLE_TRACKS) {
            return DjStartState.Failed(DjStartState.Reason.NOT_ENOUGH_SONGS, foundCount = plan.tracks.size)
        }

        val repository = DjData.analysisRepository(context)
        val analyzer = NativeAudioAnalyzer()
        val audio = DjAudioProvider.get(context)
        val known = repository.analysedIds(analyzer.analysisVersion)
        val firstTracks = plan.tracks.take(plan.coreCount)
        val toFetch = firstTracks.count { it.videoId !in known }
        if (!audio.hasSpaceFor(toFetch)) return DjStartState.Failed(DjStartState.Reason.NOT_ENOUGH_STORAGE)
        audio.prune(plan.tracks.mapTo(HashSet()) { it.videoId })

        val preparer =
            DjTrackPreparer(
                repository = repository,
                audio = audio,
                decoder = MediaCodecPcmDecoder(context),
                analyzer = analyzer,
                songFor = source::songFor,
                keepAudio = true,
                feedback = DjData.feedbackRepository(context),
            )
        val startedAt = System.nanoTime()
        val prepared =
            SessionPreparer(preparer).prepare(plan) { progress ->
                mutableState.value = DjStartState.Preparing(progress)
            }
        Timber.tag(TAG).i(
            "Prepared %d of %d tracks (%d dropped, %d already analysed) in %d s",
            prepared.tracks.size,
            plan.coreCount,
            prepared.failed.size,
            firstTracks.size - toFetch,
            (System.nanoTime() - startedAt) / 1_000_000_000,
        )
        if (prepared.tracks.size < MIN_PLAYABLE_TRACKS) {
            // Nothing at all could be analysed: most likely a connection problem rather than a thin catalogue.
            val noneWorked = prepared.tracks.isEmpty() && prepared.failed.isNotEmpty()
            val reason = if (noneWorked) DjStartState.Reason.ERROR else DjStartState.Reason.NOT_ENOUGH_SONGS
            return DjStartState.Failed(reason, foundCount = prepared.tracks.size, detail = prepared.lastFailureReason)
        }

        // Second language check, now that every song's lyrics have been looked at (owner report: Hindi songs in an
        // English-only mix had English-looking titles). A song in a language that was not chosen is dropped.
        val fitting = dropWrongLanguage(prepared.tracks, plan.settings, tagger)
        if (fitting.size < MIN_PLAYABLE_TRACKS) {
            return DjStartState.Failed(DjStartState.Reason.NOT_ENOUGH_SONGS, foundCount = fitting.size)
        }
        val ordered = PlaylistOrderer(feedback = feedback).order(fitting, plan.settings)
        logOrder(ordered)
        // Stage 8: the mix plays through the DJ engine (its own two decks), not Metrolist's queue.
        val songs = ordered.mapNotNull { t -> source.songFor(t.track.videoId)?.let { t.track.videoId to it } }.toMap()
        val playable =
            ordered.mapNotNull { t ->
                val song = songs[t.track.videoId] ?: return@mapNotNull null
                t.copy(
                    track =
                        t.track.copy(
                            thumbnailUrl = t.track.thumbnailUrl ?: song.thumbnail,
                            durationMs = t.track.durationMs ?: t.analysis.durationMs.toLong(),
                        ),
                )
            }
        val started =
            withContext(Dispatchers.Main) {
                // Metrolist binds its music service lazily; the DJ host lives in it, so make sure it is up.
                runCatching { playerConnection.awaitConnected() }
                    .onFailure { Timber.tag(TAG).w(it, "Music service did not connect") }
                DjPlayback.start(playable, songs, plan.settings, MixPlanner(techniqueScores = feedback.techniqueScores))
            }
        if (!started) return DjStartState.Failed(DjStartState.Reason.ERROR)
        return DjStartState.Started(playable.size)
    }

    /** Drops prepared songs whose stored or lyrics language was not chosen; their singers' other songs go too. */
    private suspend fun dropWrongLanguage(
        tracks: List<PreparedTrack>,
        settings: DjSessionSettings,
        tagger: StoredTagTagger,
    ): List<PreparedTrack> {
        if (settings.anyLanguage) return tracks
        val tags = tracks.associate { it.track.videoId to tagger.tag(it.track) }
        val rejected = tracks.filterNot { LanguageGate.allows(it.track, tags.getValue(it.track.videoId), settings) }
        val indianSingers = rejected.mapNotNull { it.track.artistKeys.singleOrNull() }.toSet()
        val kept =
            tracks.filter { t ->
                t !in rejected && LanguageGate.allows(t.track, tags.getValue(t.track.videoId), settings, indianArtist = t.track.artistKeys.any { it in indianSingers })
            }
        (tracks - kept.toSet()).forEach { t ->
            val languages = tags.getValue(t.track.videoId).filter { it.type == TagType.LANGUAGE && it.confidence > 0f }
            Timber.tag(TAG).i("Left out %s (%s): language %s", t.track.title, t.track.artists.joinToString(), languages.joinToString { "${it.value}/${it.source}" })
        }
        return kept
    }

    /** One line per track so the chosen order can be checked in the log (BPM, key, energy). */
    private fun logOrder(ordered: List<PreparedTrack>) {
        ordered.forEachIndexed { index, t ->
            Timber.tag(TAG).d(
                "#%d %s | %.1f BPM | %s | energy %.2f",
                index + 1,
                t.track.title,
                t.analysis.bpm,
                t.analysis.key.camelot,
                t.analysis.energyCurve
                    .takeIf { it.isNotEmpty() }
                    ?.average() ?: Double.NaN,
            )
        }
    }
}

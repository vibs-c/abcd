package com.aiqulabs.abcd.dj.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import kotlin.math.abs

/**
 * One of the engine's two decks: an ExoPlayer that plays a local audio file through [DeckGainProcessor].
 *
 * Decks never request audio focus (the engine holds it for the whole session, so it is never handed over
 * mid-blend), play local files (wake mode LOCAL), share one audio session id so system equalisers apply
 * to both, and prefer the in-process libopus renderer when the app ships it.
 */
@OptIn(UnstableApi::class)
internal class Deck(
    context: Context,
    val name: String,
    audioSessionId: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onDeckStateChanged(deck: Deck)

        fun onDeckError(
            deck: Deck,
            error: PlaybackException,
        )
    }

    private val gain = DeckGainProcessor()

    val player: ExoPlayer =
        ExoPlayer
            .Builder(context, DeckRenderersFactory(context, gain))
            .setAudioAttributes(MUSIC, false)
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .also { player ->
                player.setAudioSessionId(audioSessionId)
                player.addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) = listener.onDeckStateChanged(this@Deck)

                        override fun onPlayerError(error: PlaybackException) = listener.onDeckError(this@Deck, error)
                    },
                )
            }

    /** The track on this deck, and its position in the session's track list; null/-1 when empty. */
    var track: PreparedTrack? = null
        private set
    var trackIndex: Int = -1
        private set

    val isReady: Boolean get() = track != null && player.playbackState == Player.STATE_READY
    val hasEnded: Boolean get() = track != null && player.playbackState == Player.STATE_ENDED
    val positionMs: Long get() = player.currentPosition
    val durationMs: Long get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (track?.analysis?.durationMs?.toLong() ?: 0L)

    /** Current tempo factor (1.0 = original); changes keep the pitch. */
    val speed: Float get() = player.playbackParameters.speed

    /** Loads [uri] paused and silent at [startMs] and [speed]; the engine starts it when its transition begins. */
    fun load(
        track: PreparedTrack,
        index: Int,
        uri: Uri,
        startMs: Long,
        speed: Float = 1f,
    ) {
        this.track = track
        trackIndex = index
        gain.setTrimDb(DeckGainProcessor.trimDbFor(track.analysis.lufs))
        gain.resetEcho()
        player.playWhenReady = false
        player.volume = 0f
        setSpeed(speed)
        player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(track.track.videoId).build(), startMs)
        player.prepare()
    }

    /** Tempo change with the pitch kept (beatmatching, §9.1). */
    fun setSpeed(speed: Float) {
        if (abs(player.playbackParameters.speed - speed) > 1e-4f) player.playbackParameters = PlaybackParameters(speed, 1f)
    }

    fun setEcho(params: DeckGainProcessor.EchoParams) = gain.setEcho(params)

    /** Stops and empties the deck so it can take the next track; the ExoPlayer itself is kept. */
    fun clear() {
        gain.resetEcho()
        setSpeed(1f)
        track = null
        trackIndex = -1
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        player.volume = 0f
    }

    fun release() {
        track = null
        trackIndex = -1
        player.release()
    }

    private class DeckRenderersFactory(
        context: Context,
        private val gain: DeckGainProcessor,
    ) : DefaultRenderersFactory(context) {
        init {
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean,
        ): AudioSink =
            DefaultAudioSink
                .Builder(context)
                .setAudioProcessors(arrayOf(gain))
                .build()
    }

    private companion object {
        val MUSIC: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
    }
}

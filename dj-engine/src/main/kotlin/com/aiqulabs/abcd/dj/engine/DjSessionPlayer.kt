package com.aiqulabs.abcd.dj.engine

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The player the media session shows while DJ Mode is on (design §4.3): notification, lock screen,
 * headset and Bluetooth buttons, Wear and Android Auto all talk to this.
 *
 * Play/pause and next go to the engine; stop and "play these other items" go to [requests] (the host
 * ends DJ Mode). Seek and previous are not offered: inside a planned mix they have no clear meaning.
 */
@OptIn(UnstableApi::class)
internal class DjSessionPlayer(
    private val engine: ExoDjMixEngine,
    private val requests: () -> DjSessionRequests?,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    /** Re-reads the engine's state; call after every engine change. */
    fun refresh() = invalidateState()

    override fun getState(): State {
        val s = engine.state.value
        val tracks = listOfNotNull(s.current) + s.upcoming
        val playlist =
            tracks.mapIndexed { i, track ->
                MediaItemData
                    .Builder("${s.currentIndex + i}:${track.videoId}")
                    .setMediaItem(mediaItemFor(track))
                    .setDurationUs(track.durationMs?.let { it * 1000 } ?: C.TIME_UNSET)
                    .build()
            }
        val playbackState =
            when {
                playlist.isEmpty() -> Player.STATE_IDLE
                s.phase == DjEngineState.Phase.FINISHED -> Player.STATE_ENDED
                s.phase == DjEngineState.Phase.STARTING || !engine.isMainDeckReady() -> Player.STATE_BUFFERING
                else -> Player.STATE_READY
            }
        return State
            .Builder()
            .setAvailableCommands(COMMANDS)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build())
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs { if (playlist.isEmpty()) 0L else engine.mainPositionMs() }
            .setPlayWhenReady(s.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (playlist.isEmpty()) Player.STATE_IDLE else playbackState)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) engine.play() else engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) engine.skipNext()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        requests()?.onStopRequested()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        requests()?.onForeignPlaybackRequested(mediaItems.toList(), startIndex.coerceAtLeast(0), startPositionMs.coerceAtLeast(0L))
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(
        index: Int,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<*> {
        requests()?.onForeignPlaybackRequested(mediaItems.toList(), 0, 0L)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun mediaItemFor(track: DjTrack): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(track.videoId)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(track.title)
                    .setArtist(track.artists.joinToString(", "))
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.thumbnailUrl?.let(Uri::parse))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            ).build()

    private companion object {
        val COMMANDS: Player.Commands =
            Player.Commands
                .Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_SET_MEDIA_ITEM,
                    Player.COMMAND_CHANGE_MEDIA_ITEMS,
                    Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                    Player.COMMAND_RELEASE,
                ).build()
    }
}

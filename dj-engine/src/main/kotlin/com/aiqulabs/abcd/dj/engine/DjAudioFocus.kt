package com.aiqulabs.abcd.dj.engine

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Audio focus for the whole DJ session (brief §9.1, design §8), plus "becoming noisy" (headphones
 * unplugged). Calls arrive as a transient focus loss; navigation prompts as "may duck".
 * All callbacks run on the main thread.
 */
internal class DjAudioFocus(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onFocusGained()

        /** A call or a short interruption: pause, and resume when focus comes back. */
        fun onFocusLostTransient()

        /** Another app took over for good: pause and stay paused. */
        fun onFocusLost()

        fun onDuck(ducked: Boolean)

        fun onBecomingNoisy()
    }

    enum class Result { GRANTED, DELAYED, FAILED }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    var held = false
        private set
    private var noisyRegistered = false

    private val changeListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    held = true
                    listener.onDuck(false)
                    listener.onFocusGained()
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> listener.onFocusLostTransient()

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listener.onDuck(true)

                AudioManager.AUDIOFOCUS_LOSS -> {
                    held = false
                    listener.onFocusLost()
                }
            }
        }

    private val request: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                ).setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(changeListener, handler)
                .build()
        } else {
            null
        }

    private val noisyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) listener.onBecomingNoisy()
            }
        }

    fun request(): Result {
        registerNoisy()
        if (held) return Result.GRANTED
        val result =
            if (request != null) {
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(changeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                held = true
                Result.GRANTED
            }

            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> Result.DELAYED

            else -> Result.FAILED
        }
    }

    fun abandon() {
        if (request != null) {
            audioManager.abandonAudioFocusRequest(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(changeListener)
        }
        held = false
        unregisterNoisy()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerNoisy() {
        if (noisyRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(noisyReceiver, filter)
        }
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }
}

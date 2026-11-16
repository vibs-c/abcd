package com.aiqulabs.abcd.dj.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.aiqulabs.abcd.dj.core.analysis.AudioSource
import com.aiqulabs.abcd.dj.core.analysis.PcmAudio
import com.aiqulabs.abcd.dj.core.analysis.PcmDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes any audio file Android can play (Opus/WebM, AAC/M4A, MP3, ...) to mono float PCM (brief §5.2).
 * WebM/Opus goes through [OpusPcmDecoder] (in-process libopus, several times faster) when libopus is built
 * for this CPU; everything else, and any file that path cannot read, uses [MediaExtractor] and
 * [MediaCodec]. The sample rate is left as the file's own; the native analyzer resamples.
 *
 * Accepts a file path, or a `file://`, `content://` or `http(s)://` URI string.
 */
class MediaCodecPcmDecoder(
    context: Context,
) : PcmDecoder {
    private val appContext = context.applicationContext
    private val opus = OpusPcmDecoder(appContext)

    override suspend fun decodeToMono(source: AudioSource): PcmAudio =
        withContext(Dispatchers.IO) {
            val inApp =
                try {
                    opus.decodeOrNull(source)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "In-app Opus decoding failed, using the platform decoder", e)
                    null
                }
            if (inApp == null) Log.i(TAG, "Platform decoder for ${source.location.substringAfterLast('/')}")
            inApp ?: decodeWithMediaCodec(source)
        }

    private suspend fun decodeWithMediaCodec(source: AudioSource): PcmAudio =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                // "file:/x" (java.io.File.toURI) has one slash, so test for the scheme, not for "://".
                if (source.location.contains("://") || source.location.startsWith("file:")) {
                    extractor.setDataSource(appContext, Uri.parse(source.location), null)
                } else {
                    extractor.setDataSource(source.location)
                }
                decode(extractor)
            } finally {
                extractor.release()
            }
        }

    private suspend fun decode(extractor: MediaExtractor): PcmAudio {
        val trackIndex =
            (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track")
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))

        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
        val sink = MonoSink(MonoSink.capacityFor(durationUs, sampleRate))

        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                // Feed every input buffer the codec will take right now, without waiting.
                while (!inputDone) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index < 0) break
                    val buffer = checkNotNull(codec.getInputBuffer(index))
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                // Then take every decoded buffer that is ready; wait briefly only when none is.
                var timeout = TIMEOUT_US
                while (!outputDone) {
                    val out = codec.dequeueOutputBuffer(info, timeout)
                    timeout = 0
                    when {
                        out >= 0 -> {
                            val buffer = checkNotNull(codec.getOutputBuffer(out))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            sink.append(buffer.order(ByteOrder.nativeOrder()), encoding, channels)
                            codec.releaseOutputBuffer(out, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }

                        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val changed = codec.outputFormat
                            channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            sampleRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            encoding =
                                if (changed.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                    changed.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                } else {
                                    AudioFormat.ENCODING_PCM_16BIT
                                }
                        }

                        else -> break // INFO_TRY_AGAIN_LATER: go back and feed more input
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        if (sink.size == 0) throw IOException("Decoder produced no audio")
        return sink.toPcmAudio(sampleRate)
    }

    private companion object {
        const val TAG = "AbcdDecoder"

        /** How long to wait for decoded audio when none is ready yet (only then). */
        const val TIMEOUT_US = 5_000L
    }
}

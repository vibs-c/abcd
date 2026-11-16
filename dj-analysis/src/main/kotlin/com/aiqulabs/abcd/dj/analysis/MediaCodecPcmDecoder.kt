package com.aiqulabs.abcd.dj.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.aiqulabs.abcd.dj.core.analysis.AudioSource
import com.aiqulabs.abcd.dj.core.analysis.PcmAudio
import com.aiqulabs.abcd.dj.core.analysis.PcmDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes any audio file Android can play (Opus/WebM, AAC/M4A, MP3, ...) to mono float PCM with
 * [MediaExtractor] and [MediaCodec] (brief §5.2). The sample rate is left as the file's own; the
 * native analyzer resamples.
 *
 * Accepts a file path, or a `file://`, `content://` or `http(s)://` URI string.
 */
class MediaCodecPcmDecoder(
    context: Context,
) : PcmDecoder {
    private val appContext = context.applicationContext

    override suspend fun decodeToMono(source: AudioSource): PcmAudio =
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
        val sink = MonoSink()

        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
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
                }
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
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
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        if (sink.size == 0) throw IOException("Decoder produced no audio")
        return PcmAudio(sink.toFloatArray(), sampleRate)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}

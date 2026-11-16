package com.aiqulabs.abcd.dj.analysis

import android.content.Context
import android.media.AudioFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.opus.OpusDecoder
import androidx.media3.decoder.opus.OpusLibrary
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import com.aiqulabs.abcd.dj.core.analysis.AudioSource
import com.aiqulabs.abcd.dj.core.analysis.PcmAudio
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.LockSupport

/**
 * Decodes a WebM/Opus file (what YouTube Music serves most often) to mono PCM entirely inside the app:
 * Media3's Matroska extractor reads the packets and the libopus decoder Metrolist already ships
 * (`:opusDecoder`) decodes them.
 *
 * Why: the platform route (MediaExtractor + MediaCodec) makes a round trip to a system process for every
 * 20 ms Opus packet, about 15,000 per song, and that dominated preparation time. Output is 16-bit PCM like
 * the platform Opus decoder's, so analysis results stay comparable.
 *
 * [decodeOrNull] returns null when this path does not apply (not Matroska, not Opus, or libopus is not
 * built for this CPU, e.g. x86): the caller then uses MediaCodec.
 */
@OptIn(UnstableApi::class)
internal class OpusPcmDecoder(
    private val context: Context,
) {
    suspend fun decodeOrNull(source: AudioSource): PcmAudio? {
        if (!OpusLibrary.isAvailable()) return null
        return openFile(source.location).use { file ->
            val extractor = MatroskaExtractor(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
            try {
                if (!extractor.sniff(file.inputAt(0))) return null
                val output = Output()
                extractor.init(output)
                var input = file.inputAt(0)
                val position = PositionHolder()
                while (!output.finished) {
                    currentCoroutineContext().ensureActive()
                    when (extractor.read(input, position)) {
                        Extractor.RESULT_END_OF_INPUT -> break
                        Extractor.RESULT_SEEK -> input = file.inputAt(position.position)
                    }
                    if (output.notOpus) return null
                }
                output.finish()
            } finally {
                extractor.release()
            }
        }
    }

    /** Receives the extractor's packets and runs them through libopus into a [MonoSink]. */
    private class Output :
        ExtractorOutput,
        TrackOutput {
        var notOpus = false
        var finished = false
        private var audioTrackId = -1
        private var decoder: OpusDecoder? = null
        private var durationUs = C.TIME_UNSET
        private var sink: MonoSink? = null
        private var endOfStream = false

        // Bytes of the current packet as the extractor writes them; a packet ends with sampleMetadata().
        private var pending = ByteArray(PACKET_BYTES)
        private var pendingSize = 0

        override fun track(
            id: Int,
            type: Int,
        ): TrackOutput {
            if (type == C.TRACK_TYPE_AUDIO && audioTrackId == -1) audioTrackId = id
            return if (id == audioTrackId) this else IgnoredTrack
        }

        override fun endTracks() {
            if (audioTrackId == -1) notOpus = true
        }

        override fun seekMap(seekMap: SeekMap) {
            if (seekMap.durationUs != C.TIME_UNSET) durationUs = seekMap.durationUs
        }

        override fun durationUs(durationUs: Long) {
            if (durationUs != C.TIME_UNSET) this.durationUs = durationUs
        }

        override fun format(format: Format) {
            if (decoder != null) return
            if (format.sampleMimeType != MimeTypes.AUDIO_OPUS) {
                notOpus = true
                return
            }
            decoder = OpusDecoder(BUFFERS, BUFFERS, PACKET_BYTES, format.initializationData, null, false)
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            ensurePending(length)
            val read = input.read(pending, pendingSize, length)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            pendingSize += read
            return read
        }

        override fun sampleData(
            data: ParsableByteArray,
            length: Int,
            sampleDataPart: Int,
        ) {
            ensurePending(length)
            data.readBytes(pending, pendingSize, length)
            pendingSize += length
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            val opus = decoder ?: return
            val start = pendingSize - offset - size
            var mainStart = start
            var mainSize = size
            var extraStart = 0
            var extraSize = 0
            // With supplemental data (Opus discard padding at the end of the song) the packet is laid out
            // as [main size: 4 bytes][main data][supplemental data], as Media3's SampleQueue expects.
            if (flags and C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA != 0) {
                mainSize = ByteBuffer.wrap(pending, start, 4).int
                mainStart = start + 4
                extraStart = mainStart + mainSize
                extraSize = size - 4 - mainSize
            }
            val input = dequeueInput(opus)
            input.ensureSpaceForWrite(mainSize)
            checkNotNull(input.data).put(pending, mainStart, mainSize)
            if (extraSize > 0) {
                input.resetSupplementalData(extraSize)
                checkNotNull(input.supplementalData).put(pending, extraStart, extraSize)
                input.addFlag(C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
            }
            input.timeUs = timeUs
            input.flip()
            opus.queueInputBuffer(input)
            // Keep any bytes that already belong to the next packet.
            System.arraycopy(pending, pendingSize - offset, pending, 0, offset)
            pendingSize = offset
            drain(opus)
        }

        /** Flushes the decoder and returns the decoded song. */
        fun finish(): PcmAudio? {
            val opus = decoder ?: return null
            try {
                val input = dequeueInput(opus)
                input.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                opus.queueInputBuffer(input)
                while (!endOfStream) {
                    if (!drain(opus)) LockSupport.parkNanos(WAIT_NANOS)
                }
                finished = true
                val mono = sink ?: return null
                if (mono.size == 0) throw IOException("Decoder produced no audio")
                return mono.toPcmAudio(SAMPLE_RATE)
            } finally {
                opus.release()
            }
        }

        private fun dequeueInput(opus: OpusDecoder): DecoderInputBuffer {
            while (true) {
                opus.dequeueInputBuffer()?.let { return it }
                if (!drain(opus)) LockSupport.parkNanos(WAIT_NANOS)
            }
        }

        /** Moves every decoded buffer that is ready into the sink. Returns false when none was ready. */
        private fun drain(opus: OpusDecoder): Boolean {
            var any = false
            while (true) {
                val out = opus.dequeueOutputBuffer() ?: return any
                any = true
                if (out.isEndOfStream) {
                    endOfStream = true
                } else if (!out.shouldBeSkipped) {
                    val data = checkNotNull(out.data)
                    if (data.hasRemaining()) monoSink().append(data, AudioFormat.ENCODING_PCM_16BIT, opus.channelCount)
                }
                out.release()
            }
        }

        private fun monoSink(): MonoSink = sink ?: MonoSink(MonoSink.capacityFor(durationUs, SAMPLE_RATE)).also { sink = it }

        private fun ensurePending(extra: Int) {
            if (pendingSize + extra > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + extra))
        }
    }

    /** Swallows the data of tracks we do not decode (there are none in YouTube's audio files). */
    private object IgnoredTrack : TrackOutput {
        private val scratch = ByteArray(4096)

        override fun format(format: Format) = Unit

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            val read = input.read(scratch, 0, minOf(length, scratch.size))
            if (read == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw EOFException()
            return read
        }

        override fun sampleData(
            data: ParsableByteArray,
            length: Int,
            sampleDataPart: Int,
        ) = data.skipBytes(length)

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) = Unit
    }

    /** A seekable file (plain path, `file:` or `content:` URI) read through one [FileChannel]. */
    private class SeekableFile(
        private val channel: FileChannel,
        private val closeable: Closeable,
    ) : Closeable {
        private val length = channel.size()

        fun inputAt(position: Long): DefaultExtractorInput {
            channel.position(position)
            val reader =
                DataReader { buffer, offset, count ->
                    if (count == 0) return@DataReader 0
                    val read = channel.read(ByteBuffer.wrap(buffer, offset, count))
                    if (read < 0) C.RESULT_END_OF_INPUT else read
                }
            return DefaultExtractorInput(reader, position, length)
        }

        override fun close() = closeable.close()
    }

    private fun openFile(location: String): SeekableFile =
        if (location.startsWith("content:")) {
            val descriptor =
                context.contentResolver.openFileDescriptor(Uri.parse(location), "r")
                    ?: throw IOException("Cannot open $location")
            val stream = FileInputStream(descriptor.fileDescriptor)
            SeekableFile(stream.channel) {
                stream.close()
                descriptor.close()
            }
        } else {
            val path = if (location.startsWith("file:")) checkNotNull(Uri.parse(location).path) else location
            val file = RandomAccessFile(path, "r")
            SeekableFile(file.channel, file)
        }

    private companion object {
        /** libopus always decodes at 48 kHz. */
        const val SAMPLE_RATE = 48_000
        const val BUFFERS = 16
        const val PACKET_BYTES = 8 * 1024
        const val WAIT_NANOS = 200_000L
    }
}

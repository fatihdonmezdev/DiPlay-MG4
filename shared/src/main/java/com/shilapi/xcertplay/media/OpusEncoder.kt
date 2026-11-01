package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder as ConcentusEncoder
import java.io.Closeable

/**
 * Encodes 20 ms chunks of 48 kHz mono PCM into raw Opus access units for the CarPlay
 * microphone uplink.
 *
 * Uses the platform encoder when there is one (Android 10+). Android 9 has none, so it falls
 * back to Concentus, a pure-Java port of libopus.
 */
internal class OpusEncoder(bitrate: Int) : Closeable {
    private val codec: MediaCodec? = if (!OpusSupport.encoderAvailable()) null else try {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            SAMPLE_RATE,
            CHANNELS,
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).also {
            it.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            it.start()
            Log.i(TAG, "Opus microphone encoder started bitrate=$bitrate")
        }
    } catch (error: Exception) {
        Log.w(TAG, "Opus microphone encoder unavailable", error)
        null
    }
    private val software: ConcentusEncoder? = if (codec != null) null else try {
        ConcentusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP).also {
            it.setBitrate(bitrate)
            it.setComplexity(SOFTWARE_COMPLEXITY)
            Log.i(TAG, "Opus microphone software encoder started bitrate=$bitrate")
        }
    } catch (error: Throwable) {
        if (error is VirtualMachineError) throw error
        Log.w(TAG, "Opus microphone software encoder unavailable", error)
        null
    }
    private val samples = ShortArray(FRAME_SAMPLES)
    private val packet = ByteArray(MAX_PACKET_BYTES)

    /** "platform" or "software", for the diagnostic log. */
    val kind: String get() = if (codec != null) "platform" else if (software != null) "software" else "none"
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationTimeUs = 0L
    private var closed = false
    private var outputPackets = 0

    val available: Boolean get() = (codec != null || software != null) && !closed

    /**
     * Queues one 20 ms PCM frame and returns all Opus access units made available by the codec.
     */
    fun encode(pcm: ByteArray): List<ByteArray> {
        if (closed) return emptyList()
        software?.let { return encodeSoftware(it, pcm) }
        val codec = codec ?: return emptyList()
        val inputIndex = try {
            codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        } catch (error: Exception) {
            Log.w(TAG, "Opus microphone input dequeue failed", error)
            return emptyList()
        }
        if (inputIndex >= 0) {
            val input = codec.getInputBuffer(inputIndex)
            if (input == null || pcm.size > input.remaining()) {
                codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
            } else {
                input.clear()
                input.put(pcm)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    pcm.size,
                    presentationTimeUs,
                    0,
                )
                presentationTimeUs += INPUT_DURATION_US
            }
        }
        return drain()
    }

    private fun encodeSoftware(encoder: ConcentusEncoder, pcm: ByteArray): List<ByteArray> {
        // 16-bit little-endian PCM; a short frame is padded with silence.
        val count = minOf(pcm.size / 2, FRAME_SAMPLES)
        for (index in 0 until FRAME_SAMPLES) {
            samples[index] = if (index < count) {
                ((pcm[index * 2].toInt() and 0xff) or (pcm[index * 2 + 1].toInt() shl 8)).toShort()
            } else {
                0
            }
        }
        val length = try {
            encoder.encode(samples, 0, FRAME_SAMPLES, packet, 0, packet.size)
        } catch (error: Exception) {
            Log.w(TAG, "Opus microphone software encode failed", error)
            return emptyList()
        }
        if (length <= 0) return emptyList()
        outputPackets++
        if (outputPackets <= FIRST_PACKET_LOG_COUNT) {
            Log.i(TAG, "Opus microphone software packet=$outputPackets bytes=$length")
        }
        return listOf(packet.copyOf(length))
    }

    private fun drain(): List<ByteArray> {
        val codec = codec ?: return emptyList()
        val output = ArrayList<ByteArray>()
        while (!closed) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, 0)
            } catch (error: Exception) {
                Log.w(TAG, "Opus microphone output dequeue failed", error)
                return output
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return output
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codec.releaseOutputBuffer(index, false)
                        continue
                    }
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && bufferInfo.size > 0) {
                        val bytes = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(bytes)
                        output.add(bytes)
                        outputPackets++
                        if (outputPackets <= FIRST_PACKET_LOG_COUNT) {
                            Log.i(
                                TAG,
                                "Opus microphone packet=$outputPackets bytes=${bytes.size} " +
                                    "head=${bytes.copyOf(minOf(bytes.size, 16)).toHexString()}",
                            )
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        return output
    }

    override fun close() {
        if (closed) return
        closed = true
        val codec = codec ?: return
        try {
            codec.stop()
        } catch (_: Exception) {
            // Best effort.
        }
        try {
            codec.release()
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val INPUT_TIMEOUT_US = 10_000L
        const val INPUT_DURATION_US = 20_000L
        const val MAX_INPUT_BYTES = 4_096
        const val FIRST_PACKET_LOG_COUNT = 3
        const val FRAME_SAMPLES = SAMPLE_RATE / 50
        const val MAX_PACKET_BYTES = 1_275
        // Low effort keeps each 20 ms frame well inside real time on the MT2712; at this
        // bitrate voice quality is still good. Encode timing is in the microphone stats line.
        const val SOFTWARE_COMPLEXITY = 3
    }
}

/**
 * Runs the software Opus encoder for a few seconds of audio when CarPlay connects, so Java has
 * compiled it before the first call or Siri request. mg4.80 logs: the first half-second of a call
 * averaged 23 ms per 20 ms frame (worst 173 ms), then 5 ms once warmed up, so each call started
 * with a growing microphone delay.
 */
internal object OpusWarmUp {
    @Volatile private var done = false

    fun start() {
        if (done || OpusSupport.encoderAvailable()) return
        done = true
        Thread({
            runCatching {
                val encoder = OpusEncoder(48_000)
                // Calls also decode in software (mg4.84); warm that path up with the same frames.
                val decoder = OpusCallDecoder()
                var sequence = 0
                val frame = ByteArray(960 * 2)
                var seed = 1
                repeat(WARM_FRAMES) {
                    // Quiet noise, so the encoder takes its speech paths rather than silence shortcuts.
                    for (index in frame.indices step 2) {
                        seed = seed * 1103515245 + 12345
                        val sample = (seed shr 16) % 400
                        frame[index] = sample.toByte()
                        frame[index + 1] = (sample shr 8).toByte()
                    }
                    encoder.encode(frame).forEach { packet ->
                        // Skip every tenth sequence number so concealment and FEC warm up too.
                        sequence += if (sequence % 10 == 9) 2 else 1
                        decoder.decode(sequence and 0xffff, packet) {}
                    }
                }
                encoder.close()
            }
        }, "opus-warm-up").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private const val WARM_FRAMES = 150
}

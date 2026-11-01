package com.shilapi.xcertplay.media

import io.github.jaredmdobson.concentus.OpusDecoder as ConcentusDecoder
import io.github.jaredmdobson.concentus.OpusPacketInfo
import java.io.Closeable

/**
 * Decodes the call's Opus stream in software so lost packets can be hidden.
 *
 * The call stream has no control port, so lost packets can't be resent (R4 in the test plan),
 * and the platform decoder just skips them: the car's output runs dry and crackles. mg4.83's
 * one call lost 30-60% of packets for its last 25 s. Here each gap of up to [MAX_CONCEALED_FRAMES]
 * is filled before the next packet plays: the last missing frame from that packet's in-band FEC
 * when the iPhone includes it, the rest with Opus packet-loss concealment. Longer gaps are left
 * as silence rather than stacking up seconds of guessed audio.
 */
internal class OpusCallDecoder(
    private val decoder: FrameDecoder = ConcentusFrameDecoder(),
) : Closeable {
    /** One Opus decode call; [packet] null asks for concealment. Returns samples written. */
    interface FrameDecoder {
        fun decode(packet: ByteArray?, out: ShortArray, frameSamples: Int, fec: Boolean): Int
        fun samplesPerFrame(packet: ByteArray): Int
    }

    private var lastSequence = -1
    private var frameSamples = DEFAULT_FRAME_SAMPLES
    private val samples = ShortArray(MAX_FRAME_SAMPLES)

    var packets = 0; private set
    var fecFrames = 0; private set
    var concealedFrames = 0; private set
    var unfilledGaps = 0; private set
    var lateDropped = 0; private set
    var errors = 0; private set

    /** Decodes one RTP packet's payload, calling [output] with little-endian PCM for each frame. */
    fun decode(sequence: Int, payload: ByteArray, output: (ByteArray) -> Unit) {
        if (lastSequence >= 0) {
            val distance = (sequence - lastSequence) and 0xffff
            if (distance == 0 || distance >= 0x8000) {
                lateDropped++
                return
            }
            val missing = distance - 1
            if (missing in 1..MAX_CONCEALED_FRAMES) {
                repeat(missing) { index ->
                    val fec = index == missing - 1
                    frame(if (fec) payload else null, fec, output)?.let {
                        if (fec) fecFrames++ else concealedFrames++
                    }
                }
            } else if (missing > MAX_CONCEALED_FRAMES) {
                unfilledGaps++
            }
        }
        lastSequence = sequence
        packets++
        runCatching { decoder.samplesPerFrame(payload) }.getOrNull()
            ?.takeIf { it in 1..MAX_FRAME_SAMPLES }
            ?.let { frameSamples = it }
        frame(payload, false, output)
    }

    private fun frame(packet: ByteArray?, fec: Boolean, output: (ByteArray) -> Unit): Int? {
        val count = try {
            decoder.decode(packet, samples, frameSamples, fec)
        } catch (error: Exception) {
            errors++
            return null
        }
        if (count <= 0) return null
        val pcm = ByteArray(count * 2)
        for (index in 0 until count) {
            val value = samples[index].toInt()
            pcm[index * 2] = value.toByte()
            pcm[index * 2 + 1] = (value shr 8).toByte()
        }
        output(pcm)
        return count
    }

    /** One line for the diagnostic log. */
    fun summary(): String =
        "packets=$packets fec=$fecFrames concealed=$concealedFrames unfilledGaps=$unfilledGaps " +
            "late=$lateDropped errors=$errors"

    override fun close() = Unit

    private class ConcentusFrameDecoder : FrameDecoder {
        private val decoder = ConcentusDecoder(SAMPLE_RATE, 1)

        override fun decode(packet: ByteArray?, out: ShortArray, frameSamples: Int, fec: Boolean): Int =
            decoder.decode(packet, 0, packet?.size ?: 0, out, 0, frameSamples, fec)

        override fun samplesPerFrame(packet: ByteArray): Int =
            OpusPacketInfo.getNumSamples(packet, 0, packet.size, SAMPLE_RATE)
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val DEFAULT_FRAME_SAMPLES = SAMPLE_RATE / 50
        /** 120 ms, the longest Opus packet. */
        const val MAX_FRAME_SAMPLES = SAMPLE_RATE * 120 / 1000
        /** About 200 ms at 20 ms frames, the size of the call track's buffer. */
        const val MAX_CONCEALED_FRAMES = 10
    }
}

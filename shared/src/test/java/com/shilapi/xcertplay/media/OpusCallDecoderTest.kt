package com.shilapi.xcertplay.media

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder as ConcentusEncoder
import org.junit.Assert.assertEquals
import org.junit.Test

class OpusCallDecoderTest {
    private class FakeDecoder : OpusCallDecoder.FrameDecoder {
        val calls = mutableListOf<String>()
        override fun decode(packet: ByteArray?, out: ShortArray, frameSamples: Int, fec: Boolean): Int {
            calls += when {
                packet == null -> "plc"
                fec -> "fec${packet[0]}"
                else -> "pkt${packet[0]}"
            }
            return frameSamples
        }
        override fun samplesPerFrame(packet: ByteArray) = 960
    }

    private fun packet(id: Int) = byteArrayOf(id.toByte(), 0, 0, 0)

    @Test
    fun fillsAGapWithConcealmentThenFecFromTheNextPacket() {
        val fake = FakeDecoder()
        val decoder = OpusCallDecoder(fake)
        val frames = mutableListOf<Int>()
        decoder.decode(100, packet(1)) { frames += it.size }
        decoder.decode(104, packet(5)) { frames += it.size }

        assertEquals(listOf("pkt1", "plc", "plc", "fec5", "pkt5"), fake.calls)
        assertEquals(List(5) { 960 * 2 }, frames)
        assertEquals(1, decoder.fecFrames)
        assertEquals(2, decoder.concealedFrames)
    }

    @Test
    fun leavesLongGapsAndDropsLateOrDuplicatePackets() {
        val fake = FakeDecoder()
        val decoder = OpusCallDecoder(fake)
        decoder.decode(65_530, packet(1)) {}
        decoder.decode(65_530, packet(2)) {}
        decoder.decode(65_529, packet(3)) {}
        // Wraps past 65535 with 20 missing: too long to guess.
        decoder.decode(15, packet(4)) {}

        assertEquals(listOf("pkt1", "pkt4"), fake.calls)
        assertEquals(2, decoder.lateDropped)
        assertEquals(1, decoder.unfilledGaps)
    }

    @Test
    fun realOpusStreamKeepsItsLengthThroughLoss() {
        val encoder = ConcentusEncoder(48_000, 1, OpusApplication.OPUS_APPLICATION_VOIP)
        encoder.setUseInbandFEC(true)
        encoder.setPacketLossPercent(20)
        val decoder = OpusCallDecoder()
        val pcm = ShortArray(960)
        val packet = ByteArray(1275)
        var samples = 0
        for (index in 0 until 50) {
            for (n in pcm.indices) pcm[n] = (Math.sin((index * 960 + n) * 0.05) * 8000).toInt().toShort()
            val length = encoder.encode(pcm, 0, 960, packet, 0, packet.size)
            if (index % 5 == 3) continue
            decoder.decode(index, packet.copyOf(length)) { samples += it.size / 2 }
        }

        assertEquals(50 * 960, samples)
        assertEquals(0, decoder.errors)
        assertEquals(10, decoder.fecFrames)
    }
}

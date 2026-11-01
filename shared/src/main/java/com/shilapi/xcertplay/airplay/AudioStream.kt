package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class AudioCodecKind { AAC_LC, OPUS, LPCM }

data class AudioFormat(
    val codec: AudioCodecKind,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val audioType: String = "media",
)

/**
 * Binds the RTP data and RTCP control UDP ports for one CarPlay audio stream.
 *
 * Wire layout follows LIVI `livi_audio_stream`: one RTP packet per datagram, a 12-byte header,
 * ciphertext, a 16-byte tag, then an 8-byte little-endian nonce. The header's last eight bytes
 * (timestamp + SSRC) are the AEAD associated data.
 */
class AudioStream(
    private val key: ByteArray,
    private val streamType: Int = -1,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    interface Listener {
        fun onStarted(firstSample: Int) {}
        fun onRtp(rtp: ByteArray, sample: Int) {}
        fun onPacket(
            wire: ByteArray,
            rtp: ByteArray?,
            sample: Int?,
            error: Throwable?,
        ) {}
    }

    private val closed = AtomicBoolean(false)
    private val receivedPackets = AtomicInteger()
    private val decryptedPackets = AtomicInteger()
    private val authenticationFailures = AtomicInteger()
    private val resendRequestSequence = AtomicInteger()
    private val resendRequests = AtomicInteger()
    private val resendPackets = AtomicInteger()
    private val resendNoPeer = AtomicInteger()
    private val recoveredPackets = AtomicInteger()
    private var lastLossReportNs = 0L
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null
    private var started = false
    @Volatile private var controlPeer: SocketAddress? = null
    private val reorderLock = Any()
    private val pending = LinkedHashMap<Int, DecodedPacket>()
    private var nextSequence: Int? = null
    private var gapStartedNs = 0L
    private var lastResendStart = -1

    private data class DecodedPacket(val sequence: Int, val rtp: ByteArray, val sample: Int)

    fun listen(listener: Listener): Pair<Int, Int> {
        val data = bindAnyPort()
        // Keep short Wi-Fi bursts in the kernel while decrypting or scheduling pauses
        // the receive thread. The platform may cap this request; log the actual size.
        val originalBufferBytes = runCatching { data.receiveBufferSize }.getOrDefault(0)
        if (originalBufferBytes < AUDIO_RECEIVE_BUFFER_BYTES) {
            runCatching { data.receiveBufferSize = AUDIO_RECEIVE_BUFFER_BYTES }
        }
        onDiagnostic("Audio UDP receive buffer type=$streamType original=$originalBufferBytes requested=$AUDIO_RECEIVE_BUFFER_BYTES actual=${runCatching { data.receiveBufferSize }.getOrDefault(0)}")
        val control = bindAnyPort()
        dataSocket = data
        controlSocket = control
        dataThread = Thread({ runData(data, listener) }, "airplay-audio-rx").apply {
            isDaemon = true
            start()
        }
        controlThread = Thread({ runControl(control, listener) }, "airplay-rtcp-rx").apply {
            isDaemon = true
            start()
        }
        return data.localPort to control.localPort
    }

    /** The iPhone's RTCP control address, when SETUP names it; replaced by any packet it sends. */
    fun setControlPeer(peer: SocketAddress) {
        if (controlPeer == null) controlPeer = peer
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dataSocket?.close()
        controlSocket?.close()
        dataThread?.interrupt()
        controlThread?.interrupt()
    }

    private fun runData(socket: DatagramSocket, listener: Listener) {
        // Keep RTP draining ahead of UI, codec and vehicle polling work on the slow MT2712.
        // This cannot recreate radio-lost UDP packets, but prevents scheduler stalls from
        // turning a recoverable Wi-Fi burst into a kernel receive-queue overflow.
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        val stats = StreamReceiveStats("audio type=$streamType", onDiagnostic)
        val buffer = ByteArray(DATAGRAM_BYTES)
        try {
            while (!closed.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    stats.reading()
                    socket.receive(packet)
                } catch (_: Exception) {
                    if (closed.get()) return else continue
                }
                stats.received(
                    size = packet.length,
                    sequence = if (packet.length >= RTP_HEADER_LEN) {
                        ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
                    } else null,
                    timestamp = if (packet.length >= RTP_HEADER_LEN) readU32Be(buffer, 4) else null,
                )
                val wire = packet.data.copyOf(packet.length)
                val sequence = if (wire.size >= RTP_HEADER_LEN) readU16Be(wire, 2) else -1
                val packetNumber = receivedPackets.incrementAndGet()
                if (wire.size < RTP_HEADER_LEN + TAIL_LEN) {
                    if (packetNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType short packet bytes=${wire.size}",
                        )
                    }
                    listener.onPacket(
                        wire,
                        null,
                        null,
                        IOException("audio packet shorter than RTP header plus tail"),
                    )
                    stats.processed()
                    continue
                }

                val aad = wire.copyOfRange(4, RTP_HEADER_LEN)
                val sealedEnd = wire.size - NONCE_LEN
                val sealed = wire.copyOfRange(RTP_HEADER_LEN, sealedEnd)
                val shortNonce = wire.copyOfRange(sealedEnd, wire.size)
                val nonce = ByteArray(12).also { shortNonce.copyInto(it, 4) }
                val sample = readU32Be(wire, 4)

                val payload = try {
                    AirPlayCrypto.chachaOpen(key, nonce, sealed, aad)
                } catch (error: Exception) {
                    val failureNumber = authenticationFailures.incrementAndGet()
                    if (failureNumber == 1) {
                        android.util.Log.w(
                            TAG,
                            "audio stream type=$streamType first decrypt failure " +
                                "wire=${wire.toHexString()}",
                            error,
                        )
                    }
                    listener.onPacket(wire, null, sample, error)
                    stats.processed()
                    continue
                }
                val rtp = wire.copyOf(RTP_HEADER_LEN) + payload
                val decryptedNumber = decryptedPackets.incrementAndGet()
                if (decryptedNumber <= FIRST_PACKET_LOG_COUNT) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType packet=$decryptedNumber sample=$sample " +
                            "wireBytes=${wire.size} payloadBytes=${payload.size} " +
                            "payloadHead=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                    )
                } else if (decryptedNumber % PACKET_LOG_INTERVAL == 0) {
                    android.util.Log.i(
                        TAG,
                        "audio stream type=$streamType decrypted=$decryptedNumber " +
                            "authFailures=${authenticationFailures.get()}",
                    )
                }
                listener.onPacket(wire, rtp, sample, null)
                deliverOrdered(DecodedPacket(sequence, rtp, sample), listener)
                stats.processed()
                reportLossIfDue()
            }
        } finally {
            stats.flush(ended = true)
            reportLossIfDue(force = true)
        }
    }

    private fun runControl(socket: DatagramSocket, listener: Listener) {
        val buffer = ByteArray(DATAGRAM_BYTES)
        while (!closed.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                controlPeer = packet.socketAddress
                // AirPlay retransmit response (PT 0x56) wraps the original encrypted RTP
                // datagram after a four-byte RTCP header.
                if (packet.length > 4 + RTP_HEADER_LEN + TAIL_LEN &&
                    (buffer[1].toInt() and 0x7f) == RETRANSMIT_RESPONSE_TYPE
                ) {
                    decodeRetransmitted(buffer.copyOfRange(4, packet.length), listener)
                }
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun decodeRetransmitted(wire: ByteArray, listener: Listener) {
        val sequence = readU16Be(wire, 2)
        val sealedEnd = wire.size - NONCE_LEN
        val sample = readU32Be(wire, 4)
        val payload = try {
            AirPlayCrypto.chachaOpen(
                key,
                ByteArray(12).also { wire.copyInto(it, 4, sealedEnd, wire.size) },
                wire.copyOfRange(RTP_HEADER_LEN, sealedEnd),
                wire.copyOfRange(4, RTP_HEADER_LEN),
            )
        } catch (_: Exception) {
            return
        }
        val rtp = wire.copyOf(RTP_HEADER_LEN) + payload
        recoveredPackets.incrementAndGet()
        listener.onPacket(wire, rtp, sample, null)
        deliverOrdered(DecodedPacket(sequence, rtp, sample), listener)
    }

    /**
     * Every 5 s while packets are being lost: resend requests, recovered packets and the car's
     * own network counters, so the log shows whether loss is in the air or inside the head unit.
     */
    private fun reportLossIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (lastLossReportNs == 0L) lastLossReportNs = now
        if (!force && now - lastLossReportNs < LOSS_REPORT_NS) return
        lastLossReportNs = now
        val requests = resendRequests.getAndSet(0)
        val noPeer = resendNoPeer.getAndSet(0)
        if (requests == 0 && noPeer == 0 && !force) return
        onDiagnostic(
            "Audio loss type=$streamType resendRequests=$requests resendPackets=${resendPackets.getAndSet(0)} " +
                "noControlPeer=$noPeer recovered=${recoveredPackets.getAndSet(0)} " +
                "authFailures=${authenticationFailures.get()} ${NetCounters.delta()}",
        )
    }

    private fun deliverOrdered(packet: DecodedPacket, listener: Listener) {
        val ready = ArrayList<DecodedPacket>()
        var resend: Pair<Int, Int>? = null
        synchronized(reorderLock) {
            if (nextSequence == null) nextSequence = packet.sequence
            val expected = nextSequence ?: packet.sequence
            val distance = (packet.sequence - expected) and 0xffff
            if (distance < 0x8000) pending.putIfAbsent(packet.sequence, packet)

            while (true) {
                val next = nextSequence ?: break
                val found = pending.remove(next) ?: break
                ready += found
                nextSequence = (next + 1) and 0xffff
                gapStartedNs = 0L
                lastResendStart = -1
            }

            if (pending.isNotEmpty()) {
                val missing = nextSequence ?: return@synchronized
                val nearest = pending.keys.minByOrNull { (it - missing) and 0xffff } ?: missing
                val count = ((nearest - missing) and 0xffff).coerceAtMost(MAX_RESEND_PACKETS)
                if (count > 0 && lastResendStart != missing) {
                    lastResendStart = missing
                    resend = missing to count
                }
                val now = System.nanoTime()
                if (gapStartedNs == 0L) gapStartedNs = now
                if (now - gapStartedNs >= REORDER_WAIT_NS || pending.size >= MAX_PENDING_PACKETS) {
                    nextSequence = nearest
                    gapStartedNs = 0L
                    while (true) {
                        val next = nextSequence ?: break
                        val found = pending.remove(next) ?: break
                        ready += found
                        nextSequence = (next + 1) and 0xffff
                    }
                }
            }
        }
        resend?.let { requestResend(it.first, it.second) }
        ready.forEach { ordered ->
            if (!started) {
                started = true
                listener.onStarted(ordered.sample)
            }
            listener.onRtp(ordered.rtp, ordered.sample)
        }
    }

    private fun requestResend(firstMissing: Int, count: Int) {
        val socket = controlSocket ?: return
        val peer = controlPeer ?: run {
            resendNoPeer.incrementAndGet()
            return
        }
        resendRequests.incrementAndGet()
        resendPackets.addAndGet(count)
        val requestSequence = resendRequestSequence.incrementAndGet() and 0xffff
        val request = byteArrayOf(
            0x80.toByte(), 0xd5.toByte(),
            (requestSequence ushr 8).toByte(), requestSequence.toByte(),
            (firstMissing ushr 8).toByte(), firstMissing.toByte(),
            (count ushr 8).toByte(), count.toByte(),
        )
        runCatching { socket.send(DatagramPacket(request, request.size, peer)) }
    }

    private fun bindAnyPort(): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        return socket
    }

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val DATAGRAM_BYTES = 4_096
        const val AUDIO_RECEIVE_BUFFER_BYTES = 512 * 1024
        const val RTP_HEADER_LEN = 12
        const val TAG_LEN = 16
        const val NONCE_LEN = 8
        const val TAIL_LEN = TAG_LEN + NONCE_LEN
        const val FIRST_PACKET_LOG_COUNT = 3
        const val PACKET_LOG_INTERVAL = 100
        const val RETRANSMIT_RESPONSE_TYPE = 0x56
        const val MAX_RESEND_PACKETS = 128
        const val MAX_PENDING_PACKETS = 32
        const val REORDER_WAIT_NS = 300_000_000L
        const val LOSS_REPORT_NS = 5_000_000_000L
    }
}

/** Maps the phone's negotiated audioFormat bits to a decode/render format. */
object AudioStreamCodec {
    fun fromFormatBits(bits: Long, payloadType: Int, audioType: String = "media"): AudioFormat {
        val isAacLc = (bits and (AAC_LC_44K_STEREO or AAC_LC_48K_STEREO)) != 0L
        val isOpus = (bits and OPUS_MONO) != 0L
        val pcm = PCM_FORMAT[bits]
        return when {
            isOpus -> AudioFormat(AudioCodecKind.OPUS, 48_000, 1, payloadType, audioType)
            isAacLc -> AudioFormat(
                AudioCodecKind.AAC_LC,
                if ((bits and AAC_LC_48K_STEREO) != 0L) 48_000 else 44_100,
                2,
                payloadType,
                audioType,
            )
            pcm != null -> AudioFormat(AudioCodecKind.LPCM, pcm.first, pcm.second, payloadType, audioType)
            else -> AudioFormat(AudioCodecKind.LPCM, 44_100, 2, payloadType, audioType)
        }
    }

    private const val AAC_LC_44K_STEREO = 0x400000L
    private const val AAC_LC_48K_STEREO = 0x800000L
    private const val OPUS_MONO = 0x10000000L or 0x20000000L or 0x40000000L

    private val PCM_FORMAT = mapOf(
        0x4L to (8_000 to 1),
        0x8L to (8_000 to 2),
        0x10L to (16_000 to 1),
        0x20L to (16_000 to 2),
        0x40L to (24_000 to 1),
        0x80L to (24_000 to 2),
        0x100L to (32_000 to 1),
        0x200L to (32_000 to 2),
        0x400L to (44_100 to 1),
        0x800L to (44_100 to 2),
        0x4000L to (48_000 to 1),
        0x8000L to (48_000 to 2),
    )
}

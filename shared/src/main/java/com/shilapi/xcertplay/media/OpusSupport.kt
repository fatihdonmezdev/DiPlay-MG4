package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Whether this head unit can encode Opus for the CarPlay microphone uplink.
 *
 * Android 9 ships an Opus decoder but no Opus encoder (the platform encoder arrived in
 * Android 10). If DiPlay still offers Opus input, the iPhone picks it for calls and the
 * microphone cannot start: an outgoing call over Wi-Fi audio on an MG4 SWI165 showed
 * `Microphone telephony: failed` with `codec=OPUS` (mg4.66).
 */
object OpusSupport {
    @Volatile private var cached: Boolean? = null

    fun encoderAvailable(): Boolean = cached ?: runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true) }
        }
    }.getOrDefault(false).also { cached = it }
}

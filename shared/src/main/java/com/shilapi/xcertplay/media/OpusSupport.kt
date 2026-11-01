package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Whether this head unit has a platform Opus encoder for the CarPlay microphone uplink.
 *
 * Android 9 ships an Opus decoder but no Opus encoder (the platform encoder arrived in
 * Android 10). DiPlay must still offer Opus input: in mg4.67 it stopped doing so on Android 9
 * and the iPhone then treated the car as screen-only, with no CarPlay audio at all. Without a
 * platform encoder, [OpusEncoder] falls back to the pure-Java Concentus encoder.
 */
object OpusSupport {
    @Volatile private var cached: Boolean? = null

    fun encoderAvailable(): Boolean = cached ?: runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true) }
        }
    }.getOrDefault(false).also { cached = it }
}

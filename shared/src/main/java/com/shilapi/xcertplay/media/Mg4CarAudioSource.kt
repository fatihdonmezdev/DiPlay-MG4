package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.os.Bundle
import android.util.Log

/**
 * SAIC car audio source tags, as the stock AllGo CarPlay app sets them on SWI165
 * (`CarPlayAudioManager`): a `key_car_source_type` extra on each track's AudioAttributes tells
 * the car's audio policy which source is playing. 5 is Bluetooth (MediaService), 50 CarPlay
 * media, 52 CarPlay navigation, 53 CarPlay phone. The stock app plays calls as
 * USAGE_VOICE_COMMUNICATION with source 53 and MODE_IN_CALL; DiPlay's untagged voice track was
 * silent (mg4.65).
 *
 * `AudioAttributes.Builder.addBundle` is a hidden system API. DiPlay runs as the system user
 * on MG4, so reflection is allowed; if it is not, the track is built untagged.
 */
internal object Mg4CarAudioSource {
    private const val TAG = "DiPlay-MG4"
    const val KEY = "key_car_source_type"
    const val CARPLAY_PHONE = 53

    private val addBundle by lazy {
        runCatching {
            AudioAttributes.Builder::class.java.getMethod("addBundle", Bundle::class.java)
        }.onFailure { Log.w(TAG, "AudioAttributes.Builder.addBundle unavailable", it) }.getOrNull()
    }

    /** Returns true when the tag was added. */
    fun tag(builder: AudioAttributes.Builder, source: Int): Boolean {
        val method = addBundle ?: return false
        return runCatching {
            method.invoke(builder, Bundle().apply { putInt(KEY, source) })
            true
        }.onFailure { Log.w(TAG, "car audio source $source not set", it) }.getOrDefault(false)
    }
}

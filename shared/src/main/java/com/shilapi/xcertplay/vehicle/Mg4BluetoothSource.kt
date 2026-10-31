package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.util.Log
import com.evsuite.hardware.saic.SaicAidl
import com.evsuite.hardware.saic.SaicService

/**
 * Moves the car's audio source to Bluetooth Music, as the SAIC launcher's music card does.
 *
 * Upstream called `SaicMediaPlayer.connectBluetooth` / `switchToBluetooth`, which were never
 * published in the MG4ABRP submodule, so the public tree did not compile. This is the same
 * sequence against the SAIC media service: `requestBtAudioFocus` moves the source, `play`
 * resumes the transport. Transaction codes come from `IBtMusicBinderInterface` as used by the
 * SWI68 launcher (same media SDK as SWI165).
 *
 * On A9 firmware (SWI69, SWI131, SWI132) the SAIC media service does not exist, the bind is
 * refused once and [switchToBluetooth] returns false, leaving the source unchanged.
 */
object Mg4BluetoothSource {
    private const val TAG = "DiPlay-MG4"
    private const val MEDIA_PACKAGE = "com.saicmotor.service.media"
    private const val MEDIA_CLASS = "com.saicmotor.service.media.MediaService"
    private const val ACTION_BT = "com.saicmotor.service.media.BT_MUSIC_ACTION"
    private const val DESC_BT = "com.saicmotor.sdk.media.IBtMusicBinderInterface"
    private const val TX_BT_PLAY = 2
    private const val TX_BT_REQUEST_FOCUS = 15

    private val bluetooth = SaicService.byActionAndComponent(MEDIA_PACKAGE, ACTION_BT, MEDIA_CLASS, "media-bt-source")

    /** Starts the asynchronous bind. Idempotent. */
    fun connect(context: Context) = bluetooth.connect(context)

    /** @return true once the media service accepted both the focus request and play. */
    fun switchToBluetooth(): Boolean {
        val binder = bluetooth.binder() ?: return false
        val focused = SaicAidl.callVoid(binder, DESC_BT, TX_BT_REQUEST_FOCUS)
        val playing = focused && SaicAidl.callVoid(binder, DESC_BT, TX_BT_PLAY)
        Log.i(TAG, "BT source switch focus=$focused play=$playing")
        return playing
    }
}

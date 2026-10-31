package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.evsuite.hardware.saic.SaicAidl

/**
 * Moves the car's audio source to Bluetooth Music, as the SAIC launcher's music card does.
 *
 * Upstream called `SaicMediaPlayer.connectBluetooth` / `switchToBluetooth`, which were never
 * published in the MG4ABRP submodule, so the public tree did not compile. This is the same
 * sequence against the SAIC media service: `requestBtAudioFocus` moves the source, `play`
 * resumes the transport. Transaction codes come from `IBtMusicBinderInterface` as used by the
 * SWI68 launcher (same media SDK as SWI165).
 *
 * The bind is done here rather than through EVHardware's `SaicService` so its outcome can be
 * reported in DiPlay's own diagnostic log: on SWI165 the first build waited three seconds and
 * only saw "never answered", with the reason lost in logcat.
 *
 * On A9 firmware (SWI69, SWI131, SWI132) the SAIC media service does not exist, the bind is
 * refused and [switchToBluetooth] reports that, leaving the source unchanged.
 */
object Mg4BluetoothSource {
    private const val TAG = "DiPlay-MG4"
    private const val MEDIA_PACKAGE = "com.saicmotor.service.media"
    private const val MEDIA_CLASS = "com.saicmotor.service.media.MediaService"
    private const val ACTION_BT = "com.saicmotor.service.media.BT_MUSIC_ACTION"
    private const val DESC_BT = "com.saicmotor.sdk.media.IBtMusicBinderInterface"
    private const val TX_BT_PLAY = 2
    private const val TX_BT_REQUEST_FOCUS = 15

    /** Result of one switch attempt, worded for the diagnostic log. */
    enum class Result(val logText: String) {
        SWITCHED("focus and play accepted"),
        BIND_REFUSED("bindService refused (service missing or not allowed)"),
        NOT_CONNECTED("bind requested, service not connected yet"),
        FOCUS_FAILED("requestBtAudioFocus (15) failed"),
        PLAY_FAILED("focus accepted, play (2) failed"),
    }

    @Volatile private var binder: IBinder? = null
    @Volatile private var bindRequested = false
    @Volatile private var bindAccepted = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            Log.i(TAG, "BT music service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            bindRequested = false
            Log.w(TAG, "BT music service disconnected")
        }
    }

    /**
     * Starts the asynchronous bind. Idempotent; rebinds after the service died.
     * @return a short status for the diagnostic log.
     */
    @Synchronized
    fun connect(context: Context): String {
        if (binder?.isBinderAlive == true) return "already connected"
        if (bindRequested && bindAccepted) return "bind pending"
        val intent = Intent(ACTION_BT).setClassName(MEDIA_PACKAGE, MEDIA_CLASS)
        bindAccepted = runCatching {
            context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure { Log.w(TAG, "BT music bindService threw", it) }.getOrDefault(false)
        bindRequested = true
        return if (bindAccepted) "bind requested" else "bindService refused"
    }

    /** One attempt to move the car to Bluetooth Music. */
    fun switchToBluetooth(): Result {
        val service = binder?.takeIf { it.isBinderAlive }
            ?: return if (bindRequested && !bindAccepted) Result.BIND_REFUSED else Result.NOT_CONNECTED
        if (!SaicAidl.callVoid(service, DESC_BT, TX_BT_REQUEST_FOCUS)) return Result.FOCUS_FAILED
        if (!SaicAidl.callVoid(service, DESC_BT, TX_BT_PLAY)) return Result.PLAY_FAILED
        return Result.SWITCHED
    }
}

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
    private const val ACTION_STATUS = "com.saicmotor.service.media.PLAY_STATUS_ACTION"
    private const val DESC_STATUS = "com.saicmotor.sdk.media.IPlayStatusBinderInterface"
    private const val TX_CURRENT_SOURCE = 0x9
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

    /** Read-only: which source owns the car's audio. Diagnostics only. */
    @Volatile private var statusBinder: IBinder? = null
    @Volatile private var statusBindRequested = false

    private val statusConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            statusBinder = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            statusBinder = null
            statusBindRequested = false
        }
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
        if (!statusBindRequested) {
            statusBindRequested = runCatching {
                context.applicationContext.bindService(
                    Intent(ACTION_STATUS).setClassName(MEDIA_PACKAGE, MEDIA_CLASS),
                    statusConnection,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
        }
        if (binder?.isBinderAlive == true) return "already connected"
        if (bindRequested && bindAccepted) return "bind pending"
        val intent = Intent(ACTION_BT).setClassName(MEDIA_PACKAGE, MEDIA_CLASS)
        bindAccepted = runCatching {
            context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure { Log.w(TAG, "BT music bindService threw", it) }.getOrDefault(false)
        bindRequested = true
        return if (bindAccepted) "bind requested" else "bindService refused"
    }

    /**
     * The car's current audio source for the diagnostic log, e.g. `5 (bluetooth)`, or
     * `unknown` when the status service has not answered. Codes as in EVHardware's
     * SaicMediaPlayer: 1-4 radio/DAB, 5 bluetooth, 6 online, 7 usb, 0x32 carplay, 0x46 android-auto.
     */
    fun currentSourceDescription(): String {
        val code = SaicAidl.callInt(statusBinder?.takeIf { it.isBinderAlive }, DESC_STATUS, TX_CURRENT_SOURCE)
            ?: return "unknown"
        val name = when (code) {
            in 1..4 -> "radio/DAB"
            5 -> "bluetooth"
            6 -> "online"
            7 -> "usb"
            0x12 -> "usb-video"
            0x32 -> "carplay"
            0x46 -> "android-auto"
            else -> "other"
        }
        return "$code ($name)"
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

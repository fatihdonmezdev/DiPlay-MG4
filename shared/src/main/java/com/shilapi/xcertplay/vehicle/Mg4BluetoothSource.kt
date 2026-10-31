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
 * published in the MG4ABRP submodule, so the public tree did not compile.
 *
 * Read from the SWI165 MediaService APK (`MediaService_eh32_aus_local_P`):
 * `IBtMusicBinderInterface` has transactions 1-14 only, so the SWI68 launcher's
 * `requestBtAudioFocus` (15) does not exist here and always failed. `play` (2) is the switch:
 * `BtMusicPlayerBinder.play` -> `BtMusicMediaPlayer.play` posts message 2 to
 * `A2dpAudioFocusHandler`, which requests audio focus with `key_car_source_type=5` (the car's
 * Bluetooth source) and starts A2DP rendering, then tells the phone to play. It returns false
 * when AVRCP is not connected, Android Auto is connected, or Bluetooth has transiently lost focus.
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
    private const val TX_CURRENT_PLAYER = 0x1
    private const val TX_BT_PLAY = 2
    private const val TX_BT_PLAY_STATE = 9
    private const val TX_BT_STATE = 11

    /** Result of one switch attempt, worded for the diagnostic log. */
    enum class Result(val logText: String) {
        SWITCHED("play (2) accepted"),
        BIND_REFUSED("bindService refused (service missing or not allowed)"),
        NOT_CONNECTED("bind requested, service not connected yet"),
        PLAY_REJECTED("play (2) returned false (AVRCP down, Android Auto connected, or Bluetooth focus lost)"),
        PLAY_FAILED("play (2) call failed"),
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
     * The car's current audio source and player for the diagnostic log, e.g.
     * `source=5 (bluetooth) player=4 (bluetooth)`, or `unknown` parts when the status service
     * has not answered.
     *
     * Source codes as measured by MG4Control on the car and used by the SWI68 launcher
     * (same media SDK as SWI165): 0 none, 1-4 radio/DAB, 5 bluetooth, 6 usb, 14 online,
     * 0x12 usb-video, 50 carplay, 70 android-auto. EVHardware's own table has 6 and 7 the
     * other way round and no 14, so it is not used here. Player codes from the SWI68
     * launcher: 2 usb, 3 online, 4 bluetooth.
     */
    fun currentSourceDescription(): String {
        val status = statusBinder?.takeIf { it.isBinderAlive }
        val source = SaicAidl.callInt(status, DESC_STATUS, TX_CURRENT_SOURCE)?.let { code ->
            val name = when (code) {
                0 -> "none"
                in 1..4 -> "radio/DAB"
                5 -> "bluetooth"
                6 -> "usb"
                14 -> "online"
                0x12 -> "usb-video"
                50 -> "carplay"
                70 -> "android-auto"
                else -> "other"
            }
            "$code ($name)"
        } ?: "unknown"
        val player = SaicAidl.callInt(status, DESC_STATUS, TX_CURRENT_PLAYER)?.let { code ->
            val name = when (code) {
                2 -> "usb"
                3 -> "online"
                4 -> "bluetooth"
                else -> "other"
            }
            "$code ($name)"
        } ?: "unknown"
        return "source=$source player=$player"
    }

    /** One attempt to move the car to Bluetooth Music. */
    fun switchToBluetooth(): Result {
        val service = binder?.takeIf { it.isBinderAlive }
            ?: return if (bindRequested && !bindAccepted) Result.BIND_REFUSED else Result.NOT_CONNECTED
        return when (SaicAidl.callBoolean(service, DESC_BT, TX_BT_PLAY)) {
            true -> Result.SWITCHED
            false -> Result.PLAY_REJECTED
            null -> Result.PLAY_FAILED
        }
    }

    /** Bluetooth music state from the media service, e.g. `bt=2 play=3`, for the diagnostic log. */
    fun bluetoothStateDescription(): String {
        val service = binder?.takeIf { it.isBinderAlive } ?: return "bt service not connected"
        val bt = SaicAidl.callInt(service, DESC_BT, TX_BT_STATE)?.toString() ?: "?"
        val play = SaicAidl.callInt(service, DESC_BT, TX_BT_PLAY_STATE)?.toString() ?: "?"
        return "bt state=$bt play state=$play"
    }
}

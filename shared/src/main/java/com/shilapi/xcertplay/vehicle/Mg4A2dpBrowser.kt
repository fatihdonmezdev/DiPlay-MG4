package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Fallback for starting the car's Bluetooth Music: connect to the head unit's A2DP sink
 * MediaBrowserService and press play, the way MG4Dash drives the car's Bluetooth player.
 *
 * Used only when the SAIC media service path ([Mg4BluetoothSource]) fails. The browser is kept
 * connected afterwards: MG4Dash found that disconnecting from the car's Bluetooth player could
 * stop the music.
 *
 * The SWI165 Bluetooth stack is nFore-based (`Bluetooth_nfore_eh32`), so the AOSP service
 * below may not exist; [play] reports that instead of failing silently.
 */
object Mg4A2dpBrowser {
    private val COMPONENT = ComponentName(
        "com.android.bluetooth",
        "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService",
    )
    private const val CONNECT_TIMEOUT_SECONDS = 5L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var browser: MediaBrowser? = null
    @Volatile private var controller: MediaController? = null

    /**
     * Blocks up to five seconds; call from a background thread.
     * @return a short outcome for the diagnostic log.
     */
    fun play(context: Context): String {
        val app = context.applicationContext
        val present = runCatching { app.packageManager.getServiceInfo(COMPONENT, 0) }.isSuccess
        if (!present) return "A2DP media browser not present on this firmware"

        controller?.let { return press(it, "reused") }

        val latch = CountDownLatch(1)
        var failure: String? = null
        main.post {
            var created: MediaBrowser? = null
            created = MediaBrowser(app, COMPONENT, object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val connected = created
                    if (connected != null) {
                        controller = runCatching { MediaController(app, connected.sessionToken) }
                            .onFailure { failure = "controller failed: ${it.javaClass.simpleName}" }
                            .getOrNull()
                    }
                    latch.countDown()
                }

                override fun onConnectionFailed() {
                    failure = "connection refused"
                    latch.countDown()
                }

                override fun onConnectionSuspended() {
                    controller = null
                }
            }, null)
            browser = created
            runCatching { created?.connect() }.onFailure {
                failure = "connect threw ${it.javaClass.simpleName}"
                latch.countDown()
            }
        }
        if (!latch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return "A2DP media browser did not connect in ${CONNECT_TIMEOUT_SECONDS}s"
        failure?.let { return "A2DP media browser $it" }
        val connected = controller ?: return "A2DP media browser connected without a controller"
        return press(connected, "connected")
    }

    private fun press(controller: MediaController, how: String): String = runCatching {
        val before = controller.playbackState?.state
        controller.transportControls.play()
        "A2DP media browser $how; play sent (state before=${stateName(before)})"
    }.getOrElse { "A2DP media browser play failed: ${it.javaClass.simpleName}" }

    private fun stateName(state: Int?): String = when (state) {
        null -> "unknown"
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_NONE -> "none"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_CONNECTING -> "connecting"
        PlaybackState.STATE_ERROR -> "error"
        else -> state.toString()
    }
}

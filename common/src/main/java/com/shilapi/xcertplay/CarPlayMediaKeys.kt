package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * Steering-wheel and other hardware media buttons for CarPlay.
 *
 * Android delivers media keys to a media session; BYD picks the session of the audio-focus
 * owner. Once CarPlay plays music, DiPlay holds audio focus and an active session until the
 * CarPlay session ends, so play also works after a pause. Keys go to the iPhone as CarPlay media
 * HID presses ([CarPlayMediaButton]).
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"
    private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

    private val mainHandler = Handler(Looper.getMainLooper())
    private var controller: CarPlayController? = null
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private var appContext: Context? = null
    private var hardKeyReceiver: BroadcastReceiver? = null
    private var report: (String) -> Unit = {}

    /**
     * MG4 SWI165 steering-wheel keys do not arrive as Android media keys. SystemUI broadcasts
     * them and the car's MediaService (`HardKeyMonitor`) drives the current car source. Read from
     * MediaService_eh32_aus_local_P: KEY_CODE 1 volume up, 2 volume down, 3 mute, 4 previous,
     * 5 next; DOWN and LONG_PRESS booleans. A short press acts on release (DOWN=false).
     */
    private const val ACTION_HARD_KEY_EVENT = "com.android.systemui.ACTION_HARD_KEY_EVENT"
    private const val HARD_KEY_PREVIOUS = 4
    private const val HARD_KEY_NEXT = 5

    /**
     * @param wifiAudio CarPlay music plays through DiPlay (Wi-Fi audio). The car's Bluetooth
     * player no longer receives the wheel keys, so DiPlay listens for the MG4 hard-key broadcast.
     */
    @Synchronized
    fun attach(
        context: Context,
        next: CarPlayController,
        wifiAudio: Boolean = false,
        log: (String) -> Unit = {},
    ) {
        if (controller !== next) releaseLocked()
        appContext = context.applicationContext
        controller = next
        report = log
        if (wifiAudio && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) registerHardKeysLocked()
        // AUTUS/SAIC's Android 9 Bluetooth stack treats even an active MediaSession (without an
        // audio-focus request) as the current AVRCP player.  Registering it while the same phone
        // is the wireless CarPlay source makes the factory stack send pause back to the iPhone.
        // Android 9 therefore keeps direct CarPlay controls but does not publish a media session.
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            next.playbackListener = ::onIphonePlaying
        } else {
            next.playbackListener = null
            Log.i(TAG, "Android 9 MG4: MediaSession and audio focus disabled")
        }
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.playbackListener = null
        controller = null
        releaseLocked()
    }

    /** Called when CarPlay music starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) return
        mainHandler.post { synchronized(this) { updateLocked(active) } }
    }

    /** The iPhone started or stopped playing; may run on any thread. */
    fun onIphonePlaying(playing: Boolean) {
        if (playing) mainHandler.post { synchronized(this) { regainFocusLocked() } }
    }

    // Another car app (its own Spotify, the radio) took audio focus and with it the steering-wheel
    // keys. When CarPlay starts playing again it becomes the car's media source again, as any player
    // would; only the start counts, so a car source picked while the iPhone plays on is not undone.
    private fun regainFocusLocked() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) return
        val request = focusRequest ?: return
        if (focusHeld) return
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return
        focusHeld = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "audio focus regained=$focusHeld")
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        if (active && session == null) start(context) else if (active) regainFocusLocked()
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(if (active) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
    }

    private fun start(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener({ change ->
                    Log.i(TAG, "audio focus change=$change")
                    if (change == AudioManager.AUDIOFOCUS_LOSS) synchronized(this) { focusHeld = false }
                }, mainHandler)
                .build()
            focusHeld = audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            focusRequest = request
        }
        session = MediaSession(context, "DiPlay CarPlay").apply {
            setCallback(callback, mainHandler)
            isActive = true
        }
        Log.i(TAG, "media keys active focusGranted=$focusHeld android9Bypass=${Build.VERSION.SDK_INT <= Build.VERSION_CODES.P}")
    }

    private fun registerHardKeysLocked() {
        if (hardKeyReceiver != null) return
        val context = appContext ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onHardKey(intent)
        }
        val registered = runCatching { context.registerReceiver(receiver, IntentFilter(ACTION_HARD_KEY_EVENT)) }
            .onFailure { report("Wheel keys: hard-key receiver failed: ${it.javaClass.simpleName}") }
            .isSuccess
        if (registered) {
            hardKeyReceiver = receiver
            report("Wheel keys: listening for MG4 hard-key broadcasts")
        }
    }

    private fun onHardKey(intent: Intent) {
        val keyCode = intent.getIntExtra("KEY_CODE", -1)
        val down = intent.getBooleanExtra("DOWN", false)
        val longPress = intent.getBooleanExtra("LONG_PRESS", false)
        val index = when (keyCode) {
            HARD_KEY_NEXT -> CarPlayMediaButton.NEXT
            HARD_KEY_PREVIOUS -> CarPlayMediaButton.PREVIOUS
            else -> null
        }
        // Act once per short press, on release, as the car's own HardKeyMonitor does.
        val act = index != null && !down && !longPress
        report("Wheel key: code=$keyCode down=$down long=$longPress${if (act) " -> CarPlay" else ""}")
        if (act) send(index!!, "MG4 wheel $keyCode")
    }

    private fun releaseLocked() {
        hardKeyReceiver?.let { receiver -> runCatching { appContext?.unregisterReceiver(receiver) } }
        hardKeyReceiver = null
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        focusRequest?.let { request -> appContext?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
        focusRequest = null
        focusHeld = false
    }

    private fun send(index: Int, source: String) {
        // While the car's video player is on screen the wheel drives it: a CarPlay play/pause would
        // make the iPhone end the video session.
        if (CarPlayVideo.onMediaKey(index)) {
            Log.i(TAG, "media key $source -> car video player $index")
            return
        }
        val sent = synchronized(this) { controller }?.sendMediaButton(index) ?: false
        Log.i(TAG, "media key $source -> CarPlay $index sent=$sent")
    }

    private val callback = CarPlayMediaCallback(::send)
}

/**
 * Media-session input → CarPlay presses. Hardware keys arrive as button events and keep the toggle;
 * media controllers (not hardware keys) call [onPlay] and [onPause] with an explicit intent.
 */
internal class CarPlayMediaCallback(private val send: (index: Int, source: String) -> Unit) : MediaSession.Callback() {
    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        val index = CarPlayMediaButton.forKeyCode(event.keyCode) ?: return super.onMediaButtonEvent(mediaButtonIntent)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            send(index, KeyEvent.keyCodeToString(event.keyCode))
        }
        return true
    }

    override fun onPlay() = send(CarPlayMediaButton.PLAY, "play")
    override fun onPause() = send(CarPlayMediaButton.PAUSE, "pause")
    override fun onSkipToNext() = send(CarPlayMediaButton.NEXT, "next")
    override fun onSkipToPrevious() = send(CarPlayMediaButton.PREVIOUS, "previous")
}

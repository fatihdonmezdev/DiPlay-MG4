package com.shilapi.xcertplay.media

/** How CarPlay music reaches the car's speakers. */
enum class AudioMode {
    /** Screen-only CarPlay; music plays over the car's own Bluetooth Music player. */
    HYBRID_BLUETOOTH,

    /** The iPhone sends music over Wi-Fi and DiPlay plays it. Experimental. */
    CARPLAY_WIFI,
}

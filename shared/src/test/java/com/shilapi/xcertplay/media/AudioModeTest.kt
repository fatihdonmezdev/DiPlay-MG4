package com.shilapi.xcertplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioModeTest {
    @Test fun hybridModeDropsOnlyMediaStreams() {
        assertTrue(AndroidMediaSink.shouldDropMedia(AudioMode.HYBRID_BLUETOOTH, "media"))
        assertFalse(AndroidMediaSink.shouldDropMedia(AudioMode.HYBRID_BLUETOOTH, "alert"))
        assertFalse(AndroidMediaSink.shouldDropMedia(AudioMode.HYBRID_BLUETOOTH, "telephony"))
        assertFalse(AndroidMediaSink.shouldDropMedia(AudioMode.HYBRID_BLUETOOTH, "speechRecognition"))
        assertFalse(AndroidMediaSink.shouldDropMedia(AudioMode.HYBRID_BLUETOOTH, null))
    }

    @Test fun wifiModePlaysMediaStreams() {
        assertFalse(AndroidMediaSink.shouldDropMedia(AudioMode.CARPLAY_WIFI, "media"))
        assertFalse(AndroidMediaSink.shouldDropMedia(AudioMode.CARPLAY_WIFI, "alert"))
    }
}

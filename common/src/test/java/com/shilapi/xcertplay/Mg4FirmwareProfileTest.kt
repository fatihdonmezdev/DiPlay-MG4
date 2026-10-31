package com.shilapi.xcertplay

import com.shilapi.xcertplay.Mg4FirmwareProfile.Generation
import com.shilapi.xcertplay.media.AudioMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mg4FirmwareProfileTest {
    @Test
    fun swi165UsesHmiLauncherAndOverlayVideoWithoutGuessingTheCard() {
        val profile = Mg4FirmwareProfile.forFirmware("SWI165-29176-1100R11")
        assertEquals(Generation.SWI165, profile.generation)
        assertEquals("com.saicmotor.hmi.launcher", profile.stockLauncherPackage)
        assertNull(profile.launcherCarPlayCard)
        assertTrue(profile.preferSurfaceVideo)
        assertFalse(Mg4LauncherOverlaySupport.isSupported(profile))
    }

    @Test
    fun swi69KeepsMeasuredLauncherCardAndTextureVideo() {
        val profile = Mg4FirmwareProfile.forFirmware("SWI69-12345-1000R01")
        assertEquals(Generation.SWI69, profile.generation)
        assertEquals("com.saicmotor.launcher", profile.stockLauncherPackage)
        assertNotNull(profile.launcherCarPlayCard)
        assertFalse(profile.preferSurfaceVideo)
        assertTrue(Mg4LauncherOverlaySupport.isSupported(profile))
    }

    @Test
    fun unknownOrMissingFirmwareKeepsDefaults() {
        for (firmware in listOf(null, "", "  ", "SWI68-1", "BYD-DiLink")) {
            val profile = Mg4FirmwareProfile.forFirmware(firmware)
            assertEquals(Generation.OTHER, profile.generation)
            assertFalse(profile.preferSurfaceVideo)
            assertFalse(Mg4LauncherOverlaySupport.isSupported(profile))
        }
    }

    @Test
    fun everyFirmwareDefaultsToHybridBluetoothAudio() {
        for (firmware in listOf("SWI69-12345-1000R01", "SWI165-29176-1100R11", null, "BYD-DiLink")) {
            assertEquals(AudioMode.HYBRID_BLUETOOTH, Mg4FirmwareProfile.forFirmware(firmware).defaultAudioMode)
        }
    }
}

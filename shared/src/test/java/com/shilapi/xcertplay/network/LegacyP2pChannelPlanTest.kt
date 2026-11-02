package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyP2pChannelPlanTest {
    @Test fun automaticTriesFiveGhzThenTwoGhzThenFrameworkChoice() {
        assertEquals(listOf(36, 149, 6, 1, 11, 0), LegacyP2pChannelPlan.plan(ManualHotspotBand.AUTO, null, true))
    }

    @Test fun automaticSkipsFiveGhzWhenTheRadioCannot() {
        assertEquals(listOf(6, 1, 11, 0), LegacyP2pChannelPlan.plan(ManualHotspotBand.AUTO, null, false))
    }

    @Test fun twoGhzOnlyNeverAsksForFiveGhz() {
        assertEquals(listOf(6, 1, 11, 0), LegacyP2pChannelPlan.plan(ManualHotspotBand.GHZ_2_4, null, true))
    }

    @Test fun connectedStationChannelGoesFirstWhenItSuitsTheBand() {
        assertEquals(listOf(11, 36, 149, 6, 1, 0), LegacyP2pChannelPlan.plan(ManualHotspotBand.AUTO, 2462, true))
        assertEquals(listOf(1, 6, 11, 0), LegacyP2pChannelPlan.plan(ManualHotspotBand.GHZ_2_4, 2412, true))
        assertEquals(listOf(6, 1, 11, 0), LegacyP2pChannelPlan.plan(ManualHotspotBand.GHZ_2_4, 5180, true))
    }

    @Test fun aFiveGhzCarHotspotOverridesTheRadiosNo() {
        // mg4.86 MG4: is5GHzBandSupported=false, yet the car hotspot runs on channel 36.
        assertTrue(LegacyP2pChannelPlan.fiveGhzCapable(false, 1))
        assertFalse(LegacyP2pChannelPlan.fiveGhzCapable(false, 0))
        assertFalse(LegacyP2pChannelPlan.fiveGhzCapable(false, null))
        assertTrue(LegacyP2pChannelPlan.fiveGhzCapable(null, null))
        assertTrue(LegacyP2pChannelPlan.fiveGhzCapable(true, 0))
    }

    @Test fun stateAndFailureCodesAreLabelled() {
        assertEquals("disabled", LegacyP2pChannelPlan.p2pStateLabel(1))
        assertEquals("enabled", LegacyP2pChannelPlan.p2pStateLabel(2))
        assertEquals("unknown", LegacyP2pChannelPlan.p2pStateLabel(null))
        assertEquals("busy", LegacyP2pChannelPlan.failureLabel(2))
        assertEquals("5GHz", LegacyP2pChannelPlan.apBandLabel(1))
    }

    @Test fun wifiDirectInterfacesCanBeRead() {
        assertTrue(LegacyHotspotRadio.isReadableInterface("p2p-wlan0-0"))
        assertTrue(LegacyHotspotRadio.isReadableInterface("p2p0"))
        assertTrue(LegacyHotspotRadio.isReadableInterface("ap0"))
        assertFalse(LegacyHotspotRadio.isReadableInterface("p2p-wlan0-0;rm"))
        assertFalse(LegacyHotspotRadio.isReadableInterface("eth0"))
    }
}

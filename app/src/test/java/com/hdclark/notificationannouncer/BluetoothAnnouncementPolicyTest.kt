package com.hdclark.notificationannouncer

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAnnouncementPolicyTest {
    private val a2dp = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    private val le = AudioDeviceInfo.TYPE_BLE_HEADSET
    private val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

    @Test fun manualModeDoesNotConsultBluetooth() {
        assertTrue(BluetoothAnnouncementPolicy.enabled(true, false) { error("Unexpected query") })
        assertFalse(BluetoothAnnouncementPolicy.enabled(false, false) { error("Unexpected query") })
    }

    @Test fun automaticModeOverridesButDoesNotMutateManualPreference() {
        for (manual in listOf(false, true)) {
            assertTrue(BluetoothAnnouncementPolicy.enabled(manual, true) { true })
            assertFalse(BluetoothAnnouncementPolicy.enabled(manual, true) { false })
            assertTrue(BluetoothAnnouncementPolicy.enabled(manual, false) { false } == manual)
        }
    }

    @Test fun legacyConnectionFallbackRequiresBluetoothMediaOutput() {
        assertFalse(BluetoothAnnouncementPolicy.available(emptyList(), null))
        assertFalse(BluetoothAnnouncementPolicy.available(listOf(speaker, AudioDeviceInfo.TYPE_BLUETOOTH_SCO), null))
        assertFalse(BluetoothAnnouncementPolicy.available(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES), null))
        assertTrue(BluetoothAnnouncementPolicy.available(listOf(speaker, a2dp), null))
        assertTrue(BluetoothAnnouncementPolicy.available(listOf(le), null))
    }

    @Test fun modernRouteMustBeKnownAndExclusivelyBluetooth() {
        val connected = listOf(speaker, a2dp, le)
        assertTrue(BluetoothAnnouncementPolicy.available(connected, listOf(a2dp)))
        assertTrue(BluetoothAnnouncementPolicy.available(connected, listOf(a2dp, le)))
        assertFalse(BluetoothAnnouncementPolicy.available(connected, emptyList()))
        assertFalse(BluetoothAnnouncementPolicy.available(connected, listOf(speaker)))
        assertFalse(BluetoothAnnouncementPolicy.available(connected, listOf(a2dp, speaker)))
        assertFalse(BluetoothAnnouncementPolicy.available(listOf(speaker), listOf(a2dp)))
        assertFalse(BluetoothAnnouncementPolicy.available(listOf(a2dp), listOf(le)))
    }
}

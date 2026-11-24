package com.hdclark.notificationannouncer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Pure policy: manual mode must not even query the audio system. */
internal object BluetoothAnnouncementPolicy {
    fun enabled(manualEnabled: Boolean, automatic: Boolean, bluetoothAvailable: () -> Boolean): Boolean =
        if (automatic) bluetoothAvailable() else manualEnabled

    // SCO is primarily call audio; keyboards, watches and other non-audio devices
    // are not outputs. A2DP cannot distinguish headphones from external speakers.
    fun isBluetoothMediaOutput(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
        else -> false
    }

    fun available(connected: Collection<Int>, routed: Collection<Int>?): Boolean {
        if (connected.none(::isBluetoothMediaOutput)) return false
        // Null means the platform predates the public routing query (API 33).
        // A known empty, mixed or non-Bluetooth route must fail closed.
        return routed == null || (routed.isNotEmpty() &&
            routed.all { isBluetoothMediaOutput(it) && it in connected })
    }
}

internal object BluetoothAudioAvailability {
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun isAvailable(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        val connected = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getAudioDevicesForAttributes(speechAttributes).map { it.type }
        } else null
        BluetoothAnnouncementPolicy.available(connected, routed)
    }.getOrDefault(false)
}

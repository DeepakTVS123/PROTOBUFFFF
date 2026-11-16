package com.example.protobuff.data.model

import android.bluetooth.BluetoothDevice

/**
 * Data class representing a discovered Keyfob BLE device.
 */
data class FobDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice
)

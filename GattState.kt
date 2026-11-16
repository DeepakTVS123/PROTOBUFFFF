package com.example.protobuff.data.model

/**
 * Sealed class representing BLE GATT Connection states.
 */
sealed class GattState {
    object Disconnected : GattState()
    object Connecting : GattState()
    object Connected : GattState()
    object ServicesDiscovered : GattState()
    data class Error(val message: String) : GattState()
}

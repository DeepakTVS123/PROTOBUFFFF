package com.example.protobuff.data.model

/**
 * Result of BLE GATT write operations.
 */
sealed class BleWriteResult {
    object Success : BleWriteResult()
    data class Error(val reason: String) : BleWriteResult()
}

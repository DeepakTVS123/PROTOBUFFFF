package com.example.protobuff.utils

/**
 * Utility class for converting ByteArray <-> Hex String for Protobuf BLE framing.
 */
object HexUtils {

    /**
     * Converts a ByteArray to a continuous uppercase Hex String (e.g. 0A0874657374...).
     */
    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val hexArray = "0123456789ABCDEF".toCharArray()
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = hexArray[v ushr 4]
            hexChars[i * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    /**
     * Formats a ByteArray to a space-separated Hex String (e.g. 0A 08 74 65 73 74...).
     */
    fun bytesToFormattedHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { String.format("%02X", it) }
    }

    /**
     * Converts a Hex String back to a ByteArray.
     */
    fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "").replace("0x", "", ignoreCase = true).trim()
        val len = cleanHex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) + Character.digit(cleanHex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}

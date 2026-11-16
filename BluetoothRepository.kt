package com.example.protobuff.model

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.deepak.protolib.VehicleSDK
import com.example.protobuff.data.model.BleWriteResult
import com.example.protobuff.data.model.FobDevice
import com.example.protobuff.data.model.GattState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.pow

@SuppressLint("MissingPermission")
class BluetoothRepository private constructor(val context: Context) {

    companion object {
        private const val TAG = "BLE"

        // Custom UUIDs matching reference
        val CUSTOM_SERVICE_UUID: UUID = UUID.fromString("5456534M-5647-5341-5342-454E544F5251".replace("M", "D").lowercase())
        val CHARACTERISTIC_TRANSMIT_UUID: UUID = UUID.fromString("00005354-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_RECEIVE_UUID: UUID = UUID.fromString("00005352-0000-1000-8000-00805f9b34fb")
        val CCCD_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        @Volatile
        private var instance: BluetoothRepository? = null

        fun getInstance(context: Context): BluetoothRepository {
            return instance ?: synchronized(this) {
                instance ?: BluetoothRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var isScanning = false
    private var bluetoothGatt: BluetoothGatt? = null
    private val deviceSet = HashSet<String>()
    private val devices = mutableListOf<BluetoothDevice>()
    private val handler = Handler(Looper.getMainLooper())
    var hexString: String = ""

    private val SCAN_PERIOD: Long = 10000
    private val CONNECTION_TIMEOUT: Long = 10000 // 10 seconds
    private val RSSI_CHECK_INTERVAL: Long = 500 // 500ms
    private var isConnecting = false
    private val rssiHistory = mutableListOf<Int>()
    private val RSSI_SMOOTHING_WINDOW = 6
    private val TX_POWER = -59 // dBm at 1 meter
    private val PATH_LOSS_EXPONENT = 2.7
    private val MAX_DISTANCE = 6.0 // meters
    private var lastRangeStatus: Boolean? = null

    // Callbacks
    var onDataReceived: ((String) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null
    var onToast: ((String) -> Unit)? = null
    var onConnectionStatus: ((Boolean, String?) -> Unit)? = null
    var onRangeStatus: ((Boolean) -> Unit)? = null // True = in range, False = out of range
    var onDistanceUpdate: ((Double) -> Unit)? = null
    var onRssiUpdate: ((Int) -> Unit)? = null

    // StateFlows for UI observation
    private val _scannedDevices = MutableStateFlow<List<FobDevice>>(emptyList())
    val scannedDevices: StateFlow<List<FobDevice>> = _scannedDevices.asStateFlow()

    private val _isScanningFlow = MutableStateFlow(false)
    val isScanningFlow: StateFlow<Boolean> = _isScanningFlow.asStateFlow()

    private val _gattState = MutableStateFlow<GattState>(GattState.Disconnected)
    val gattState: StateFlow<GattState> = _gattState.asStateFlow()

    private val _rssiValue = MutableStateFlow<Int?>(null)
    val rssiValue: StateFlow<Int?> = _rssiValue.asStateFlow()

    private val _distanceValue = MutableStateFlow<Double?>(null)
    val distanceValue: StateFlow<Double?> = _distanceValue.asStateFlow()

    private val _notificationBytes = MutableSharedFlow<ByteArray>()
    val notificationBytes: SharedFlow<ByteArray> = _notificationBytes.asSharedFlow()

    init {
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    private fun calculateDistance(rssi: Int): Double {
        val smoothedRssi = if (rssiHistory.size >= RSSI_SMOOTHING_WINDOW) {
            rssiHistory.takeLast(RSSI_SMOOTHING_WINDOW).average().toInt()
        } else {
            rssi
        }
        return 10.0.pow((TX_POWER - smoothedRssi) / (10 * PATH_LOSS_EXPONENT))
    }

    fun startLeScan(onDevicesFound: ((List<BluetoothDevice>) -> Unit)? = null) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            onToast?.invoke("Bluetooth is disabled.")
            Log.e(TAG, "Bluetooth is disabled or not supported")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onToast?.invoke("BluetoothLeScanner not available.")
            Log.e(TAG, "BluetoothLeScanner is null")
            return
        }

        if (isScanning) {
            Log.d(TAG, "Already scanning, ignoring request")
            return
        }

        isScanning = true
        _isScanningFlow.value = true
        deviceSet.clear()
        devices.clear()
        _scannedDevices.value = emptyList()

        val fobDevices = mutableListOf<FobDevice>()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val deviceName = device.name ?: result.scanRecord?.deviceName

                Log.d(TAG, "Found device: $deviceName (${device.address})")

                if (!deviceName.isNullOrEmpty() && deviceName.startsWith("T", ignoreCase = true)) {
                    val distance = calculateDistance(result.rssi)
                    if (deviceSet.add(device.address)) {
                        devices.add(device)
                        fobDevices.add(FobDevice(deviceName, device.address, result.rssi, device))
                        _scannedDevices.value = ArrayList(fobDevices)
                        onDevicesFound?.invoke(devices.toList())
                        Log.d(TAG, "Target Device added: $deviceName (${device.address}), distance: $distance m")
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                isScanning = false
                _isScanningFlow.value = false
                onToast?.invoke("Scan failed: $errorCode")
            }
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, scanSettings, scanCallback)
        onStatusUpdate?.invoke("Scanning for devices...")

        handler.postDelayed({
            stopLeScan()
        }, SCAN_PERIOD)
    }

    fun stopLeScan() {
        if (!isScanning) return

        val scanner = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner
        scanCallback?.let {
            scanner?.stopScan(it)
        }
        scanCallback = null
        isScanning = false
        _isScanningFlow.value = false
        onStatusUpdate?.invoke("Scan finished")
    }

    fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Initiating connection to ${device.name} (${device.address})")
        stopLeScan()

        // 150ms delay before connectGatt to clear BLE scanner state on Android hardware
        handler.postDelayed({
            disconnectInternal()

            isConnecting = true
            rssiHistory.clear()
            lastRangeStatus = null
            _gattState.value = GattState.Connecting

            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }

            // Connection timeout
            handler.postDelayed({
                if (isConnecting) {
                    Log.e(TAG, "Connection timeout for ${device.name}")
                    onConnectionStatus?.invoke(false, "Connection timed out")
                    _gattState.value = GattState.Error("Connection timed out")
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    isConnecting = false
                }
            }, CONNECTION_TIMEOUT)
        }, 150)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            isConnecting = false
            handler.removeCallbacksAndMessages(null)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to ${gatt.device?.name}")
                    onStatusUpdate?.invoke("Connected to ${gatt.device?.name}")
                    onConnectionStatus?.invoke(true, null)
                    _gattState.value = GattState.Connected
                    gatt.discoverServices()
                    startRssiMonitoring(gatt)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from ${gatt.device?.name}, status: $status")
                    onStatusUpdate?.invoke("Disconnected from ${gatt.device?.name}")
                    _gattState.value = GattState.Disconnected
                    stopRssiMonitoring()
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        onConnectionStatus?.invoke(false, "Connection failed: $status")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _gattState.value = GattState.ServicesDiscovered
                val service = gatt.getService(CUSTOM_SERVICE_UUID)
                    ?: gatt.services.find { it.uuid.toString().equals("5456534d-5647-5341-5342-454e544f5251", ignoreCase = true) }

                if (service != null) {
                    val transmitChar = service.getCharacteristic(CHARACTERISTIC_TRANSMIT_UUID)
                        ?: service.characteristics.find { c -> (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0 }

                    if (transmitChar != null) {
                        gatt.setCharacteristicNotification(transmitChar, true)
                        val descriptor = transmitChar.getDescriptor(CCCD_DESCRIPTOR_UUID)
                        descriptor?.let {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(it)
                            }
                        } ?: Log.e(TAG, "Descriptor not found")
                    } else {
                        Log.e(TAG, "Transmit characteristic not found")
                    }
                } else {
                    Log.e(TAG, "Custom service not found")
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHARACTERISTIC_TRANSMIT_UUID) {
                val data = characteristic.value ?: return
                hexString = bytesToHex(data)
                Log.i(TAG, "Receiving Data: $hexString")
                onDataReceived?.invoke(hexString)
                _notificationBytes.tryEmit(data)
                VehicleSDK.getInstance().processRawData(data)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == CHARACTERISTIC_TRANSMIT_UUID) {
                hexString = bytesToHex(value)
                Log.i(TAG, "Receiving Data: $hexString")
                onDataReceived?.invoke(hexString)
                _notificationBytes.tryEmit(value)
                VehicleSDK.getInstance().processRawData(value)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssiValue.value = rssi
                rssiHistory.add(rssi)
                val distance = calculateDistance(rssi)
                _distanceValue.value = distance
                val isInRange = distance <= MAX_DISTANCE
                onDistanceUpdate?.invoke(distance)
                onRssiUpdate?.invoke(rssi)
                if (isInRange != lastRangeStatus) {
                    onRangeStatus?.invoke(isInRange)
                    lastRangeStatus = isInRange
                }
            }
        }
    }

    private fun startRssiMonitoring(gatt: BluetoothGatt) {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (bluetoothGatt == gatt && !isConnecting) {
                    gatt.readRemoteRssi()
                    handler.postDelayed(this, RSSI_CHECK_INTERVAL)
                }
            }
        }, RSSI_CHECK_INTERVAL)
    }

    private fun stopRssiMonitoring() {
        handler.removeCallbacksAndMessages(null)
        rssiHistory.clear()
        lastRangeStatus = null
    }

    fun sendCommand(data: ByteArray): BleWriteResult {
        val gatt = bluetoothGatt
            ?: run {
                Log.e(TAG, "BluetoothGatt is null")
                return BleWriteResult.Error("GATT is not connected")
            }

        val service = gatt.getService(CUSTOM_SERVICE_UUID)
            ?: gatt.services.find { it.uuid.toString().equals("5456534d-5647-5341-5342-454e544f5251", ignoreCase = true) }

        if (service == null) {
            Log.e(TAG, "Service not found")
            return BleWriteResult.Error("Custom GATT Service not found")
        }

        val characteristic = service.getCharacteristic(CHARACTERISTIC_RECEIVE_UUID)
            ?: service.characteristics.find { c -> (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0 }

        if (characteristic == null) {
            Log.e(TAG, "Receive characteristic not found")
            return BleWriteResult.Error("Receive characteristic not found")
        }

        val writeType = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        val hexData = bytesToHex(data)
        Log.e(TAG, "Sending Command: $hexData")

        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(characteristic, data, writeType)
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }

        return if (success) {
            BleWriteResult.Success
        } else {
            Log.e(TAG, "Failed to send command")
            BleWriteResult.Error("Failed to write characteristic")
        }
    }

    fun writeData(bytes: ByteArray): BleWriteResult = sendCommand(bytes)

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    fun disconnect() {
        disconnectInternal()
        _gattState.value = GattState.Disconnected
        _rssiValue.value = null
        _distanceValue.value = null
    }

    private fun disconnectInternal() {
        isConnecting = false
        stopRssiMonitoring()
        bluetoothGatt?.apply {
            disconnect()
            close()
        }
        bluetoothGatt = null
    }
}

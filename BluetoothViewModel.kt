package com.example.protobuff.viewmodel

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import com.deepak.protolib.VehicleSDK
import com.deepak.protolib.VehicleTelemetry
import com.example.protobuff.data.model.BleWriteResult
import com.example.protobuff.data.model.FobDevice
import com.example.protobuff.data.model.GattState
import com.example.protobuff.model.BluetoothRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel bridging UI components with the shared BluetoothRepository Singleton.
 */
class BluetoothViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BluetoothRepository.getInstance(application)

    val scannedDevices: StateFlow<List<FobDevice>> = repository.scannedDevices
    val isScanning: StateFlow<Boolean> = repository.isScanningFlow
    val gattState: StateFlow<GattState> = repository.gattState
    val rssiValue: StateFlow<Int?> = repository.rssiValue
    val distanceValue: StateFlow<Double?> = repository.distanceValue
    val vehicleTelemetry: StateFlow<VehicleTelemetry> = VehicleSDK.getInstance().telemetry

    /**
     * Checks whether Bluetooth hardware is enabled on the device.
     */
    fun isBluetoothEnabled(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return adapter?.isEnabled == true
    }

    /**
     * Checks whether Location / GPS services are enabled on the device.
     */
    fun isLocationEnabled(): Boolean {
        val locationManager = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return locationManager?.let {
            it.isProviderEnabled(LocationManager.GPS_PROVIDER) || it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } ?: false
    }

    /**
     * Initiates BLE scanning for Keyfob devices starting with "T".
     */
    fun startScan() {
        repository.startLeScan()
    }

    /**
     * Stops active BLE scanning.
     */
    fun stopScan() {
        repository.stopLeScan()
    }

    /**
     * Connects to the selected Keyfob device.
     */
    fun connectToDevice(fobDevice: FobDevice) {
        repository.connectToDevice(fobDevice.device)
    }

    /**
     * Sends serialized Protobuf byte array to the Keyfob device via BLE.
     */
    fun sendDataViaBle(bytes: ByteArray): BleWriteResult {
        return repository.writeData(bytes)
    }

    /**
     * Disconnects from the current GATT session.
     */
    fun disconnect() {
        repository.disconnect()
    }
}

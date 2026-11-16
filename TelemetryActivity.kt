package com.example.protobuff.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.deepak.protolib.VehicleProto
import com.deepak.protolib.VehicleSDK
import com.deepak.protolib.VehicleTelemetry
import com.example.protobuff.R
import com.example.protobuff.data.model.BleWriteResult
import com.example.protobuff.data.model.GattState
import com.example.protobuff.utils.HexUtils
import com.example.protobuff.viewmodel.BluetoothViewModel
import kotlinx.coroutines.launch

/**
 * Activity displaying live vehicle telemetry data from VehicleSDK StateFlow,
 * UserData Proto serialization, and PassCode Proto transmission over BLE.
 */
class TelemetryActivity : AppCompatActivity() {

    private val viewModel: BluetoothViewModel by viewModels()

    private lateinit var tvDeviceHeader: TextView
    private lateinit var tvGattState: TextView
    private lateinit var tvRssi: TextView

    private lateinit var tvBatteryVoltage: TextView
    private lateinit var tvWheelSpeed: TextView
    private lateinit var tvEngineRpm: TextView
    private lateinit var tvGearPosition: TextView
    private lateinit var tvThrottlePosition: TextView
    private lateinit var tvFuelLevel: TextView
    private lateinit var tvTyrePressure: TextView
    private lateinit var tvStatusIndicators: TextView

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnSendUserData: Button
    private lateinit var tvUserDataResult: TextView

    private lateinit var etPassCode: EditText
    private lateinit var btnSendPassCode: Button
    private lateinit var tvPassCodeResult: TextView

    private lateinit var btnDisconnectTelemetry: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telemetry)

        val deviceName = intent.getStringExtra("EXTRA_DEVICE_NAME") ?: "Unknown Device"
        val deviceAddress = intent.getStringExtra("EXTRA_DEVICE_ADDRESS") ?: "--:--:--:--:--"

        initViews(deviceName, deviceAddress)
        observeViewModel()
    }

    @SuppressLint("SetTextI18n")
    private fun initViews(deviceName: String, deviceAddress: String) {
        tvDeviceHeader = findViewById(R.id.tvDeviceHeader)
        tvGattState = findViewById(R.id.tvGattState)
        tvRssi = findViewById(R.id.tvRssi)

        tvBatteryVoltage = findViewById(R.id.tvBatteryVoltage)
        tvWheelSpeed = findViewById(R.id.tvWheelSpeed)
        tvEngineRpm = findViewById(R.id.tvEngineRpm)
        tvGearPosition = findViewById(R.id.tvGearPosition)
        tvThrottlePosition = findViewById(R.id.tvThrottlePosition)
        tvFuelLevel = findViewById(R.id.tvFuelLevel)
        tvTyrePressure = findViewById(R.id.tvTyrePressure)
        tvStatusIndicators = findViewById(R.id.tvStatusIndicators)

        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnSendUserData = findViewById(R.id.btnSendUserData)
        tvUserDataResult = findViewById(R.id.tvUserDataResult)

        etPassCode = findViewById(R.id.etPassCode)
        btnSendPassCode = findViewById(R.id.btnSendPassCode)
        tvPassCodeResult = findViewById(R.id.tvPassCodeResult)

        btnDisconnectTelemetry = findViewById(R.id.btnDisconnectTelemetry)

        tvDeviceHeader.text = "Connected Device: $deviceName ($deviceAddress)"

        btnSendUserData.setOnClickListener {
            handleUserDataSerialization()
        }

        btnSendPassCode.setOnClickListener {
            handlePassCodeSerialization()
        }

        btnDisconnectTelemetry.setOnClickListener {
            viewModel.disconnect()
            finish()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 1. Observe GATT State
                launch {
                    viewModel.gattState.collect { state ->
                        updateGattUiState(state)
                    }
                }

                // 2. Observe Live RSSI
                launch {
                    viewModel.rssiValue.collect { rssi ->
                        tvRssi.text = if (rssi != null) "Live RSSI: $rssi dBm" else "Live RSSI: -- dBm"
                    }
                }

                // 3. Observe Vehicle Telemetry StateFlow from VehicleSDK
                launch {
                    viewModel.vehicleTelemetry.collect { telemetry ->
                        updateTelemetryUi(telemetry)
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateGattUiState(state: GattState) {
        when (state) {
            is GattState.Disconnected -> tvGattState.text = "GATT State: Disconnected"
            is GattState.Connecting -> tvGattState.text = "GATT State: Connecting..."
            is GattState.Connected -> tvGattState.text = "GATT State: Connected"
            is GattState.ServicesDiscovered -> tvGattState.text = "GATT State: Services Discovered (Active)"
            is GattState.Error -> tvGattState.text = "GATT Error: ${state.message}"
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateTelemetryUi(data: VehicleTelemetry) {
        tvBatteryVoltage.text = "Battery Voltage: ${data.batteryVoltageDisplay} V"
        tvWheelSpeed.text = "Speed: ${data.wheelSpeedKmh} km/h"
        tvEngineRpm.text = "Engine RPM: ${data.engineSpeedRpm} RPM"
        tvGearPosition.text = "Gear Position: ${if (data.gearPosition == 0) "N" else data.gearPosition.toString()}"
        tvThrottlePosition.text = "Throttle Position: ${data.throttlePositionDisplay} %"
        tvFuelLevel.text = "Fuel Level: ${data.fuelLevelPct} %"
        tvTyrePressure.text = "Tyre Pressure: Front ${data.tyrePressureFrontDisplay} PSI | Rear ${data.tyrePressureRearDisplay} PSI"

        val milText = if (data.milOn) "MIL: ON ⚠️" else "MIL: OFF ✅"
        val absText = if (data.absWarning) "ABS: WARNING ⚠️" else "ABS: OK ✅"
        val standText = if (data.sideStandDown) "SideStand: DOWN" else "SideStand: UP"

        tvStatusIndicators.text = "Indicators: $milText | $absText | $standText"
    }

    @SuppressLint("SetTextI18n")
    private fun handleUserDataSerialization() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (username.isEmpty()) {
            Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show()
            return
        }

        if (username.length > 32) {
            Toast.makeText(this, "Username must not exceed 32 characters (currently ${username.length})", Toast.LENGTH_SHORT).show()
            return
        }

        if (password.length < 8 || password.length > 32) {
            Toast.makeText(this, "Password must be between 8 and 32 characters (currently ${password.length})", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Encode UserData into VehicleMessage envelope byte array using VehicleSDK
        val serializedBytes = VehicleSDK.getInstance().encodeUserData(username, password)

        // 2. Convert bytes to Hex string representation
        val hexString = HexUtils.bytesToHex(serializedBytes)
        val formattedHex = HexUtils.bytesToFormattedHex(serializedBytes)

        // 3. Send serialized byte array over Bluetooth to connected device
        val writeResult = viewModel.sendDataViaBle(serializedBytes)

        val statusText = when (writeResult) {
            is BleWriteResult.Success -> "SENT USERDATA ENVELOPE OVER BLE SUCCESSFULLY! ✅ (${serializedBytes.size} bytes)"
            is BleWriteResult.Error -> "BLE SEND FAILED ❌ (${writeResult.reason})"
        }

        val parsedEnvelope = VehicleProto.VehicleMessage.parseFrom(serializedBytes)

        tvUserDataResult.text = """
            BLE Status: $statusText
            Envelope Size: ${serializedBytes.size} bytes
            Payload Case: ${parsedEnvelope.payloadCase}
            Hex String: $hexString
            Formatted Hex: $formattedHex
            Sent Username: ${parsedEnvelope.userData.username}
            Sent Password: ${parsedEnvelope.userData.password}
        """.trimIndent()

        Toast.makeText(this, statusText, Toast.LENGTH_LONG).show()
    }

    @SuppressLint("SetTextI18n")
    private fun handlePassCodeSerialization() {
        val passCodeInput = etPassCode.text.toString().trim()

        if (passCodeInput.length != 4 || !passCodeInput.all { it.isDigit() }) {
            Toast.makeText(this, "Please enter a valid 4-digit passcode (e.g. 1234)", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Encode PassCode into VehicleMessage envelope byte array using VehicleSDK
        val serializedBytes = VehicleSDK.getInstance().encodePassCode(passCodeInput)

        // 2. Convert bytes to Hex string representation
        val hexString = HexUtils.bytesToHex(serializedBytes)
        val formattedHex = HexUtils.bytesToFormattedHex(serializedBytes)

        // 3. Send serialized byte array over Bluetooth to connected device
        val writeResult = viewModel.sendDataViaBle(serializedBytes)

        val statusText = when (writeResult) {
            is BleWriteResult.Success -> "PASSCODE SENT OVER BLE SUCCESSFULLY! ✅ (${serializedBytes.size} bytes)"
            is BleWriteResult.Error -> "BLE SEND FAILED ❌ (${writeResult.reason})"
        }

        val parsedEnvelope = VehicleProto.VehicleMessage.parseFrom(serializedBytes)
        val p = parsedEnvelope.passCode

        tvPassCodeResult.text = """
            BLE Status: $statusText
            Envelope Size: ${serializedBytes.size} bytes
            Payload Case: ${parsedEnvelope.payloadCase}
            Hex String: $hexString
            Formatted Hex: $formattedHex
            Sent Passcode Digits: ${p.digit1} ${p.digit2} ${p.digit3} ${p.digit4}
        """.trimIndent()

        Toast.makeText(this, statusText, Toast.LENGTH_LONG).show()
    }
}

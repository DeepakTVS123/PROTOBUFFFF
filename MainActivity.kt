package com.example.protobuff.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.protobuff.R
import com.example.protobuff.data.model.GattState
import com.example.protobuff.viewmodel.BluetoothViewModel
import kotlinx.coroutines.launch

/**
 * MainActivity demonstrating:
 * 1. Bluetooth & Location Permission and Prerequisite Checks
 * 2. BLE Scanning & Target Device Filtering
 * 3. GATT Lifecycle & Connection Management
 * 4. GATT Notifications & Periodic RSSI Polling
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: BluetoothViewModel
    private lateinit var deviceAdapter: DeviceAdapter

    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvGattStatus: TextView
    private lateinit var tvConnectedRssi: TextView
    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var rvDevices: RecyclerView

    // Runtime Permission Request Launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            tvPermissionStatus.text = "Permissions: Granted"
            checkPrerequisitesAndStartScan()
        } else {
            tvPermissionStatus.text = "Permissions: Denied"
            Toast.makeText(this, "Bluetooth and Location permissions are required for BLE scanning.", Toast.LENGTH_LONG).show()
        }
    }

    // Enable Bluetooth Launcher
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (viewModel.isBluetoothEnabled()) {
            checkPrerequisitesAndStartScan()
        } else {
            Toast.makeText(this, "Bluetooth must be enabled to scan for keyfobs.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[BluetoothViewModel::class.java]

        initViews()
        setupRecyclerView()
        observeViewModel()
        updatePermissionStatusText()
    }

    private fun initViews() {
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        tvGattStatus = findViewById(R.id.tvGattStatus)
        tvConnectedRssi = findViewById(R.id.tvConnectedRssi)
        btnScan = findViewById(R.id.btnScan)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        progressBar = findViewById(R.id.progressBar)
        rvDevices = findViewById(R.id.rvDevices)

        btnScan.setOnClickListener {
            if (hasRequiredPermissions()) {
                checkPrerequisitesAndStartScan()
            } else {
                requestRequiredPermissions()
            }
        }

        btnDisconnect.setOnClickListener {
            viewModel.disconnect()
        }
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter { selectedDevice ->
            Toast.makeText(this, "Connecting to ${selectedDevice.name}...", Toast.LENGTH_SHORT).show()
            viewModel.connectToDevice(selectedDevice)

            val intent = Intent(this, TelemetryActivity::class.java).apply {
                putExtra("EXTRA_DEVICE_NAME", selectedDevice.name)
                putExtra("EXTRA_DEVICE_ADDRESS", selectedDevice.address)
            }
            startActivity(intent)
        }
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter
    }

    private fun observeViewModel() {
        lifecycleScope.launchWhenStarted {
            launch {
                viewModel.scannedDevices.collect { devices ->
                    deviceAdapter.submitList(devices)
                }
            }

            launch {
                viewModel.isScanning.collect { isScanning ->
                    progressBar.visibility = if (isScanning) View.VISIBLE else View.GONE
                    btnScan.text = if (isScanning) "Scanning..." else "Start BLE Scan"
                }
            }

            launch {
                viewModel.gattState.collect { state ->
                    updateGattUiState(state)
                }
            }

            launch {
                viewModel.rssiValue.collect { rssi ->
                    if (rssi != null) {
                        tvConnectedRssi.text = "Live RSSI: $rssi dBm"
                    } else {
                        tvConnectedRssi.text = "Live RSSI: -- dBm"
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateGattUiState(state: GattState) {
        when (state) {
            is GattState.Disconnected -> {
                tvGattStatus.text = "GATT State: Disconnected"
                btnDisconnect.isEnabled = false
            }
            is GattState.Connecting -> {
                tvGattStatus.text = "GATT State: Connecting..."
                btnDisconnect.isEnabled = true
            }
            is GattState.Connected -> {
                tvGattStatus.text = "GATT State: Connected (Discovering Services...)"
                btnDisconnect.isEnabled = true
            }
            is GattState.ServicesDiscovered -> {
                tvGattStatus.text = "GATT State: Services Discovered (Notifications Ready)"
                btnDisconnect.isEnabled = true
            }
            is GattState.Error -> {
                tvGattStatus.text = "GATT Error: ${state.message}"
                btnDisconnect.isEnabled = false
                Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Checks Bluetooth & Location prerequisites before starting BLE scan.
     */
    private fun checkPrerequisitesAndStartScan() {
        // 1. Check if Bluetooth is enabled
        if (!viewModel.isBluetoothEnabled()) {
            Toast.makeText(this, "Prompting to enable Bluetooth...", Toast.LENGTH_SHORT).show()
            @Suppress("DEPRECATION")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
            return
        }

        // 2. Check if Location Services are enabled
        if (!viewModel.isLocationEnabled()) {
            Toast.makeText(this, "Please enable Location/GPS services for BLE scanning.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        // Prerequisites passed -> Start Scan
        viewModel.startScan()
    }

    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        permissionLauncher.launch(getRequiredPermissions())
    }

    @SuppressLint("SetTextI18n")
    private fun updatePermissionStatusText() {
        if (hasRequiredPermissions()) {
            tvPermissionStatus.text = "Permissions: Granted"
        } else {
            tvPermissionStatus.text = "Permissions: Pending Request"
        }
    }
}

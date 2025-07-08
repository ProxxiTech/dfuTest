package com.example.bledfutesteractivity

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive // <-- This import fixes the error
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Private mutable flows for internal state management
    private val _logMessages = MutableStateFlow("")
    private val _testProgress = MutableStateFlow(0)
    private val _isTestRunning = MutableStateFlow(false)
    private val _scannedDevices = MutableStateFlow<List<ScanResult>>(emptyList())
    private val _selectedFileUri = MutableStateFlow<Uri?>(null)

    // Public immutable flows for the UI to observe
    val logMessages = _logMessages.asStateFlow()
    val testProgress = _testProgress.asStateFlow()
    val isTestRunning = _isTestRunning.asStateFlow()
    val scannedDevices = _scannedDevices.asStateFlow()
    val selectedFileUri = _selectedFileUri.asStateFlow()

    private var dfuService: DfuTestingService? = null
    private var serviceJob: Job? = null

    // BLE Scanning components
    private val bluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner = bluetoothAdapter?.bluetoothLeScanner
    private var scanJob: Job? = null
    private val scanResults = mutableMapOf<String, ScanResult>()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            if (result.device.name!= null) {
                scanResults[result.device.address] = result
                _scannedDevices.value = scanResults.values.toList().sortedByDescending { it.rssi }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            // You can log scan errors here
        }
    }

    fun onServiceConnected(service: DfuTestingService) {
        this.dfuService = service
        serviceJob?.cancel()
        serviceJob = viewModelScope.launch {
            launch { service.logMessages.collect { _logMessages.value = it } }
            launch { service.testProgress.collect { _testProgress.value = it } }
            launch { service.isTestRunning.collect { _isTestRunning.value = it } }
        }
    }

    fun onServiceDisconnected() {
        serviceJob?.cancel()
        dfuService = null
    }

    fun onFirmwareFileSelected(uri: Uri) {
        _selectedFileUri.value = uri
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanJob?.isActive == true) return

        scanJob = viewModelScope.launch {
            scanResults.clear()
            _scannedDevices.value = emptyList()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner?.startScan(emptyList(), settings, scanCallback)

            delay(15_000)

            // CORRECTED: Check if the coroutine is still active before stopping the scan.
            if (isActive) {
                stopScan()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanJob?.cancel()
        scanner?.stopScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    override fun onCleared() {
        super.onCleared()
        scanner?.stopScan(scanCallback)
    }
}
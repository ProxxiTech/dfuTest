package com.example.bledfutesteractivity

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import no.nordicsemi.android.dfu.DfuServiceInitiator
import java.io.File

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    // UI Elements
    private lateinit var devicesRecyclerView: RecyclerView
    private lateinit var deviceScanAdapter: DeviceScanAdapter
    private lateinit var buttonSelectFile: Button
    private lateinit var textSelectedFile: TextView
    private lateinit var editTextIterations: EditText
    private lateinit var editTextTimeout: EditText
    private lateinit var buttonStartStopTest: Button
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var logScrollView: ScrollView
    private lateinit var logTextView: TextView
    private lateinit var buttonShareLog: Button
    private lateinit var buttonNewTest: Button

    private var selectedDevice: BluetoothDevice? = null
    private var selectedFileUri: Uri? = null

    private var dfuService: DfuTestingService? = null
    private var isBound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as DfuTestingService.DfuBinder
            dfuService = binder.getService()
            viewModel.onServiceConnected(binder.getService())
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            viewModel.onServiceDisconnected()
            isBound = false
            dfuService = null
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                startScan()
            } else {
                Toast.makeText(this, "All permissions are required for the app to function.", Toast.LENGTH_LONG).show()
            }
        }

    private val requestBluetoothEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.startScan()
            } else {
                Toast.makeText(this, "Bluetooth is required to scan for devices.", Toast.LENGTH_SHORT).show()
            }
        }

    private val selectFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                selectedFileUri = it
                val fileName = getFileNameFromUri(this, it)
                textSelectedFile.text = fileName
                viewModel.onFirmwareFileSelected(it)
                Toast.makeText(this, "Selected file: $fileName", Toast.LENGTH_SHORT).show()
                updateStartButtonState()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupUI()
        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
        createDfuNotificationChannel()
        requestAllPermissions()

        updateStartButtonState()
    }

    override fun onStart() {
        super.onStart()
        Intent(this, DfuTestingService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private fun setupUI() {
        devicesRecyclerView = findViewById(R.id.devices_recycler_view)
        buttonSelectFile = findViewById(R.id.button_select_file)
        textSelectedFile = findViewById(R.id.text_selected_file)
        editTextIterations = findViewById(R.id.edit_text_iterations)
        editTextTimeout = findViewById(R.id.edit_text_timeout)
        buttonStartStopTest = findViewById(R.id.button_start_stop_test)
        progressBar = findViewById(R.id.progress_bar)
        logScrollView = findViewById(R.id.log_scroll_view)
        logTextView = findViewById(R.id.log_text_view)
        buttonShareLog = findViewById(R.id.button_share_log)
        buttonNewTest = findViewById(R.id.button_new_test)
    }

    @SuppressLint("MissingPermission")
    private fun setupRecyclerView() {
        deviceScanAdapter = DeviceScanAdapter { device ->
            selectedDevice = device
            Toast.makeText(this, "Selected: ${device.name?: device.address}", Toast.LENGTH_SHORT).show()
            viewModel.stopScan()
            updateStartButtonState()
        }
        devicesRecyclerView.adapter = deviceScanAdapter
        devicesRecyclerView.layoutManager = GridLayoutManager(this, 2)
    }

    private fun setupClickListeners() {
        buttonSelectFile.setOnClickListener {
            selectFileLauncher.launch(arrayOf("application/zip"))
        }

        buttonStartStopTest.setOnClickListener { onStartStopTestClicked() }

        buttonShareLog.setOnClickListener { onShareLogClicked() }

        buttonNewTest.setOnClickListener { resetForNewTest() }
    }

    private fun onStartStopTestClicked() {
        if (viewModel.isTestRunning.value) {
            dfuService?.stopTest()
        } else {
            val service = dfuService
            if (service == null) {
                Toast.makeText(this, "Service not ready, please wait a moment.", Toast.LENGTH_SHORT).show()
                return
            }

            val device = selectedDevice
            val fileUri = selectedFileUri
            val iterations = editTextIterations.text.toString().toIntOrNull()?: 10
            val timeout = editTextTimeout.text.toString().toLongOrNull()?: 120

            if (device == null) {
                Toast.makeText(this, "Please select a target device.", Toast.LENGTH_SHORT).show()
                return
            }
            if (fileUri == null) {
                Toast.makeText(this, "Please select a DFU firmware file.", Toast.LENGTH_SHORT).show()
                return
            }

            Intent(this, DfuTestingService::class.java).also { intent ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
            service.startTest(device.address, fileUri, iterations, timeout)
        }
    }

    private fun onShareLogClicked() {
        val logDir = File(cacheDir, "logs")
        val logFile = File(logDir, "dfu_stress_test_log.txt")

        if (!logFile.exists()) {
            Toast.makeText(this, "Log file not found.", Toast.LENGTH_SHORT).show()
            return
        }

        val authority = "${applicationContext.packageName}.provider"
        val logUri = FileProvider.getUriForFile(this, authority, logFile)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, logUri)
            putExtra(Intent.EXTRA_SUBJECT, "DFU Stress Test Log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(shareIntent, "Share Log File"))
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.logMessages.collect { messages ->
                        logTextView.text = messages
                        logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
                    }
                }

                launch {
                    viewModel.testProgress.collect { progress ->
                        progressBar.progress = progress
                        dfuService?.updateNotificationProgress(progress, 100)
                    }
                }

                launch {
                    viewModel.isTestRunning.collect { isRunning ->
                        // This is the new logic to keep the screen on
                        if (isRunning) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }

                        buttonStartStopTest.text = if (isRunning) "Stop Test" else "Start Test"
                        buttonSelectFile.isEnabled =!isRunning
                        editTextIterations.isEnabled =!isRunning
                        editTextTimeout.isEnabled =!isRunning
                        devicesRecyclerView.isEnabled =!isRunning

                        val showPostTestButtons =!isRunning && logTextView.text.isNotEmpty()
                        buttonShareLog.visibility = if (showPostTestButtons) View.VISIBLE else View.GONE
                        buttonNewTest.visibility = if (showPostTestButtons) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.scannedDevices.collect { devices ->
                        deviceScanAdapter.clearDevices()
                        devices.forEach { deviceScanAdapter.addDevice(it) }
                    }
                }
            }
        }
    }

    private fun requestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsNotGranted = permissionsToRequest.filter {
            ActivityCompat.checkSelfPermission(this, it)!= PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNotGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsNotGranted.toTypedArray())
        } else {
            startScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "This device does not support Bluetooth.", Toast.LENGTH_LONG).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestBluetoothEnableLauncher.launch(enableBtIntent)
        } else {
            viewModel.startScan()
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var fileName: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex!= -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        return fileName
    }

    private fun createDfuNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DfuServiceInitiator.createDfuNotificationChannel(this)
        }
    }

    private fun updateStartButtonState() {
        buttonStartStopTest.isEnabled = selectedDevice!= null && selectedFileUri!= null
    }

    private fun resetForNewTest() {
        selectedDevice = null
        selectedFileUri = null

        textSelectedFile.text = ""
        logTextView.text = ""
        progressBar.progress = 0
        deviceScanAdapter.clearDevices()

        updateStartButtonState()
        buttonNewTest.visibility = View.GONE
        buttonShareLog.visibility = View.GONE

        requestAllPermissions()
    }
}
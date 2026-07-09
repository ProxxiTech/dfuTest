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
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.slider.RangeSlider
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
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
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
    private lateinit var buttonRescan: Button
    private lateinit var buttonResetBt: Button
    private lateinit var labelDevices: TextView
    private lateinit var sliderDelay: RangeSlider
    private lateinit var textDelayLabel: TextView
    private lateinit var progressBarDfu: LinearProgressIndicator
    private lateinit var textIterationStatus: TextView
    private lateinit var textDfuStatus: TextView
    private lateinit var pieChart: PieChartView
    private lateinit var textPieLegend: TextView

    private var selectedDevice: BluetoothDevice? = null
    private var cachedFirmwareFile: File? = null

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
                val fileName = getFileNameFromUri(this, it)
                cachedFirmwareFile = copyFirmwareToCache(it)
                if (cachedFirmwareFile != null) {
                    textSelectedFile.text = fileName
                    buttonSelectFile.text = "Change File"
                    viewModel.onFirmwareFileSelected(it)
                } else {
                    Toast.makeText(this, "Failed to read firmware file.", Toast.LENGTH_LONG).show()
                }
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
        labelDevices = findViewById(R.id.label_devices)
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
        buttonRescan = findViewById(R.id.button_rescan)
        buttonResetBt = findViewById(R.id.button_reset_bt)
        progressBarDfu = findViewById(R.id.progress_bar_dfu)
        textIterationStatus = findViewById(R.id.text_iteration_status)
        textDfuStatus = findViewById(R.id.text_dfu_status)
        pieChart = findViewById(R.id.pie_chart)
        textPieLegend = findViewById(R.id.text_pie_legend)
        sliderDelay = findViewById(R.id.slider_delay)
        textDelayLabel = findViewById(R.id.text_delay_label)

        // Two-thumb range: user picks a min and max; each inter-iteration wait is a random value
        // in [min, max].
        sliderDelay.values = listOf(3f, 5f)
        updateDelayLabel()
        sliderDelay.addOnChangeListener { _, _, _ -> updateDelayLabel() }
    }

    private fun updateDelayLabel() {
        val lo = sliderDelay.values.first().toInt()
        val hi = sliderDelay.values.last().toInt()
        textDelayLabel.text =
            if (lo == hi) "Delay between DFU: $lo min"
            else "Delay between DFU: $lo-$hi min (random)"
    }

    @SuppressLint("MissingPermission")
    private fun setupRecyclerView() {
        deviceScanAdapter = DeviceScanAdapter { device ->
            selectedDevice = device
            viewModel.stopScan()
            val label = if (device.name != null) "${device.name} (${device.address})" else device.address
            collapseDeviceSection(label)
            updateStartButtonState()
        }
        devicesRecyclerView.adapter = deviceScanAdapter
        devicesRecyclerView.layoutManager = GridLayoutManager(this, 2)
        (devicesRecyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    private fun collapseDeviceSection(deviceLabel: String) {
        labelDevices.text = "Device: $deviceLabel"
        devicesRecyclerView.visibility = View.GONE
        buttonRescan.text = "Change"
    }

    private fun expandDeviceSection() {
        labelDevices.text = "Nearby Devices"
        devicesRecyclerView.visibility = View.VISIBLE
        buttonRescan.text = "Rescan"
        deviceScanAdapter.clearDevices()
    }

    private fun setupClickListeners() {
        buttonSelectFile.setOnClickListener {
            selectFileLauncher.launch(arrayOf("application/zip"))
        }

        buttonStartStopTest.setOnClickListener { onStartStopTestClicked() }

        buttonShareLog.setOnClickListener { onShareLogClicked() }

        buttonNewTest.setOnClickListener { resetForNewTest() }

        buttonRescan.setOnClickListener {
            selectedDevice = null
            updateStartButtonState()
            expandDeviceSection()
            startScan()
        }

        buttonResetBt.setOnClickListener { resetBluetooth() }
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
            val firmwareFile = cachedFirmwareFile
            val iterations = editTextIterations.text.toString().toIntOrNull() ?: 10
            val timeout = editTextTimeout.text.toString().toLongOrNull() ?: 120
            val delayMin = sliderDelay.values.first().toInt()
            val delayMax = sliderDelay.values.last().toInt()

            if (device == null) {
                Toast.makeText(this, "Please select a target device.", Toast.LENGTH_SHORT).show()
                return
            }
            if (firmwareFile == null) {
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
            service.startTest(device.name, device.address, firmwareFile, iterations, timeout, delayMin, delayMax)
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
                        buttonSelectFile.isEnabled = !isRunning
                        editTextIterations.isEnabled = !isRunning
                        editTextTimeout.isEnabled = !isRunning
                        sliderDelay.isEnabled = !isRunning
                        devicesRecyclerView.isEnabled = !isRunning
                        buttonRescan.isEnabled = !isRunning

                        val showPostTestButtons =!isRunning && logTextView.text.isNotEmpty()
                        buttonShareLog.visibility = if (showPostTestButtons) View.VISIBLE else View.GONE
                        buttonNewTest.visibility = if (showPostTestButtons) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.scannedDevices.collect { devices ->
                        if (devices.isEmpty()) {
                            deviceScanAdapter.clearDevices()
                        } else {
                            devices.forEach { deviceScanAdapter.addDevice(it) }
                        }
                    }
                }

                launch {
                    viewModel.dfuIterationProgress.collect { percent ->
                        progressBarDfu.progress = percent
                    }
                }

                launch {
                    viewModel.dfuStatusText.collect { text ->
                        textDfuStatus.text = text
                    }
                }

                launch {
                    viewModel.currentIteration.collect { updateIterationStatus() }
                }

                launch {
                    viewModel.totalIterations.collect { updateIterationStatus() }
                }

                launch {
                    viewModel.successCount.collect { updatePieChart() }
                }

                launch {
                    viewModel.failCount.collect { updatePieChart() }
                }

                launch {
                    viewModel.totalIterations.collect { updatePieChart() }
                }
            }
        }
    }

    private fun updateIterationStatus() {
        val current = viewModel.currentIteration.value
        val total = viewModel.totalIterations.value
        textIterationStatus.text = if (total > 0) "Iteration: $current / $total" else "–"
    }

    private fun updatePieChart() {
        val success   = viewModel.successCount.value
        val fail      = viewModel.failCount.value
        val remaining = maxOf(0, viewModel.totalIterations.value - success - fail)
        pieChart.successCount   = success
        pieChart.failCount      = fail
        pieChart.remainingCount = remaining

        val sb = SpannableStringBuilder()
        fun append(text: String, color: Int) {
            val start = sb.length
            sb.append(text)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        append("✓$success",   Color.parseColor("#4CAF50"))
        sb.append("  ")
        append("✗$fail",      Color.parseColor("#F44336"))
        sb.append("  ")
        append("…$remaining", Color.parseColor("#9E9E9E"))
        textPieLegend.text = sb
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

    private fun copyFirmwareToCache(uri: Uri): File? {
        return try {
            val firmwareDir = File(cacheDir, "firmware")
            firmwareDir.mkdirs()
            val dest = File(firmwareDir, "firmware.zip")
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            dest
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun resetBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ blocks programmatic toggle — guide user to do it manually
            Toast.makeText(
                this,
                "Please toggle Bluetooth off and on in Settings to reset the BLE stack.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        } else {
            @Suppress("DEPRECATION")
            adapter.disable()
            Toast.makeText(this, "Resetting Bluetooth…", Toast.LENGTH_SHORT).show()
            buttonResetBt.isEnabled = false
            buttonResetBt.postDelayed({
                @Suppress("DEPRECATION")
                adapter.enable()
                buttonResetBt.postDelayed({
                    buttonResetBt.isEnabled = true
                    startScan()
                }, 3000)
            }, 2000)
        }
    }

    private fun updateStartButtonState() {
        buttonStartStopTest.isEnabled = selectedDevice != null && cachedFirmwareFile != null
    }

    private fun resetForNewTest() {
        selectedDevice = null
        cachedFirmwareFile = null

        textSelectedFile.text = ""
        buttonSelectFile.text = "Select DFU File"
        logTextView.text = ""
        progressBar.progress = 0
        progressBarDfu.progress = 0
        textIterationStatus.text = "–"
        textDfuStatus.text = "Upload: 0%"
        textPieLegend.text = ""
        viewModel.resetStats()
        updatePieChart()

        expandDeviceSection()
        updateStartButtonState()
        buttonNewTest.visibility = View.GONE
        buttonShareLog.visibility = View.GONE

        requestAllPermissions()
    }
}
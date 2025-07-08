package com.example.bledfutesteractivity

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import no.nordicsemi.android.dfu.*
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.coroutineContext

class DfuTestingService : Service() {

    private val binder = DfuBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    val logMessages = MutableStateFlow("")
    val testProgress = MutableStateFlow(0)
    val isTestRunning = MutableStateFlow(false)

    private var successCount = 0
    private var failCount = 0
    private lateinit var logFile: File
    private var testJob: Job? = null

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        setupLogFile()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("DFU Stress Test is running...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    inner class DfuBinder : Binder() {
        fun getService(): DfuTestingService = this@DfuTestingService
    }

    fun startTest(deviceAddress: String, firmwareUri: Uri, iterations: Int, timeoutSeconds: Long) {
        if (isTestRunning.value) return

        testJob = serviceScope.launch {
            try {
                isTestRunning.value = true
                setupLogFile()
                runTestLoop(deviceAddress, firmwareUri, iterations, timeoutSeconds)
            } finally {
                // This 'finally' block will now execute whether the test finishes
                // normally or is cancelled by the user.
                isTestRunning.value = false
                log("Test session ended. Final Score -> Success: $successCount, Fail: $failCount")
                stopForeground(true)
                stopSelf()
            }
        }
    }

    fun stopTest() {
        testJob?.cancel("User stopped the test.")
        log("Test manually stopped by user.")
    }

    private suspend fun runTestLoop(
        initialDeviceAddress: String,
        firmwareUri: Uri,
        iterations: Int,
        timeoutSeconds: Long
    ) {
        var currentDeviceAddress = initialDeviceAddress
        successCount = 0
        failCount = 0

        for (i in 1..iterations) {
            coroutineContext.ensureActive()

            log("--- Starting DFU Iteration ${i}/${iterations} on device ${currentDeviceAddress} ---")
            updateNotificationProgress(i, iterations)

            val dfuResult = performDfuWithTimeout(currentDeviceAddress, firmwareUri, timeoutSeconds)

            if (dfuResult) {
                successCount++
                log("DFU Iteration ${i} SUCCESSFUL.")
            } else {
                failCount++
                log("DFU Iteration ${i} FAILED.")
            }

            testProgress.value = (i * 100) / iterations

            if (i < iterations) {
                coroutineContext.ensureActive()
                log("Waiting 30 seconds before next scan...")
                delay(30_000)

                log("Re-scanning for device (last known address: $currentDeviceAddress)...")
                val foundDevice = findDeviceAfterDfu(currentDeviceAddress)

                if (foundDevice!= null) {
                    currentDeviceAddress = foundDevice.address
                    log("Device found at new address: ${currentDeviceAddress}. Proceeding.")
                } else {
                    log("CRITICAL: Could not find device within 5 minutes. Stopping test.")
                    break
                }
            }
        }
    }

    private suspend fun performDfuWithTimeout(address: String, uri: Uri, timeoutSeconds: Long): Boolean {
        return try {
            withTimeout(timeoutSeconds * 1000) {
                initiateDfu(address, uri)
            }
        } catch (e: TimeoutCancellationException) {
            log("DFU timed out after $timeoutSeconds seconds.")
            false
        } catch (e: Exception) {
            log("An unexpected error occurred during DFU: ${e.message}")
            false
        }
    }

    private suspend fun initiateDfu(address: String, uri: Uri): Boolean =
        suspendCancellableCoroutine { continuation ->
            val progressListener = object : DfuProgressListenerAdapter() {
                override fun onDfuCompleted(deviceAddress: String) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onError(deviceAddress: String, error: Int, errorType: Int, message: String?) {
                    log("DFU Error: $message (Code: $error)")
                    if (continuation.isActive) continuation.resume(false)
                }

                override fun onDfuAborted(deviceAddress: String) {
                    log("DFU Aborted.")
                    if (continuation.isActive) continuation.resume(false)
                }
                override fun onDeviceConnecting(deviceAddress: String) { log("Connecting to DFU target...") }
                override fun onDfuProcessStarting(deviceAddress: String) { log("DFU process starting...") }
                override fun onEnablingDfuMode(deviceAddress: String) { log("Enabling DFU mode...") }
                override fun onFirmwareValidating(deviceAddress: String) { log("Validating firmware...") }
                override fun onDeviceDisconnecting(deviceAddress: String) { log("Disconnecting...") }
            }

            DfuServiceListenerHelper.registerProgressListener(this, progressListener, address)

            val starter = DfuServiceInitiator(address)
                .setKeepBond(false)
                .setForceDfu(true)
                .setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true)
                .setZip(uri)

            val controller = starter.start(this, DfuService::class.java)

            continuation.invokeOnCancellation {
                log("Dfu coroutine cancelled. Aborting DFU.")
                controller.abort()
                DfuServiceListenerHelper.unregisterProgressListener(this, progressListener)
            }
        }

    @SuppressLint("MissingPermission")
    private suspend fun findDeviceAfterDfu(lastKnownAddress: String): BluetoothDevice? {
        return withTimeoutOrNull(5 * 60 * 1000) {
            suspendCancellableCoroutine { continuation ->
                val leScanner = BluetoothAdapter.getDefaultAdapter().bluetoothLeScanner
                var deviceFound = false

                val scanCallback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (!deviceFound) {
                            deviceFound = true
                            leScanner.stopScan(this)
                            if (continuation.isActive) continuation.resume(result.device)
                        }
                    }
                    override fun onScanFailed(errorCode: Int) {
                        log("Scan failed with error code: $errorCode")
                        if (continuation.isActive) continuation.resume(null)
                    }
                }

                val incrementedAddress = getIncrementedMacAddress(lastKnownAddress)
                val filters = mutableListOf(ScanFilter.Builder().setDeviceAddress(lastKnownAddress).build())
                incrementedAddress?.let {
                    log("Also scanning for incremented address: $it")
                    filters.add(ScanFilter.Builder().setDeviceAddress(it).build())
                }

                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                leScanner.startScan(filters, settings, scanCallback)

                continuation.invokeOnCancellation {
                    leScanner.stopScan(scanCallback)
                }
            }
        }
    }

    private fun getIncrementedMacAddress(macAddress: String): String? {
        return try {
            val macAsLong = macAddress.replace(":", "").toLong(16)
            val incrementedMac = String.format("%012X", macAsLong + 1)
            incrementedMac.chunked(2).joinToString(":")
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun setupLogFile() {
        val logDir = File(cacheDir, "logs")
        if (!logDir.exists()) logDir.mkdirs()
        logFile = File(logDir, "dfu_stress_test_log.txt")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        logFile.writeText("DFU Stress Test Log - Session started at $timestamp\n\n")
        logMessages.value = ""
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $message\n"
        logMessages.value += logEntry
        try {
            logFile.appendText(logEntry)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun createNotification(contentText: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "DFU Test Channel", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DFU Stress Test")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun updateNotificationProgress(current: Int, total: Int) {
        val notification = createNotification("Running iteration $current of $total...")
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "DfuTestServiceChannel"
    }
}
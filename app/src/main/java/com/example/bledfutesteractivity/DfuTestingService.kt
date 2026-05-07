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
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import kotlin.coroutines.resume
import kotlin.coroutines.coroutineContext

class DfuTestingService : Service() {

    private val binder = DfuBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    val logMessages = MutableStateFlow("")
    val testProgress = MutableStateFlow(0)
    val isTestRunning = MutableStateFlow(false)
    val dfuIterationProgress = MutableStateFlow(0)
    val successCountFlow = MutableStateFlow(0)
    val failCountFlow = MutableStateFlow(0)
    val totalIterationsFlow = MutableStateFlow(0)
    val currentIterationFlow = MutableStateFlow(0)

    private var successCount = 0
    private var failCount = 0
    private var testJob: Job? = null

    private var testDeviceName: String? = null
    private var testDeviceAddress: String = ""
    private var testFirmwareFileName: String = ""

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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

    fun startTest(deviceName: String?, deviceAddress: String, firmwareFile: File, iterations: Int, timeoutSeconds: Long) {
        if (isTestRunning.value) return

        testDeviceName = deviceName
        testDeviceAddress = deviceAddress
        testFirmwareFileName = firmwareFile.name

        testJob = serviceScope.launch {
            try {
                isTestRunning.value = true
                totalIterationsFlow.value = iterations
                successCountFlow.value = 0
                failCountFlow.value = 0
                currentIterationFlow.value = 0
                dfuIterationProgress.value = 0
                setupLogFile()
                runTestLoop(deviceAddress, firmwareFile, iterations, timeoutSeconds)
            } finally {
                isTestRunning.value = false
                currentIterationFlow.value = 0
                dfuIterationProgress.value = 0
                log("Test session ended. Final Score -> Success: $successCount, Fail: $failCount")
                sendTestReport()
                @Suppress("DEPRECATION")
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
        firmwareFile: File,
        iterations: Int,
        timeoutSeconds: Long
    ) {
        var currentDeviceAddress = initialDeviceAddress
        successCount = 0
        failCount = 0

        for (i in 1..iterations) {
            coroutineContext.ensureActive()

            currentIterationFlow.value = i
            dfuIterationProgress.value = 0

            log("--- Starting DFU Iteration ${i}/${iterations} on device ${currentDeviceAddress} ---")
            updateNotificationProgress(i, iterations)

            val dfuResult = performDfuWithTimeout(currentDeviceAddress, firmwareFile, timeoutSeconds)

            if (dfuResult) {
                successCount++
                successCountFlow.value = successCount
                log("DFU Iteration ${i} SUCCESSFUL.")
            } else {
                failCount++
                failCountFlow.value = failCount
                log("DFU Iteration ${i} FAILED.")
            }

            testProgress.value = (i * 100) / iterations

            if (i < iterations) {
                coroutineContext.ensureActive()
                log("Waiting 60 seconds before next scan...")
                delay(60_000)

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

    private suspend fun performDfuWithTimeout(address: String, firmwareFile: File, timeoutSeconds: Long): Boolean {
        repeat(2) { attempt ->
            if (attempt > 0) {
                log("Retrying DFU after GATT error (attempt 2/2)...")
                delay(5_000)
            }
            val outcome = try {
                withTimeout(timeoutSeconds * 1000) { initiateDfu(address, firmwareFile) }
            } catch (e: TimeoutCancellationException) {
                log("DFU timed out after $timeoutSeconds seconds.")
                return false
            } catch (e: Exception) {
                log("An unexpected error occurred during DFU: ${e.message}")
                return false
            }
            when (outcome) {
                true  -> return true
                false -> return false
                null  -> { /* GATT error 133 — loop will retry once */ }
            }
        }
        log("DFU failed after 2 attempts (GATT error 133).")
        return false
    }

    // Returns true = success, false = non-retryable failure, null = GATT error 133 (retryable)
    private suspend fun initiateDfu(address: String, firmwareFile: File): Boolean? =
        suspendCancellableCoroutine { continuation ->
            val progressListener = object : DfuProgressListenerAdapter() {
                override fun onDfuCompleted(deviceAddress: String) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onError(deviceAddress: String, error: Int, errorType: Int, message: String?) {
                    log("DFU Error: $message (Code: $error)")
                    val result: Boolean? = if (error == 133) null else false
                    if (continuation.isActive) continuation.resume(result)
                }

                override fun onDfuAborted(deviceAddress: String) {
                    log("DFU Aborted.")
                    if (continuation.isActive) continuation.resume(false)
                }
                override fun onProgressChanged(deviceAddress: String, percent: Int, speed: Float, avgSpeed: Float, currentPart: Int, partsTotal: Int) {
                    dfuIterationProgress.value = percent
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
                .setRebootTime(2000)       // wait 2 s after disconnect before scanning for bootloader
                .setScanTimeout(15_000)    // scan up to 15 s for the bootloader (default is 5 s)
                .setZip(firmwareFile.absolutePath)

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
                val leScanner = (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter.bluetoothLeScanner
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

                val filters = mutableListOf(ScanFilter.Builder().setDeviceAddress(lastKnownAddress).build())

                val incrementedAddress = getIncrementedMacAddress(lastKnownAddress)
                incrementedAddress?.let {
                    log("Also scanning for incremented address: $it")
                    filters.add(ScanFilter.Builder().setDeviceAddress(it).build())
                }

                val decrementedAddress = getDecrementedMacAddress(lastKnownAddress)
                decrementedAddress?.let {
                    log("Also scanning for decremented address: $it")
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

    private fun getDecrementedMacAddress(macAddress: String): String? {
        return try {
            val macAsLong = macAddress.replace(":", "").toLong(16)
            val decrementedMac = String.format("%012X", macAsLong - 1)
            decrementedMac.chunked(2).joinToString(":")
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun sendTestReport() {
        val logFile = File(File(cacheDir, "logs"), "dfu_stress_test_log.txt")
        val deviceLabel = if (testDeviceName != null) "$testDeviceName ($testDeviceAddress)" else testDeviceAddress
        val subject = "DFU Stress Test Report – $deviceLabel"
        val body = """
            DFU Stress Test completed.

            Device:   $deviceLabel
            Firmware: $testFirmwareFileName

            Results:
              Success: $successCount
              Fail:    $failCount
              Total:   ${successCount + failCount}

            Full log attached.
        """.trimIndent()

        try {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.host", "smtp.gmail.com")
                put("mail.smtp.port", "587")
            }
            val session = Session.getInstance(props, object : javax.mail.Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication("kaimeng@proxxiband.com", "wnpjtnxzostaforb")
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress("kaimeng@proxxiband.com"))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse("kaimeng@proxxiband.com"))
                setSubject(subject)
            }

            val textPart = MimeBodyPart().apply { setText(body) }
            val attachPart = MimeBodyPart().apply {
                dataHandler = DataHandler(FileDataSource(logFile))
                fileName = logFile.name
            }
            message.setContent(MimeMultipart().also {
                it.addBodyPart(textPart)
                if (logFile.exists()) it.addBodyPart(attachPart)
            })

            Transport.send(message)
            log("Test report emailed to kaimeng@proxxiband.com.")
        } catch (e: Exception) {
            log("Failed to send email: ${e.message}")
        }
    }

    private fun setupLogFile() {
        val logDir = File(cacheDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        val logFile = File(logDir, "dfu_stress_test_log.txt")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        logFile.writeText("DFU Stress Test Log - Session started at $timestamp\n\n")
        logMessages.value = ""
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $message\n"
        logMessages.value += logEntry

        // This robust approach ensures the file reference is always valid.
        val logDir = File(cacheDir, "logs")
        val logFile = File(logDir, "dfu_stress_test_log.txt")

        try {
            // Synchronized block to prevent potential (though unlikely) concurrent write issues.
            synchronized(this) {
                logFile.appendText(logEntry)
            }
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
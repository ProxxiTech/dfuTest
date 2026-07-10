@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.bledfutesteractivity

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter
import no.nordicsemi.android.dfu.DfuServiceInitiator
import no.nordicsemi.android.dfu.DfuServiceListenerHelper
import no.nordicsemi.android.kotlin.ble.client.main.callback.ClientBleGatt
import no.nordicsemi.android.kotlin.ble.core.data.BleGattConnectOptions
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
import kotlin.coroutines.coroutineContext

class DfuTestingService : Service() {

    private val binder = DfuBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    val logMessages            = MutableStateFlow("")
    val testProgress           = MutableStateFlow(0)
    val isTestRunning          = MutableStateFlow(false)
    val dfuIterationProgress   = MutableStateFlow(0)
    val dfuStatusText          = MutableStateFlow("Upload: 0%")
    val successCountFlow       = MutableStateFlow(0)
    val failCountFlow          = MutableStateFlow(0)
    val totalIterationsFlow    = MutableStateFlow(0)
    val currentIterationFlow   = MutableStateFlow(0)

    private var successCount = 0
    private var failCount    = 0
    private var testJob: Job? = null

    private var testDeviceName: String?  = null
    private var testDeviceAddress: String = ""
    private var testFirmwareFileName: String = ""

    private lateinit var notificationManager: NotificationManager

    // ─── Service lifecycle ───────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("DFU Stress Test is running…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() { super.onDestroy(); serviceJob.cancel() }

    inner class DfuBinder : Binder() {
        fun getService(): DfuTestingService = this@DfuTestingService
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    fun startTest(
        deviceName: String?,
        deviceAddress: String,
        firmwareFile: File,
        iterations: Int,
        timeoutSeconds: Long,
        delayMinMinutes: Int = 1,
        delayMaxMinutes: Int = 1
    ) {
        if (isTestRunning.value) return

        testDeviceName       = deviceName
        testDeviceAddress    = deviceAddress
        testFirmwareFileName = firmwareFile.name

        testJob = serviceScope.launch {
            try {
                isTestRunning.value         = true
                totalIterationsFlow.value   = iterations
                successCountFlow.value      = 0
                failCountFlow.value         = 0
                currentIterationFlow.value  = 0
                dfuIterationProgress.value  = 0
                setupLogFile()
                runTestLoop(deviceAddress, firmwareFile, iterations, timeoutSeconds, delayMinMinutes, delayMaxMinutes)
            } finally {
                isTestRunning.value        = false
                currentIterationFlow.value = 0
                dfuIterationProgress.value = 0
                log("Test session ended. Final Score → Success: $successCount, Fail: $failCount")
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

    // ─── Test loop ───────────────────────────────────────────────────────────

    private suspend fun runTestLoop(
        initialDeviceAddress: String,
        firmwareFile: File,
        iterations: Int,
        timeoutSeconds: Long,
        delayMinMinutes: Int,
        delayMaxMinutes: Int
    ) {
        var currentDeviceAddress = initialDeviceAddress
        successCount = 0
        failCount    = 0

        for (i in 1..iterations) {
            coroutineContext.ensureActive()

            currentIterationFlow.value = i
            dfuIterationProgress.value = 0
            dfuStatusText.value        = "Upload: 0%"

            log("--- Starting DFU Iteration $i/$iterations on device $currentDeviceAddress ---")
            updateNotificationProgress(i, iterations)

            val dfuResult = performDfuWithTimeout(currentDeviceAddress, firmwareFile, timeoutSeconds)

            if (dfuResult) {
                successCount++
                successCountFlow.value = successCount
                log("DFU Iteration $i SUCCESSFUL.")
            } else {
                failCount++
                failCountFlow.value = failCount
                log("DFU Iteration $i FAILED.")
            }

            testProgress.value = (i * 100) / iterations

            if (i < iterations) {
                coroutineContext.ensureActive()
                val lo = minOf(delayMinMinutes, delayMaxMinutes)
                val hi = maxOf(delayMinMinutes, delayMaxMinutes)
                val waitMinutes = (lo..hi).random()   // random value in the selected window
                if (lo == hi) log("Waiting $waitMinutes minute(s) before next scan…")
                else log("Waiting $waitMinutes minute(s) (random in $lo-$hi) before next scan…")
                val totalWaitMs = waitMinutes * 60_000L
                var elapsed = 0L
                while (elapsed < totalWaitMs) {
                    coroutineContext.ensureActive()
                    val remaining     = totalWaitMs - elapsed
                    val remainingMins = remaining / 60_000
                    val remainingSecs = (remaining % 60_000) / 1_000
                    dfuStatusText.value        = "Next DFU in: ${remainingMins}m ${remainingSecs}s"
                    dfuIterationProgress.value = ((elapsed * 100) / totalWaitMs).toInt()
                    delay(1_000L)
                    elapsed += 1_000L
                }
                dfuIterationProgress.value = 0
                dfuStatusText.value        = "Upload: 0%"

                log("Re-scanning for device (last known address: $currentDeviceAddress)…")
                val foundDevice = findDeviceAfterDfu(currentDeviceAddress)
                if (foundDevice != null) {
                    currentDeviceAddress = foundDevice.address
                    log("Device found at new address: $currentDeviceAddress. Proceeding.")
                } else {
                    log("CRITICAL: Could not find device within 5 minutes. Stopping test.")
                    break
                }
            }
        }
    }

    // ─── DFU via Nordic library ───────────────────────────────────────────────

    private suspend fun performDfuWithTimeout(
        address: String,
        firmwareFile: File,
        timeoutSeconds: Long
    ): Boolean = try {
        withTimeout(timeoutSeconds * 1_000) { initiateDfu(address, firmwareFile) }
    } catch (e: TimeoutCancellationException) {
        log("DFU timed out after $timeoutSeconds seconds.")
        false
    } catch (e: Exception) {
        log("Unexpected error during DFU: ${e.message}")
        false
    }

    /**
     * Drives a single DFU transfer via [DfuServiceInitiator] and suspends until
     * [DfuProgressListenerAdapter] fires onDfuCompleted / onDfuAborted / onError.
     * Must run on the main dispatcher so LocalBroadcastManager delivers callbacks here.
     */
    private suspend fun initiateDfu(address: String, firmwareFile: File): Boolean {
        // ── Pre-DFU: connect with our OWN GATT, read device info, and HOLD the link open ──
        // A second connectGatt from the same app to an already-connected device attaches to the
        // existing ACL link instead of a fresh (flaky) cold connect, so holding this open across
        // DfuServiceInitiator.start() lets the DFU library reuse the link and sidestep the
        // app-mode connection-establishment failures (HCI 0x3E / GATT 133). Best-effort: if the
        // pre-connect fails, we fall back to letting the DFU library connect on its own.
        val (heldGatt, info) = DeviceInfoReader(applicationContext) { msg -> log(msg) }
            .connectReadAndHold(address)
        if (heldGatt != null) log("Pre-DFU device info — ${info.summary()}")
        else log("Pre-DFU own-GATT connect failed; the DFU library will connect on its own.")

        // ── ACL hand-off validation ──────────────────────────────────────────────
        // With the native ACL held open, does the Nordic Kotlin client's connect attach WARM (fast)
        // instead of a cold connect? This proves the pattern for production (which stays on the Kotlin
        // client) without passing a native handle. A warm connect returns in ~ms; a timeout means the
        // hand-off didn't work (and leaks one client — so if it fails, don't run many iterations).
        // Gated by the build flavor: only the "handoff" flavor runs this; "aosp" is the pure baseline.
        if (BuildConfig.ACL_HANDOFF_ENABLED && heldGatt != null) {
            val t0 = System.currentTimeMillis()
            val kotlinGatt = withTimeoutOrNull(8_000L) {
                ClientBleGatt.connect(
                    applicationContext, address, serviceScope,
                    options = BleGattConnectOptions(autoConnect = false),
                )
            }
            if (kotlinGatt != null) {
                log("ACL hand-off OK: ClientBleGatt connected WARM in ${System.currentTimeMillis() - t0} ms (autoConnect=false, native ACL held).")
                runCatching { kotlinGatt.disconnect() }
                runCatching { kotlinGatt.close() }
            } else {
                log("ACL hand-off FAILED: ClientBleGatt did not connect within 8s despite the held ACL.")
            }
        }

        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        fun releaseHeldGatt() {
            if (heldGatt != null && released.compareAndSet(false, true)) {
                runCatching { heldGatt.close() }
            }
        }

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val listener = object : DfuProgressListenerAdapter() {
                    override fun onDeviceConnecting(deviceAddress: String) {
                        log("Connecting to $deviceAddress…")
                        dfuStatusText.value = "Connecting…"
                    }
                    override fun onDeviceConnected(deviceAddress: String) {
                        log("Connected to $deviceAddress")
                        // DFU library has attached to our held ACL — release our GATT now.
                        releaseHeldGatt()
                    }
                    override fun onDfuProcessStarted(deviceAddress: String) {
                        log("DFU process started on $deviceAddress")
                        dfuStatusText.value = "DFU started…"
                    }
                    override fun onEnablingDfuMode(deviceAddress: String) {
                        log("Enabling DFU mode on $deviceAddress…")
                        dfuStatusText.value = "Entering bootloader…"
                    }
                    override fun onFirmwareValidating(deviceAddress: String) {
                        log("Validating firmware…")
                        dfuStatusText.value = "Validating…"
                    }
                    override fun onDeviceDisconnecting(deviceAddress: String?) {
                        log("Disconnecting from ${deviceAddress ?: "device"}…")
                    }
                    override fun onDeviceDisconnected(deviceAddress: String) {
                        log("Disconnected from $deviceAddress")
                    }
                    override fun onProgressChanged(
                        deviceAddress: String, percent: Int,
                        speed: Float, avgSpeed: Float,
                        currentPart: Int, partsTotal: Int
                    ) {
                        dfuIterationProgress.value = percent
                        dfuStatusText.value = "Upload: $percent% (${String.format("%.1f", avgSpeed)} kB/s)"
                    }
                    override fun onDfuCompleted(deviceAddress: String) {
                        log("DFU completed successfully on $deviceAddress")
                        releaseHeldGatt()
                        DfuServiceListenerHelper.unregisterProgressListener(this@DfuTestingService, this)
                        if (cont.isActive) cont.resumeWith(Result.success(true))
                    }
                    override fun onDfuAborted(deviceAddress: String) {
                        log("DFU aborted on $deviceAddress")
                        releaseHeldGatt()
                        DfuServiceListenerHelper.unregisterProgressListener(this@DfuTestingService, this)
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                    override fun onError(deviceAddress: String, error: Int, errorType: Int, message: String?) {
                        log("DFU error on $deviceAddress: $message (code=$error, type=$errorType)")
                        releaseHeldGatt()
                        DfuServiceListenerHelper.unregisterProgressListener(this@DfuTestingService, this)
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                }

                DfuServiceListenerHelper.registerProgressListener(this@DfuTestingService, listener)

                val controller = DfuServiceInitiator(address)
                    .setDeviceName(testDeviceName)
                    .setKeepBond(false)
                    .setForceDfu(false)
                    .setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true)
                    .setNumberOfRetries(3)
                    .setRebootTime(2_000)
                    // Packet Receipt Notifications: the device acks every N data packets, pacing the
                    // transfer and keeping periodic bidirectional traffic on the link. Improves
                    // reliability on marginal links (helps avoid the mid-transfer supervision-timeout
                    // drops seen on the T811). Trade-off: slightly slower upload. Tune the value if needed.
                    .setPacketsReceiptNotificationsEnabled(true)
                    .setPacketsReceiptNotificationsValue(12)
                    .setZip(firmwareFile.absolutePath)
                    .start(this@DfuTestingService, DfuService::class.java)

                cont.invokeOnCancellation {
                    releaseHeldGatt()
                    DfuServiceListenerHelper.unregisterProgressListener(this@DfuTestingService, listener)
                    controller.abort()
                }
            }
        }
    }

    // ─── Inter-iteration device re-scan ──────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun findDeviceAfterDfu(lastKnownAddress: String): BluetoothDevice? {
        return withTimeoutOrNull(5 * 60 * 1_000) {
            suspendCancellableCoroutine { continuation ->
                val leScanner = (getSystemService(Context.BLUETOOTH_SERVICE)
                        as android.bluetooth.BluetoothManager).adapter.bluetoothLeScanner
                var deviceFound        = false
                var loggedNonConnectable = false

                val scanCallback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val connectable = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                                || result.isConnectable
                        if (!connectable) {
                            if (!loggedNonConnectable) {
                                loggedNonConnectable = true
                                log("Device found at ${result.device.address} but not connectable. Waiting…")
                            }
                            return
                        }
                        if (!deviceFound) {
                            deviceFound = true
                            leScanner.stopScan(this)
                            if (continuation.isActive) continuation.resume(result.device, onCancellation = null)
                        }
                    }
                    override fun onScanFailed(errorCode: Int) {
                        log("Re-scan failed with error code: $errorCode")
                        if (continuation.isActive) continuation.resume(null, onCancellation = null)
                    }
                }

                val filters = mutableListOf(
                    ScanFilter.Builder().setDeviceAddress(lastKnownAddress).build()
                )
                macIncrement(lastKnownAddress, +1)?.let {
                    log("Also scanning for incremented address: $it")
                    filters.add(ScanFilter.Builder().setDeviceAddress(it).build())
                }
                macIncrement(lastKnownAddress, -1)?.let {
                    log("Also scanning for decremented address: $it")
                    filters.add(ScanFilter.Builder().setDeviceAddress(it).build())
                }

                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                leScanner.startScan(filters, settings, scanCallback)
                continuation.invokeOnCancellation { leScanner.stopScan(scanCallback) }
            }
        }
    }

    private fun macIncrement(mac: String, delta: Int): String? = try {
        val v = mac.replace(":", "").toLong(16) + delta
        String.format("%012X", v).chunked(2).joinToString(":")
    } catch (_: Exception) { null }

    // ─── Email report ─────────────────────────────────────────────────────────

    private fun sendTestReport() {
        val logFile     = File(File(cacheDir, "logs"), "dfu_stress_test_log.txt")
        val deviceLabel = if (testDeviceName != null) "$testDeviceName ($testDeviceAddress)" else testDeviceAddress
        val subject     = "DFU Stress Test Report – $deviceLabel"
        val body        = """
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
                put("mail.smtp.auth",            "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.host",            "smtp.gmail.com")
                put("mail.smtp.port",            "587")
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

            val textPart   = MimeBodyPart().apply { setText(body) }
            val attachPart = MimeBodyPart().apply {
                dataHandler = DataHandler(FileDataSource(logFile))
                fileName    = logFile.name
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

    // ─── Logging ─────────────────────────────────────────────────────────────

    private fun setupLogFile() {
        val logDir = File(cacheDir, "logs")
        if (!logDir.exists()) logDir.mkdirs()
        val logFile   = File(logDir, "dfu_stress_test_log.txt")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        logFile.writeText("DFU Stress Test Log - Session started at $timestamp\n\n")
        logMessages.value = ""
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logEntry  = "[$timestamp] $message\n"
        logMessages.value += logEntry
        val logFile = File(File(cacheDir, "logs"), "dfu_stress_test_log.txt")
        try {
            synchronized(this) { logFile.appendText(logEntry) }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    // ─── Notification ────────────────────────────────────────────────────────

    private fun createNotification(contentText: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "DFU Test Channel", NotificationManager.IMPORTANCE_LOW)
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
        val notification = createNotification("Running iteration $current of $total…")
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID      = "DfuTestServiceChannel"
    }
}

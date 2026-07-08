package com.example.bledfutesteractivity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Device info read from the band's standard Battery + Device Information services. */
data class DeviceInfo(
    var batteryPercent: Int? = null,
    var firmwareRevision: String? = null,   // DIS 0x2A26
    var hardwareRevision: String? = null,   // DIS 0x2A27
    var softwareRevision: String? = null,   // DIS 0x2A28 (bootloader version on this band)
    var manufacturer: String? = null,       // DIS 0x2A29
    var modelNumber: String? = null,        // DIS 0x2A24
    var serialNumber: String? = null,       // DIS 0x2A25
) {
    fun summary(): String = buildString {
        append("Battery ${batteryPercent?.let { "$it%" } ?: "?"}")
        append(" | FW ${firmwareRevision ?: "?"}")
        append(" | HW ${hardwareRevision ?: "?"}")
        append(" | SW/BL ${softwareRevision ?: "?"}")
        serialNumber?.let { append(" | SN $it") }
        modelNumber?.let { append(" | Model $it") }
    }
}

/**
 * Opens the app's OWN GATT to a device, reads Battery Level + Device Information Service strings,
 * and returns the STILL-CONNECTED [BluetoothGatt] so the caller can hold the ACL link open while
 * the Nordic DFU library attaches to the same device.
 *
 * Rationale: on Android, a second `connectGatt` from the same app to an already-connected device
 * attaches to the existing ACL link instead of performing a fresh (flaky) LE create-connection.
 * Holding this connection open across `DfuServiceInitiator.start()` lets the DFU library's connect
 * reuse the link, sidestepping the cold-connect establishment failures (HCI 0x3E / GATT 133) seen
 * on the app-mode connect.
 *
 * IMPORTANT: the caller MUST [BluetoothGatt.close] the returned gatt once the DFU library has
 * attached (onDeviceConnected) or on any terminal outcome, otherwise the client interface leaks.
 */
class DeviceInfoReader(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    /**
     * Connect (with fast-retry), read info, and return the open gatt + collected info.
     * Returns a null gatt if all connection attempts fail (info may be partially populated).
     */
    @SuppressLint("MissingPermission")
    suspend fun connectReadAndHold(address: String): Pair<BluetoothGatt?, DeviceInfo> {
        val info = DeviceInfo()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return null to info
        val device = adapter.getRemoteDevice(address)

        for (attempt in 1..MAX_ATTEMPTS) {
            // Last attempt is a patient autoConnect=true attempt: no create-connection timeout, so it
            // rides out the band's ~10s non-connectable iBeacon window and connects when it returns.
            val patient = attempt > FAST_ATTEMPTS
            val gatt = connectAndReadOnce(device, attempt, patient, info)
            if (gatt != null) return gatt to info
        }
        log("Pre-DFU own-GATT connect: exhausted $MAX_ATTEMPTS attempts.")
        return null to info
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAndReadOnce(
        device: BluetoothDevice,
        attempt: Int,
        patient: Boolean,
        info: DeviceInfo,
    ): BluetoothGatt? = withTimeoutOrNull((if (patient) PATIENT_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS) + READ_BUDGET_MS) {
        suspendCancellableCoroutine { cont ->
            var gattRef: BluetoothGatt? = null
            val queue = ArrayDeque<Pair<UUID, UUID>>()
            // All read/discovery bookkeeping runs on this handler so the shared state below is only
            // ever touched from one thread (GATT callbacks arrive on a binder thread).
            val handler = Handler(Looper.getMainLooper())
            val finished = AtomicBoolean(false)
            var current: Pair<UUID, UUID>? = null   // characteristic being read
            var readAttempt = 0
            var discoverAttempt = 0

            fun done(result: BluetoothGatt?) {
                if (finished.compareAndSet(false, true)) {
                    handler.removeCallbacksAndMessages(null)
                    if (cont.isActive) cont.resumeWith(Result.success(result))
                }
            }

            // Read `current`, retrying up to MAX_READ_ATTEMPTS with a delay, then advance to the next
            // characteristic. When the queue drains, finish and KEEP the gatt open. Main thread only.
            fun pump(g: BluetoothGatt) {
                val target = current ?: run { done(g); return }
                val ch = g.getService(target.first)?.getCharacteristic(target.second)
                if (ch == null) { current = queue.removeFirstOrNull(); readAttempt = 0; pump(g); return }
                readAttempt++
                if (!g.readCharacteristic(ch)) {
                    if (readAttempt < MAX_READ_ATTEMPTS) {
                        handler.postDelayed({ pump(g) }, READ_RETRY_DELAY_MS)
                    } else {
                        current = queue.removeFirstOrNull(); readAttempt = 0; pump(g)
                    }
                }
                // else: wait for onCharacteristicRead → onReadResult
            }

            fun onReadResult(g: BluetoothGatt, uuid: UUID, value: ByteArray?, status: Int) {
                handler.post {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        store(uuid, value, info)
                        current = queue.removeFirstOrNull(); readAttempt = 0
                        pump(g)
                    } else if (readAttempt < MAX_READ_ATTEMPTS) {
                        handler.postDelayed({ pump(g) }, READ_RETRY_DELAY_MS)  // retry the same characteristic
                    } else {
                        log("Pre-DFU read $uuid failed after $MAX_READ_ATTEMPTS tries; skipping.")
                        current = queue.removeFirstOrNull(); readAttempt = 0
                        pump(g)
                    }
                }
            }

            fun doDiscover(g: BluetoothGatt) {
                discoverAttempt++
                if (!g.discoverServices() && discoverAttempt < MAX_DISCOVER_ATTEMPTS) {
                    handler.postDelayed({ doDiscover(g) }, READ_RETRY_DELAY_MS)
                }
            }

            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        log("Pre-DFU connect attempt $attempt failed (status=$status).")
                        g.close()
                        done(null)
                        return
                    }
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            log("Pre-DFU connect attempt $attempt (${if (patient) "patient" else "fast"}): connected, discovering services…")
                            discoverAttempt = 0
                            handler.post { doDiscover(g) }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            g.close()
                            done(null)
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        if (discoverAttempt < MAX_DISCOVER_ATTEMPTS) {
                            log("Pre-DFU service discovery failed (status=$status); retrying…")
                            handler.postDelayed({ doDiscover(g) }, READ_RETRY_DELAY_MS)
                        } else {
                            log("Pre-DFU service discovery failed (status=$status); giving up.")
                            done(g) // hand back the open connection anyway
                        }
                        return
                    }
                    handler.post {
                        queue.clear()
                        for (pair in READ_ORDER) {
                            if (g.getService(pair.first)?.getCharacteristic(pair.second) != null) queue.add(pair)
                        }
                        current = queue.removeFirstOrNull(); readAttempt = 0
                        pump(g)
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                    onReadResult(g, ch.uuid, ch.value, status)
                }

                override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                    onReadResult(g, ch.uuid, value, status)
                }
            }

            gattRef = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                device.connectGatt(context, patient, cb, BluetoothDevice.TRANSPORT_LE)
            else
                device.connectGatt(context, patient, cb)

            cont.invokeOnCancellation { handler.removeCallbacksAndMessages(null); runCatching { gattRef?.disconnect(); gattRef?.close() } }
        }
    }

    private fun store(uuid: UUID, value: ByteArray?, info: DeviceInfo) {
        if (value == null || value.isEmpty()) return
        fun str() = value.toString(Charsets.UTF_8).trimEnd(' ').trim()
        when (uuid) {
            BATTERY_LEVEL -> info.batteryPercent = value[0].toInt() and 0xFF
            FW_REV -> info.firmwareRevision = str()
            HW_REV -> info.hardwareRevision = str()
            SW_REV -> info.softwareRevision = str()
            MANUFACTURER -> info.manufacturer = str()
            MODEL_NUMBER -> info.modelNumber = str()
            SERIAL_NUMBER -> info.serialNumber = str()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 6_000L          // fast autoConnect=false attempt
        private const val PATIENT_CONNECT_TIMEOUT_MS = 20_000L // final autoConnect=true attempt (> 10s iBeacon window)
        private const val READ_BUDGET_MS = 12_000L             // room for read/discovery retries below
        private const val FAST_ATTEMPTS = 3                    // fast autoConnect=false attempts
        private const val MAX_ATTEMPTS = FAST_ATTEMPTS + 1     // + 1 patient autoConnect=true fallback

        private const val MAX_READ_ATTEMPTS = 3                // tries per characteristic before skipping
        private const val MAX_DISCOVER_ATTEMPTS = 2            // service-discovery tries
        private const val READ_RETRY_DELAY_MS = 300L           // delay between read/discovery retries

        private fun uuid16(v: String): UUID = UUID.fromString("0000$v-0000-1000-8000-00805f9b34fb")

        private val BATTERY_SERVICE = uuid16("180f")
        private val BATTERY_LEVEL = uuid16("2a19")
        private val DIS_SERVICE = uuid16("180a")
        private val FW_REV = uuid16("2a26")
        private val HW_REV = uuid16("2a27")
        private val SW_REV = uuid16("2a28")
        private val MANUFACTURER = uuid16("2a29")
        private val MODEL_NUMBER = uuid16("2a24")
        private val SERIAL_NUMBER = uuid16("2a25")

        /** Read order (service, characteristic). */
        private val READ_ORDER = listOf(
            BATTERY_SERVICE to BATTERY_LEVEL,
            DIS_SERVICE to FW_REV,
            DIS_SERVICE to HW_REV,
            DIS_SERVICE to SW_REV,
            DIS_SERVICE to MANUFACTURER,
            DIS_SERVICE to MODEL_NUMBER,
            DIS_SERVICE to SERIAL_NUMBER,
        )
    }
}

package com.example.bledfutesteractivity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import no.nordicsemi.android.kotlin.ble.client.main.callback.ClientBleGatt
import no.nordicsemi.android.kotlin.ble.core.RealServerDevice
import no.nordicsemi.android.kotlin.ble.core.data.BleGattConnectOptions
import no.nordicsemi.android.kotlin.ble.core.data.BleGattConnectionPriority
import no.nordicsemi.android.kotlin.ble.core.data.BleGattPhy
import no.nordicsemi.android.kotlin.ble.core.data.GattConnectionState
import no.nordicsemi.android.kotlin.ble.core.data.PhyOption
import java.util.UUID

/**
 * Production-faithful "connect → read firmware revision" step for the end-to-end DFU mimic. Mirrors
 * band-mobile-android's `BLEConnectionManager` at v3.1.0 (verified against the real source), in TWO
 * modes:
 *
 *  - improved = false → **PRODUCTION**: exactly what the shipped app does today. Includes v3.1.0's
 *    hardened teardown ([closeGattQuietly]) and `connectionStateWithStatus` observer, BUT keeps the
 *    behaviour that still leaks on the foreground/DFU path: `connect()` assigns `currentGatt` WITHOUT
 *    closing the previous handle (BLEConnectionManager:131). The v3.1.0 "ghost connections" fix only
 *    disabled auto-reconnect for *background sync*; the foreground DFU path still auto-reconnects and
 *    overwrites, so this mode still climbs gatt_if → "No resources"/status-128 under the stress loop.
 *    On a connect FAILURE it also fires the DUAL reconnect loops the ble-connection-review flags as
 *    the highest-probability cause: the manager's [handleReconnection] (1s) AND a BLEService-style
 *    [handleServiceReconnection] (2s, unguarded) race and overlap connect(), orphaning handles faster.
 *
 *  - improved = true → **MOST-IMPROVED**: the ideal beyond production. A robust connect that rides
 *    out the band's ~10s non-connectable iBeacon window (fast direct attempts + a patient
 *    autoConnect=true fallback) and VERIFIES the link (service discovery + FW read) BEFORE handing it
 *    to the DFU — so the DFU always starts from a known-good, established connection rather than
 *    waiting the window out itself. Per-attempt child scopes (leak-safe), skips the risky 2M-PHY
 *    tuning, and hands the warm ACL to the DFU on attach ([releaseConnection]).
 *
 * Faithfully reproduced from the real manager either way: connect via
 * `ClientBleGatt.connect(ctx, RealServerDevice(device), scope)`; `postConnectTuning`
 * (requestConnectionPriority(HIGH) + requestMtu(517) + setPhy(2M)); a single `connectionStateWithStatus`
 * observer that auto-reconnects on disconnect (1s, or 15s right after a firmware update); the canonical
 * suspend `closeGattQuietly` (skip-disconnect-if-already-DISCONNECTED → await STATE_DISCONNECTED (≤800ms)
 * → refreshGattCache → close); firmware read over the SAME connection (DIS 0x180A → 0x2A26 → read().value);
 * and `disableReconnection`/`enableReconnection`/`markFirmwareUpdateCompleted` gated around the DFU as
 * FirmwareUpdateManager does.
 */
class ProductionDfuConnector(
    private val context: Context,
    private val improved: Boolean,
    private val parentJob: Job,
    private val log: (String) -> Unit,
    // Reports each connect attempt to the progress grid: (row 0-based, DfuGridView.CELL_* state).
    private val onAttempt: (row: Int, state: Int) -> Unit = { _, _ -> },
) {
    private val scope = CoroutineScope(SupervisorJob(parentJob) + Dispatchers.IO)

    private var currentGatt: ClientBleGatt? = null
    private var gattStateJob: Job? = null
    private var connectedDevice: BluetoothDevice? = null
    // improved: the child scope that owns the currently-held link (kept alive across the DFU handoff,
    // cancelled at the next connect or at shutdown).
    private var liveConnectScope: CoroutineScope? = null

    @Volatile private var shouldReconnect = true
    @Volatile private var firmwareUpdateJustCompleted = false

    // Concurrent reconnect coroutines currently alive (both authorities). Production has NO such
    // guard — the review's whole point (#1). This cap is a TESTER-ONLY safety valve so a wedged /
    // absent device can't fan the two unguarded loops out into unbounded coroutines and OOM the app;
    // up to the cap still overlaps enough to orphan handles and demonstrate the race.
    private val reconnectsInFlight = AtomicInteger(0)

    /** Connect the production way and read the firmware revision. Returns the FW string, or null. */
    @SuppressLint("MissingPermission")
    suspend fun connectAndReadFirmware(address: String): String? {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        connectedDevice = adapter.getRemoteDevice(address)
        shouldReconnect = true
        return if (improved) improvedConnectAndRead(connectedDevice!!)
               else withTimeoutOrNull(PROD_CONNECT_MS) { connectAndRead(connectedDevice!!) }
    }

    // PRODUCTION single-shot connect (faithful to BLEConnectionManager.connect): one
    // ClientBleGatt.connect on the shared scope, autoConnect=false, capped at PROD_CONNECT_MS by the
    // caller. NO retry, NO patient fallback — so when the band is in its iBeacon window this simply
    // fails and the DFU is left to connect on its own. On failure it fires the dual reconnect loops.
    @SuppressLint("MissingPermission")
    private suspend fun connectAndRead(device: BluetoothDevice): String? {
        onAttempt(0, DfuGridView.CELL_RUNNING)
        return try {
            val g = ClientBleGatt.connect(context, RealServerDevice(device), scope)
            currentGatt = g                    // assigned only AFTER connect (prod :131); no close-before-overwrite → leaks
            observeGattConnectionState(g)      // prod :133
            val fw = readFirmwareRevision(g)   // discover + read 0x2A26
            postConnectTuning(g)               // prod :139 — HIGH priority + MTU + forced PHY 2M
            onAttempt(0, DfuGridView.CELL_SUCCESS)
            log("    prod-connect: CONNECTED, FW=${fw ?: "?"} (production)")
            fw
        } catch (e: CancellationException) {
            gattStateJob?.cancel(); closeGattQuietly(); throw e
        } catch (e: Exception) {
            log("    prod-connect: connect FAILED '${e.message}'")
            onAttempt(0, DfuGridView.CELL_ATTEMPT_FAILED)
            gattStateJob?.cancel(); closeGattQuietly()
            // review #1: a single failure spawns TWO independent, unguarded reconnect loops — the
            // manager's handleReconnection (1s) AND BLEService.attemptReconnection (2s) — that race
            // and overlap connect(), orphaning handles (the highest-probability 133 / GATT-slot leak).
            handleReconnection()          // authority #1: manager
            handleServiceReconnection()   // authority #2: BLEService-style, unguarded
            null
        }
    }

    /**
     * MOST-IMPROVED connect: establish AND verify a working link before the DFU takes over, riding
     * out the band's ~10s non-connectable iBeacon window — the reliability the single-shot production
     * connect lacks. Fast direct attempts first (autoConnect=false, quick when the band is
     * connectable), then a patient autoConnect=true attempt (no create-connection timeout — connects
     * the moment the band becomes connectable). Each attempt runs on its OWN child scope so a
     * timed-out attempt tears down with no leak; the successful attempt's scope is kept
     * ([liveConnectScope]) so the warm link survives until the DFU handoff. Returning (even FW=null)
     * means the link connected and services were discoverable = verified working. NO postConnectTuning
     * (no risky 2M PHY), NO auto-reconnect — a deliberately controlled, single-owner connect.
     */
    @SuppressLint("MissingPermission")
    private suspend fun improvedConnectAndRead(device: BluetoothDevice): String? {
        // Drop any scope/handle left over from the previous iteration's handoff.
        liveConnectScope?.cancel(); liveConnectScope = null
        closeGattQuietly()

        for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
            val row = attempt - 1
            onAttempt(row, DfuGridView.CELL_RUNNING)
            val patient = attempt > FAST_CONNECT_ATTEMPTS
            val budget  = if (patient) PATIENT_CONNECT_MS else FAST_CONNECT_MS
            val cs = CoroutineScope(SupervisorJob(parentJob) + Dispatchers.IO)
            var connected = false
            try {
                val fw = withTimeoutOrNull(budget) {
                    val g = ClientBleGatt.connect(
                        context, RealServerDevice(device), cs,
                        options = BleGattConnectOptions(autoConnect = patient),
                    )
                    currentGatt = g
                    connected = true
                    readFirmwareRevision(g)   // discover + read 0x2A26 — proves the link works
                }
                if (connected && currentGatt != null) {
                    liveConnectScope = cs      // keep the live link (and its scope) for the DFU handoff
                    onAttempt(row, DfuGridView.CELL_SUCCESS)
                    log("    prod-connect: link established & verified (attempt $attempt/$MAX_CONNECT_ATTEMPTS, ${if (patient) "patient·autoConnect" else "fast·direct"}), FW=${fw ?: "?"}")
                    return fw
                }
            } catch (e: CancellationException) {
                cs.cancel(); currentGatt = null; throw e
            } catch (e: Exception) {
                log("    prod-connect: attempt $attempt error: ${e.message}")
            }
            // Did not establish within the budget → cancel this attempt's scope (leak-safe) and escalate.
            cs.cancel()
            currentGatt = null
            onAttempt(row, DfuGridView.CELL_ATTEMPT_FAILED)
            log("    prod-connect: attempt $attempt/$MAX_CONNECT_ATTEMPTS (${if (patient) "patient" else "fast"}) did not establish; escalating")
        }
        log("    prod-connect: all $MAX_CONNECT_ATTEMPTS attempts failed to establish a working link")
        return null
    }

    // prod :423 — one observer at a time; collects connectionStateWithStatus so the disconnect reason
    // is captured; on an unexpected disconnect (reconnect enabled) it auto-reconnects.
    private fun observeGattConnectionState(g: ClientBleGatt) {
        gattStateJob?.cancel()
        gattStateJob = scope.launch {
            g.connectionStateWithStatus.collect { sws ->
                if (sws?.state == GattConnectionState.STATE_DISCONNECTED) {
                    log("    prod-connect: DISCONNECTED status=${sws.status.name} (auto-reconnect ${if (shouldReconnect) "on" else "off"})")
                    if (shouldReconnect) handleReconnection()
                }
            }
        }
    }

    // Authority #1 — prod BLEConnectionManager.handleReconnection (:469): reconnect after 1s, or 15s
    // immediately after a firmware update.
    private fun handleReconnection() {
        if (!admitReconnect("manager")) return
        scope.launch {
            try {
                if (!shouldReconnect) return@launch
                val device = connectedDevice ?: return@launch
                val delayMs = if (firmwareUpdateJustCompleted) {
                    firmwareUpdateJustCompleted = false
                    15_000L
                } else 1_000L
                log("    prod-connect: manager auto-reconnect in ${delayMs}ms")
                delay(delayMs)
                if (shouldReconnect) connectAndRead(device)   // PRODUCTION: overwrites currentGatt → orphans the old one
            } finally {
                reconnectsInFlight.decrementAndGet()
            }
        }
    }

    // Authority #2 — prod BLEService.attemptReconnection (:1720): a SECOND, independent reconnect
    // loop with a 2s delay and NO in-flight guard, racing authority #1. This is the dual-loop the
    // review calls the highest-probability cause of the 133 / GATT-slot leak.
    private fun handleServiceReconnection() {
        if (!admitReconnect("BLEService")) return
        scope.launch {
            try {
                if (!shouldReconnect) return@launch
                val device = connectedDevice ?: return@launch
                log("    prod-connect: BLEService-style reconnect in 2000ms (2nd authority, unguarded)")
                delay(2_000L)
                if (shouldReconnect) connectAndRead(device)
            } finally {
                reconnectsInFlight.decrementAndGet()
            }
        }
    }

    /** Tester-only cap admission (see [reconnectsInFlight]); returns false when at the cap. */
    private fun admitReconnect(which: String): Boolean {
        if (reconnectsInFlight.incrementAndGet() > MAX_INFLIGHT_RECONNECTS) {
            reconnectsInFlight.decrementAndGet()
            log("    prod-connect: [tester cap] $which reconnect skipped ($MAX_INFLIGHT_RECONNECTS already in flight)")
            return false
        }
        return true
    }

    // prod :410 — HIGH priority + MTU 517 + PHY 2M after discovery, fire-and-forget on the scope.
    private fun postConnectTuning(g: ClientBleGatt) {
        scope.launch { runCatching { g.requestConnectionPriority(BleGattConnectionPriority.HIGH) } }
        // Legacy stacks (<= API 30) stall on immediate MTU/PHY changes; prod skips them there.
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
            scope.launch {
                runCatching { delay(150); g.requestMtu(517) }.onSuccess { log("    prod-connect: MTU 517 requested") }
            }
            scope.launch {
                runCatching { g.setPhy(BleGattPhy.PHY_LE_2M, BleGattPhy.PHY_LE_2M, PhyOption.NO_PREFERRED) }
                    .onSuccess { log("    prod-connect: PHY 2M requested") }
            }
        }
    }

    private suspend fun readFirmwareRevision(g: ClientBleGatt): String? {
        val services = g.discoverServices()
        val ch = services.findService(DIS_SERVICE)?.findCharacteristic(FW_REVISION)
        if (ch == null) {
            log("    prod-connect: Firmware Revision characteristic (0x2A26) not found")
            return null
        }
        return runCatching { ch.read().value.toString(Charsets.UTF_8).trim() }
            .onFailure { log("    prod-connect: FW read failed: ${it.message}") }
            .getOrNull()
    }

    /** Gate auto-reconnection around a DFU, mirroring FirmwareUpdateManager. */
    fun disableReconnection() { shouldReconnect = false }
    fun enableReconnection() { shouldReconnect = true }
    fun markFirmwareUpdateCompleted() { firmwareUpdateJustCompleted = true }

    /**
     * Hand the warm ACL over to the DFU library: close ONLY this client interface — no disconnect(),
     * so the ACL stays up for the DFU's own gatt to keep using. Mirrors the proven Start Test
     * `releaseHeldGatt` handoff (close-only on the DFU's onDeviceConnected). Used by the improved
     * variant so the DFU owns the link alone during the delicate indication-enable phase; production
     * deliberately does NOT release (holds two clients open — the failure this fixes).
     */
    fun releaseConnection() {
        val g = currentGatt ?: return
        currentGatt = null
        gattStateJob?.cancel()
        runCatching { g.close() }
        log("    prod-connect: released connector client — DFU now owns the link")
    }

    /**
     * v3.1.0 canonical teardown (BLEConnectionManager:520) — the hardened close is shipped production,
     * so both modes share it: skip disconnect() if already DISCONNECTED (avoids the Nordic/Samsung
     * disconnect/close race and the StateFlow regression), else disconnect + await STATE_DISCONNECTED
     * (≤800ms) → clear GATT cache → close (capturing a close() throw as a leaked slot). The ONLY
     * difference is the cache-clear: see [clearGattCache] (review #3).
     */
    private suspend fun closeGattQuietly() {
        val g = currentGatt ?: return
        val alreadyDisconnected =
            g.connectionStateWithStatus.value?.state == GattConnectionState.STATE_DISCONNECTED
        if (!alreadyDisconnected) {
            runCatching { g.disconnect() }
            runCatching {
                withTimeoutOrNull(DISCONNECT_WAIT_MS) {
                    g.connectionStateWithStatus.first { it?.state == GattConnectionState.STATE_DISCONNECTED }
                }
            }
        }
        clearGattCache(g)
        try {
            g.close()
        } catch (t: Throwable) {
            log("    prod-connect: close() threw — GATT slot may leak: ${t.message}")
        }
        currentGatt = null
    }

    /**
     * Clear Android's GATT service cache before close (matters for re-reading services after a DFU
     * changes the GATT table). Review #3: production's `getMethod("refresh")` reflection
     * (BLEConnectionManager:198) targets Nordic's ClientBleGatt wrapper, which has NO `refresh`
     * method (only `clearServicesCache()` — verified against client-1.1.0.aar), so it always throws
     * and is a no-op.
     *  - PRODUCTION: replays that broken reflection faithfully (stays a no-op, like shipped).
     *  - MOST-IMPROVED: calls the real `clearServicesCache()` — the fix.
     */
    private fun clearGattCache(g: ClientBleGatt) {
        if (improved) {
            runCatching { g.clearServicesCache() }
                .onFailure { log("    prod-connect: clearServicesCache() failed: ${it.message}") }
        } else {
            // Verified no-op — faithful to production's non-working reflection.
            runCatching { g.javaClass.getMethod("refresh").invoke(g) }
        }
    }

    /**
     * Session-end teardown. Both modes stop reconnecting and close the live handle (v3.1.0
     * disconnect()). MOST-IMPROVED also cancels the scope so nothing lingers; PRODUCTION leaves the
     * scope so the clients it leaked mid-session persist — the reproduction — until the service job
     * (this scope's parent) is cancelled as a backstop.
     */
    suspend fun shutdown() = withContext(NonCancellable) {
        shouldReconnect = false
        gattStateJob?.cancel()
        closeGattQuietly()
        liveConnectScope?.cancel(); liveConnectScope = null
        if (improved) scope.cancel()
    }

    companion object {
        private const val DISCONNECT_WAIT_MS = 800L   // prod DISCONNECT_WAIT_MS
        private const val MAX_INFLIGHT_RECONNECTS = 6 // tester-only safety valve (prod has none)
        private const val PROD_CONNECT_MS = 10_000L   // production single-shot connect cap (as WristbandConnectionViewModel)
        // improved robust connect: 3 fast direct attempts then 1 patient autoConnect=true attempt
        // (> the band's ~10s iBeacon window), mirroring DeviceInfoReader's proven pattern.
        private const val FAST_CONNECT_ATTEMPTS = 3
        private const val MAX_CONNECT_ATTEMPTS = 4
        private const val FAST_CONNECT_MS = 7_000L    // autoConnect=false direct attempt
        private const val PATIENT_CONNECT_MS = 22_000L // autoConnect=true — rides out the iBeacon window
        private fun uuid16(v: String): UUID = UUID.fromString("0000$v-0000-1000-8000-00805f9b34fb")
        private val DIS_SERVICE = uuid16("180a")   // Device Information Service
        private val FW_REVISION = uuid16("2a26")   // Firmware Revision String
    }
}

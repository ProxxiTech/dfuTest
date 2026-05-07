# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected/emulated device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented (on-device) tests
./gradlew connectedAndroidTest
```

The app must be tested on a real Android device with Bluetooth — an emulator cannot run BLE.

## Architecture Overview

Single-module Android app (Kotlin, View-based UI, minSdk 24, targetSdk 35) that performs automated BLE DFU (Device Firmware Update) stress testing using Nordic Semiconductor's DFU library.

### Component Roles

**`MainActivity`** — The only Activity. Handles permissions, BLE adapter enable flow, file picking (ZIP firmware), and binds to `DfuTestingService` on `onStart`/`onStop`. UI state is driven by StateFlows collected from `MainViewModel`.

**`MainViewModel`** — `AndroidViewModel` that owns BLE scanning (15-second scan using `BluetoothLeScanner`). When the service binds, it collects the service's three StateFlows (`logMessages`, `testProgress`, `isTestRunning`) and re-exposes them to the UI. Acts as a bridge between the service and the Activity.

**`DfuTestingService`** — Foreground `Service` (`connectedDevice` type) that owns the entire test lifecycle. Runs a coroutine loop across N DFU iterations. Between iterations it: waits 30 seconds, then scans for the device by MAC address ±1 (Nordic devices commonly shift address after DFU). Has a 5-minute timeout per re-scan. Exposes `MutableStateFlow` for log, progress, and running state.

**`DfuService`** — Thin subclass of Nordic's `DfuBaseService`. Required by the Nordic DFU library; `DfuTestingService` starts it for each actual DFU operation. Debug logging is enabled (`isDebug = true`).

**`DeviceScanAdapter`** — `RecyclerView.Adapter` for the 2-column grid of scanned BLE devices. Maintains selection highlight state internally.

### Key Flows

1. App starts → requests permissions → starts 15s BLE scan → user picks a device and a firmware ZIP
2. User taps "Start Test" → `MainActivity` starts `DfuTestingService` as a foreground service and calls `service.startTest(address, uri, iterations, timeout)`
3. `DfuTestingService` runs the iteration loop: calls `initiateDfu()` (suspending, via Nordic `DfuServiceInitiator` + `DfuProgressListenerAdapter`) → logs result → re-scans → repeats
4. Log is written incrementally to `cacheDir/logs/dfu_stress_test_log.txt` and shared via `FileProvider` with authority `${applicationId}.provider`

### MAC Address Drift

After DFU completes, Nordic BLE devices may advertise with a MAC address offset by ±1. `DfuTestingService.findDeviceAfterDfu()` explicitly builds scan filters for `lastAddress`, `lastAddress+1`, and `lastAddress-1` to handle this.

### State Management

All async state uses `kotlinx.coroutines` with `MutableStateFlow`. The Activity collects flows in `repeatOnLifecycle(STARTED)` coroutines. The service runs on `Dispatchers.IO` via a `SupervisorJob`-backed scope that is cancelled in `onDestroy`.

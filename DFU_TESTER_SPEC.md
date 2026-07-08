# BLE DFU Stress Tester — App Specification

## Purpose

Automates repeated Nordic DFU (Device Firmware Update) cycles over BLE to stress-test firmware update reliability. The user selects a target BLE device and a firmware ZIP file, configures the number of iterations and a per-iteration timeout, then starts the test. The app runs unattended, logs every event, and emails a report when done.

---

## UI Layout (single screen, top to bottom)

### Top bar
| Element | Behaviour |
|---|---|
| **Label** ("Nearby Devices" / "Device: Name (MAC)") | Collapses to show selected device after selection |
| **Reset BT** button | Resets the Android BLE stack (see BLE Stack Reset) |
| **Change / Rescan** button | Expands device list and starts a new scan |

### Device list (collapsible)
- 2-column grid of scanned BLE devices showing device name and MAC address
- Visible during scanning; collapses to a single label line once a device is selected
- Tapping a device selects it, stops the scan, and collapses the section
- Only named devices (devices that broadcast a local name) are shown

### Firmware file picker
- **"Select DFU File" / "Change File"** button — opens system file picker filtered to `.zip`
- Selected filename displayed inline to the right of the button
- Section stays visible at all times (file can be changed between tests)

### Configuration fields (two columns)
| Field | Default | Description |
|---|---|---|
| Iterations | 10 | Number of DFU update cycles to run |
| Timeout (s) | 120 | Max seconds to allow for a single DFU attempt before declaring it timed out |

### Start / Stop button
- Full-width filled button
- Disabled until both a device and a firmware file are selected
- Toggles between "Start Test" and "Stop Test" while a test is running
- All config controls (device, file, iterations, timeout) are disabled while a test is running

### Stats section (visible once test starts)
Left side:
- **"Iteration: X / Y"** label
- **Overall progress bar** (0–100%, advances one step per completed iteration)
- **"Upload: Z%"** label
- **DFU upload progress bar** (0–100%, resets each iteration, shows live firmware transfer progress)

Right side:
- **Pie chart** (100×100 dp circle) divided into three coloured segments:
  - Green — successful iterations
  - Red — failed iterations
  - Grey — remaining iterations
- **Legend** below chart: `✓N  ✗N  …N` in matching colours

### Log area
- Monospace scrolling text view, auto-scrolls to bottom on new entries
- Each line: `[HH:mm:ss.SSS] message`
- Fills remaining vertical space between stats and bottom buttons

### Bottom buttons (hidden until test ends)
| Button | Action |
|---|---|
| **New Test** | Resets all state, clears log, expands device list, starts a new scan |
| **Share Log** | Opens system share sheet with the log file attached |

---

## BLE Scanning

- Scan starts automatically on launch (after permissions are granted)
- Scan duration: **15 seconds**, then stops automatically
- Mode: low-latency (`SCAN_MODE_LOW_LATENCY`)
- Only devices with a non-null local name are shown
- Devices are added incrementally; list never reorders or flickers (new-only, no RSSI sorting)
- Change animations disabled on the list to prevent visual instability
- **Rescan / Change**: clears the list and starts a fresh 15-second scan
- BLE must be enabled; if not, the OS is prompted to enable it

---

## DFU Test Loop

### Prerequisites
Both a device and a firmware ZIP must be selected before the test can start.

The firmware ZIP is copied to the app's private cache at selection time (`cache/firmware/firmware.zip`) so the file remains accessible for the full test duration regardless of original URI permissions.

### Iteration flow
For each iteration `i` of `N`:

1. **Wait for connectable window** — scan for the target MAC address and block until the advertisement has `isConnectable = true`. The device may alternate between a connectable advertisement and a non-connectable iBeacon; only the connectable slot is a valid DFU window. Logs once if the device is seen but not connectable.

2. **Attempt DFU** (up to 3 attempts):
   - Each attempt: call `waitUntilConnectable` again, then start the Nordic DFU library
   - DFU uses the **Secure DFU with Buttonless Service** path:
     - Connect to device in application mode
     - Trigger the buttonless DFU characteristic → device enters bootloader and disconnects
     - Library waits 2 s (`rebootTime`), then scans up to 15 s for the DFU bootloader
     - Transfers the ZIP firmware
   - **GATT error 133** (connection rejected) → retryable, up to 3 total attempts
   - **Timeout** or **other error** → non-retryable, iteration marked as failed
   - Between retry attempts: 2 s delay, then `waitUntilConnectable` again

3. **Record result** — success or failure logged and counted

4. **Inter-iteration wait** (skipped after the last iteration):
   - Wait **60 seconds**
   - Re-scan for the device by MAC address, also scanning `MAC±1` (some Nordic bootloaders shift the address by one after DFU)
   - Only a **connectable** advertisement is accepted
   - Timeout: **5 minutes** — if device not found, test stops early with a critical log message
   - Proceed with the (possibly new) MAC address

### Stopping
- **User taps Stop**: cancels the coroutine, logs "manually stopped by user"
- **Device not found after inter-iteration scan**: logs "CRITICAL" and breaks the loop
- In all cases: the `finally` block runs, emits the final score, sends the email report, and stops the foreground service

### Screen stays on
`FLAG_KEEP_SCREEN_ON` is set while the test is running and cleared when it ends.

---

## MAC Address Drift

After DFU, Nordic BLE devices sometimes advertise at `address ± 1`. The inter-iteration re-scan therefore builds three scan filters:
- `lastAddress`
- `lastAddress + 1`
- `lastAddress - 1`

Whichever address responds (connectable) is used for the next iteration.

---

## Progress Indicators

| Indicator | Updates |
|---|---|
| Overall progress bar | After each iteration completes: `(i / total) × 100` |
| DFU upload progress bar | Live during each firmware transfer (0–100%), resets to 0 at iteration start |
| "Upload: Z%" label | Mirrors DFU upload progress bar |
| "Iteration: X / Y" label | Updates at the start of each iteration |
| Pie chart + legend | Updates after every success or failure |
| Foreground notification | Shows "Running iteration X of Y…" |

---

## Logging

- Every significant event is timestamped and appended to an in-memory string (displayed in the log view) and simultaneously written to `cache/logs/dfu_stress_test_log.txt`
- Each new test session overwrites the log file and clears the in-memory log
- Logged events include: iteration start, DFU phase transitions (connecting, enabling DFU mode, uploading, validating, disconnecting), errors with code, retry messages, inter-iteration scan results, and final score

### Share Log
The log file is shared via the system share sheet using `FileProvider`. The share intent uses `ACTION_SEND` with the file attached as a stream.

---

## Automatic Email Report

Sent at the end of every test session (whether completed, stopped by user, or aborted by error).

| Field | Value |
|---|---|
| From / To | kaimeng@proxxiband.com |
| SMTP | smtp.gmail.com:587 (STARTTLS) |
| Auth | Google Workspace app password |
| Subject | `DFU Stress Test Report – DeviceName (MAC)` |
| Body | Device name, MAC address, firmware filename, success count, fail count, total |
| Attachment | `dfu_stress_test_log.txt` (if it exists) |

Email is sent on a background thread. Failure is logged but does not affect test outcome.

---

## BLE Stack Reset

A known issue on Android: after the target device connects to another phone or broadcasts a non-connectable advertisement, the Android pad's BLE stack can get a stale connection entry and refuse all connections to that device with GATT error 133, even from other apps (e.g. nRF Connect). The device is fine — other phones can still connect.

**Reset BT button** behaviour:
- **Android ≤ 12**: programmatically disables Bluetooth, waits 2 s, re-enables, waits 3 s, then starts a fresh scan
- **Android 13+**: opens Bluetooth Settings with a toast instructing the user to toggle Bluetooth manually (programmatic toggle is blocked by the OS)

---

## Permissions Required

| Permission | When |
|---|---|
| `BLUETOOTH_SCAN` | Android 12+ — BLE scanning |
| `BLUETOOTH_CONNECT` | Android 12+ — connecting to devices |
| `ACCESS_FINE_LOCATION` | Android < 12 — BLE scanning |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | Android < 12 |
| `FOREGROUND_SERVICE` | Running the DFU service in the foreground |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Android 12+ foreground service type |
| `POST_NOTIFICATIONS` | Android 13+ — showing foreground service notification |
| `INTERNET` | Sending the email report |

---

## iOS Porting Notes

### DFU library
Use Nordic's **iOS DFU Library** (`iOSDFULibrary`, available via Swift Package Manager / CocoaPods). The API is nearly equivalent:
- `DFUServiceInitiator` → configure with `buttonlessServiceInSecureDfuEnabled = true`, `rebootTime = 2.0`, `scanTimeout = 15.0`
- Implement `DFUProgressDelegate` for upload percentage and `DFUServiceDelegate` for state/error callbacks

### BLE scanning
Use `CoreBluetooth` (`CBCentralManager`). Key differences:
- iOS does not expose raw MAC addresses — devices are identified by a UUID assigned by iOS (stable per app, resets on Bluetooth toggle or app reinstall)
- `isConnectable` is available on `CBAdvertisementData` via key `CBAdvertisementDataIsConnectable`
- MAC ±1 scanning is not applicable on iOS since raw MACs are hidden; use the iOS UUID instead

### Connectable check
```swift
let isConnectable = advertisementData[CBAdvertisementDataIsConnectable] as? Bool ?? false
```

### Inter-iteration re-scan
Since iOS hides MACs, re-scan by service UUID or device name rather than address. The DFU bootloader typically advertises a specific service UUID (`00001530-1212-EFDE-1523-785FEABCD123` for Nordic legacy, `FE59` for secure DFU).

### Background execution
iOS does not support long-running background BLE operations the same way Android foreground services do. Use `CBCentralManager` with `CBConnectPeripheralOptionNotifyOnConnectionKey` and consider `UIApplication.shared.beginBackgroundTask` for short gaps. Full background DFU requires the app to be in the foreground or use a background mode entitlement.

### Email
Use `MFMailComposeViewController` for user-initiated sharing, or `URLSession` with an SMTP library (e.g. `SwiftSMTP`) for automatic sending equivalent to the Android behaviour.

### BLE stack reset
On iOS, there is no API to toggle Bluetooth programmatically. The equivalent of the Reset BT button would open `App-Prefs:root=Bluetooth` (private URL scheme, may be rejected by App Store review) or simply show an alert instructing the user.

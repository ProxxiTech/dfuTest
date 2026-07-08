# iOS BLE DFU Stress Tester — App Specification

A port of the Android **BLE DFU Stress Tester** (`DFU_TESTER_SPEC.md`) to iOS. Same purpose: run repeated Nordic DFU cycles against a Proxxi band over BLE, unattended, logging every event and producing a shareable report. This document is written so an iOS developer can build it without reading the Android code — but read `DFU_TESTER_SPEC.md` for the Android reference behavior.

> Scope: this is the **test harness**, not the production consumer app. It maximizes visibility and control over the connect/DFU sequence so we can measure reliability.

---

## 1. Platform & dependencies

| Item | Choice |
|---|---|
| Language | Swift 5.9+, SwiftUI (or UIKit) |
| Min iOS | 15.0 |
| BLE | `CoreBluetooth` (`CBCentralManager`) |
| DFU | **Nordic `NordicDFU`** (a.k.a. iOSDFULibrary) via SwiftPM: `https://github.com/NordicSemiconductor/IOS-DFU-Library` — the **legacy/secure-DFU** library (NOT the McuMgr `iOS-nRF-Connect-Device-Manager`; the band runs an nRF5-SDK S132 Secure DFU bootloader) |
| File import | `UIDocumentPickerViewController` / `.fileImporter` for `.zip` |
| Share | `UIActivityViewController`; email via `MFMailComposeViewController` |

---

## 2. Critical iOS ⇄ Android BLE differences (read first)

| Concern | Android | iOS |
|---|---|---|
| Device identity | MAC address (visible) | **No MAC.** Opaque `CBPeripheral.identifier: UUID`, stable per iOS device, resets on BT toggle/reinstall, **not shared across phones** |
| MAC ±1 drift trick | Used for post-DFU re-scan | **Not applicable** — re-scan by **service UUID** and re-identify the peripheral |
| Connect timeout | ~30 s hard timeout → GATT 133 | **`connect()` has NO timeout** — it's inherently "patient." You must impose your own timeout with a `Timer` + `cancelPeripheralConnection` |
| Error `133` | The catch-all | **Does not exist.** Use `CBError` / `CBATTError` (see §6.6) |
| Connection interval control | `requestConnectionPriority()` | **No app control** — the peripheral's preferred params are honored automatically (so firmware rec D6 is nothing-to-do) |
| iBeacon (non-connectable) phase | Seen as a separate non-connectable MAC | Not delivered to CoreBluetooth as a peripheral; iBeacons belong to CoreLocation. With a **service-UUID scan filter you simply don't see the band during its 10 s iBeacon window** — which is what we want |
| Background execution | Foreground service | `bluetooth-central` background mode (throttled). For a long unattended run, keep the app **foreground with the screen awake** (`UIApplication.shared.isIdleTimerDisabled = true`) |
| Programmatic BT reset | Possible ≤ Android 12 | **Impossible.** No API to toggle Bluetooth |

---

## 3. Target device profile (Proxxi band) — what iOS sees

From the band firmware (nRF52 / S132, single advertising set, single peripheral link):

- **Connectable advertisement:** `CBAdvertisementDataIsConnectable == true`, advertises **service UUID `fe6991e0-0985-11e5-b16e-0002a5d5c51b`**; scan response carries the full local name + manufacturer data (band code + on-charger flag). **This is the scan filter and the only valid DFU target.**
- **iBeacon phase:** non-connectable, proximity UUID `fe6991e0-0985-11e5-b16e-0002a5d5c51b`, major/minor = band-code bytes. Not surfaced via CoreBluetooth with a service-UUID filter → the band is simply **absent from scans for those 10 s**.
- **Rotation schedule:**
  - Within 30 min of a successful sync → **always connectable**, no rotation.
  - After 30 min idle → **50 s connectable → 10 s iBeacon → repeat** (60 s period).
  - **While connected, rotation is suspended.**
- **Advertising interval ≈ 1022 ms** (slow, Apple-friendly). Scans must be patient.
- **Single peripheral link:** connected to another central (e.g. an iPhone) → the band stops advertising connectable → your scan finds nothing (not an error, just absent).
- **No pairing/bonding:** the band rejects pairing (`PAIRING_NOT_SUPP`); all characteristics are open. **Never initiate pairing.**
- **Connection-parameter prefs:** 15–30 ms during sync, 105–120 ms idle, latency 0, supervision timeout 6 s. iOS honors these automatically; do not fight them.

### Reference UUIDs
| Purpose | UUID |
|---|---|
| Proxxi connectable service (scan filter) | `fe6991e0-0985-11e5-b16e-0002a5d5c51b` |
| Secure DFU service (bootloader) | `FE59` |
| Battery Service / Battery Level | `180F` / `2A19` (uint8 %) |
| Device Information Service | `180A` |
| Firmware Revision String | `2A26` |
| Hardware Revision String | `2A27` |
| Software Revision String (= bootloader ver) | `2A28` |
| Manufacturer / Model / Serial | `2A29` / `2A24` / `2A25` |

---

## 4. UI (single screen, mirrors Android)

- **Device list:** peripherals discovered by the Proxxi service UUID, shown by local name (+ band code from mfg data). Tap to select; identity kept as `CBPeripheral` + its `identifier`.
- **Firmware picker:** "Select DFU File" → document picker filtered to `public.zip-archive`. Copy into the app sandbox (`FileManager` temp/Documents). Show filename.
- **Config:** Iterations (default 10), Timeout seconds (default 120), Inter-iteration delay minutes (default 1–2).
- **Start/Stop:** full-width; disabled until a device + a firmware file are selected; disables config while running.
- **Stats:** "Iteration X / Y", overall progress bar, "Upload Z%" + upload progress bar, and a success/fail/remaining pie + legend.
- **Device info banner** (new, from the pre-connect read): `Battery 87% · FW v1.2.3 · HW ZA · SW/BL x.y · SN …`.
- **Log view:** monospace, auto-scroll, `[HH:mm:ss.SSS] message`.
- **After test:** "New Test" and "Share Log" buttons.
- Keep screen awake while running.

---

## 5. Core flows

### 5.1 Scan
```swift
central.scanForPeripherals(
  withServices: [CBUUID(string: "fe6991e0-0985-11e5-b16e-0002a5d5c51b")],
  options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
```
- Filtering by the service UUID means you only ever see the **connectable** phase (the 10 s iBeacon phase is invisible — good).
- Read `advertisementData[CBAdvertisementDataIsConnectable] as? Bool` and `CBAdvertisementDataLocalNameKey`, `CBAdvertisementDataManufacturerDataKey`.
- Initial scan ~15 s; inter-iteration re-scan up to **5 min** (§5.7).

### 5.2 Firmware pick
Document picker → copy the `.zip` into the sandbox. Build `DFUFirmware(urlToZipFile:)` at DFU time.

### 5.3 Per-iteration loop (for i in 1…N)
1. **Acquire a connectable peripheral** (re-scan if needed; only accept `isConnectable == true`).
2. **Pre-connect + read device info + hold the connection** (§5.4). This mirrors the Android tester's own-GATT step: connect ourselves, read Battery + DIS, and keep the connection so we've proven connectability and captured battery drain per iteration.
3. **Run DFU** (§5.5) against that peripheral.
4. **Record** success/fail.
5. **Inter-iteration wait** then **re-scan** (§5.7). Skip after the last iteration.

### 5.4 Device-info read (own connection)
Connect (with the timeout strategy in §5.6), then:
```swift
peripheral.discoverServices([CBUUID(string:"180F"), CBUUID(string:"180A")])
// then discoverCharacteristics + readValue for:
//   Battery: 180F/2A19  (uint8 → value[0])
//   DIS 180A: 2A26 FW, 2A27 HW, 2A28 SW/BL, 2A29 Manufacturer, 2A24 Model, 2A25 Serial (UTF-8)
```
Serialize reads (CoreBluetooth queues them, but read one, wait for `didUpdateValueFor`, read next — keeps it clean). Log the summary. All chars are open, so no pairing prompt.

> **iOS note on "holding the connection" for the DFU hand-off:** Android's trick (keep our GATT open so the DFU library's `connectGatt` reuses the ACL) does **not** translate cleanly — the NordicDFU library uses its own `CBCentralManager`, and CoreBluetooth connection state is per-central-manager. Two viable approaches, in order of preference:
> 1. **Let NordicDFU own the connection** — do the info read on your central, `cancelPeripheralConnection`, then start DFU by identifier. Simplest; relies on iOS `connect()` being patient (no cold-connect 133 problem to begin with).
> 2. **Give NordicDFU your `CBCentralManager`** (see §5.5) and pass the already-connected `CBPeripheral` as the target, so the library reuses your connection. **Verify this reuse behavior against the installed library version** before depending on it.

### 5.5 DFU via NordicDFU
```swift
let firmware = DFUFirmware(urlToZipFile: zipURL)!
let initiator = DFUServiceInitiator(centralManager: myCentral)   // reuse your central if going route 2
    .with(firmware: firmware)
initiator.enableUnsafeExperimentalButtonlessServiceInSecureDfu = true  // band uses buttonless secure DFU
initiator.alternativeAdvertisingNameEnabled = true                     // library re-scans for the bootloader
// (Property names vary by library version — confirm against the release you pin.)
initiator.logger   = self   // LoggerDelegate
initiator.delegate = self   // DFUServiceDelegate (dfuStateDidChange / dfuError)
initiator.progressDelegate = self  // DFUProgressDelegate (dfuProgressDidChange)

let controller = initiator.start(target: peripheral)   // or .start(targetWithIdentifier: uuid)
```
- The library drives: connect (app mode) → write buttonless characteristic → device reboots to bootloader → **re-scan for the DFU bootloader** (Secure DFU `FE59`) → upload the ZIP → reboot to app.
- On iOS the bootloader is re-found by scanning (identifier/name), **not** by MAC±1.
- `DFUServiceController` exposes `pause()`, `resume()`, `abort()` (use `abort()` on Stop / timeout).
- Implement your own **per-iteration timeout** (default 120 s) → `controller.abort()` and mark failed.

### 5.6 Connection strategy on iOS ("fast" vs "patient")
iOS `connect()` never times out, so *the concept is inverted from Android* — patience is free, and you add "fast" behavior with your own timer:

- **Fast attempt:** `connect(peripheral)`, start a **6 s** timer; if not connected, `cancelPeripheralConnection` and retry. Catches the common case within the 50 s connectable window and beats the micro teardown race on retry.
- **Patient attempt (fallback):** `connect(peripheral)` with a **~20 s** timer (or none). Because iOS keeps trying to connect whenever the peripheral reappears, this naturally **rides out the 10 s iBeacon window** and connects when it returns — no special code, just a longer deadline.
- Suggested: 3 fast (6 s) attempts, then 1 patient (20 s). Always `cancelPeripheralConnection` before retrying so no half-open connection lingers (the iOS analog of Android's `close()`).

### 5.7 Inter-iteration re-scan
- Wait the configured delay.
- `scanForPeripherals(withServices: [proxxiUUID])`, accept the first `isConnectable == true` result. Prefer the same `CBPeripheral.identifier`; if the identifier changed (post-DFU), match by local name / band-code mfg data.
- Timeout **5 min** → if not found, log CRITICAL and stop (device likely connected elsewhere or out of range — see §6.6).

---

## 6. Behavior details

### 6.1 Buttonless DFU
Same secure-DFU-with-buttonless path as Android: `enableUnsafeExperimentalButtonlessServiceInSecureDfu = true`. The library handles the jump + reconnect.

### 6.2 Progress & stats
Map `DFUProgressDelegate.dfuProgressDidChange(part:totalParts:progress:…)` → upload bar; count success/fail for the pie; "Iteration X/Y" at each iteration start.

### 6.3 Logging
Timestamp every event; append to an in-memory buffer (log view) and a file (`.../Documents/dfu_stress_test_log.txt`). Overwrite per session. Implement `LoggerDelegate.logWith(_:message:)` to capture the library's phase transitions.

### 6.4 Report
- **Share Log:** `UIActivityViewController` with the log file URL.
- **Email:** `MFMailComposeViewController` (requires a user tap and a Mail account). Note: **automatic SMTP send** (as the Android app does) has no first-party iOS API and is awkward/against typical review norms — prefer a share sheet, or POST the log to a backend endpoint for unattended runs.

### 6.5 Screen-awake
`UIApplication.shared.isIdleTimerDisabled = true` while running; reset when done.

### 6.6 Error handling (the `133` replacement)
There is no `133`. Handle these from `centralManager(_:didFailToConnect:error:)` and `didDisconnectPeripheral:error:` (`CBError.Code`):

| Situation | Likely signal | Action |
|---|---|---|
| Landed in the 10 s iBeacon window / band briefly gone | fast timer fires with no connect | retry fast; then patient |
| Establishment race (band's connectable↔iBeacon toggle) | `didFailToConnect` / early disconnect | retry within ~1–2 s (usually lands) |
| Band connected to another central (e.g. iPhone) the whole time | **scan finds nothing** → 5-min re-scan timeout | log "device not found / likely connected elsewhere"; not a connection error |
| Genuine link drop mid-DFU | `didDisconnectPeripheral(error:)` non-nil | let NordicDFU's retry handle, else fail iteration |

Note: iOS gives **no dedicated "connected elsewhere / busy" code** — an occupied single-link band manifests as *not discoverable*, so the dominant symptom is a **scan timeout**, not a connect error.

---

## 7. Firmware-team Android tuning recs (D1–D7) mapped to iOS

| Rec | iOS equivalent |
|---|---|
| D1 close before retry | Always `cancelPeripheralConnection` before retrying (no leaked half-connections) |
| D2 retry on 133 w/ backoff; wait out the 10 s window | The **patient** (long-timeout) attempt inherently rides out the 10 s window |
| D3 autoConnect=false fast, then autoConnect=true | iOS `connect()` is already "patient"; emulate "fast" with a short self-timeout, "patient" with a long one |
| D4 scan long enough | 5-min re-scan for the ~1 s advertiser; `allowDuplicates` on |
| D5 no bonding | Never trigger pairing; all chars open |
| D6 connection params | **Nothing to do** — iOS honors the peripheral's preferred params |
| D7 sync resets the 30-min timer → always connectable | Product behavior; if the tester also performs a sync/handshake after connecting, it keeps the band connectable and avoids the rotation window for 30 min |

---

## 8. Permissions / Info.plist / entitlements

| Key | Reason |
|---|---|
| `NSBluetoothAlwaysUsageDescription` | BLE usage prompt (required) |
| `UIBackgroundModes` → `bluetooth-central` | Only if background BLE is needed (throttled; prefer foreground) |
| `UISupportedInterfaceOrientations` | as desired |
| `LSSupportsOpeningDocumentsInPlace` / document types | ZIP import if needed |

No location permission is required (we scan by service UUID and don't use CoreLocation/iBeacon detection).

---

## 9. Open questions to verify before/at implementation

1. **NordicDFU version & exact option names** — `enableUnsafeExperimentalButtonlessServiceInSecureDfu`, `alternativeAdvertisingNameEnabled`, reboot/scan-timeout equivalents differ across releases. Pin a version and confirm.
2. **Connection reuse (§5.4 route 2)** — confirm whether passing your `CBCentralManager` + a connected `CBPeripheral` makes the library skip its own connect. If not, use route 1 (let the library own it — acceptable on iOS since `connect()` is patient).
3. **Whether a "sync/handshake" step is worth adding** to hold the band in always-connectable mode (D7) during a long run.
4. **Unattended email** — decide share-sheet vs backend upload; MFMailComposeViewController cannot send without user interaction.

---

## 10. Summary of the iOS-vs-Android mental model

- Android's whole 133/fast-retry/patient saga exists because Android does an aggressive, timeout-bound cold connect. **iOS `connect()` is patient by default**, so the cold-connect establishment failure is largely a non-issue — the main thing you build is a *self-imposed short timeout* to get fast behavior and to detect the "band is in its iBeacon window / connected elsewhere" cases.
- You cannot use MAC addresses or MAC±1; identify and re-find the band by **service UUID + `CBPeripheral.identifier` + local name/band-code**.
- Everything else (iterate, read info, DFU via Nordic lib, log, report, keep-awake) maps 1:1 to the Android tester.

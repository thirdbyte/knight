# Hero Xtreme 125R — Instrument Console BLE Protocol

**Reverse-engineered from:** `Hero App.apk` (`com.customerapp.hero`, 8 dex, ~40k classes)
**Tooling:** [REA](https://github.com/morluto/rea) 6.8.0 (`inspect-android-package`, `inspect-android-method`) over headless JADX 0.7.1, plus JADX 1.5.6 full decompile and `--decompilation-mode fallback` (raw Dalvik) for ground truth on ambiguous control flow.
**Date:** 2026-11-11
**Goal:** build a minimal, console-connectivity-only Android app. No DND, no auto-SMS, no telematics.

---

## 0. TL;DR for the app build

| Question | Answer |
|---|---|
| Transport | **BLE GATT** (not Bluetooth Classic / SPP) |
| Profile | **Telit / Stollmann Terminal I/O (TIO)** — a credit-flow-controlled serial-over-BLE profile |
| Service UUID | `0000FEFB-0000-1000-8000-00805F9B34FB` |
| Payload | **ASCII text frames**, `\n` + opcode + fields + `\n` |
| Bonding | Yes — OS-level BLE pairing, console displays a 6-digit passkey |
| Permissions actually needed | 6 runtime + 4 manifest-only (see §5) |
| Can we set the clock over BLE? | **No** on this console family. See §7 |
| Can we set brightness over BLE? | **No**, nowhere in the app. See §7 |
| Hidden extras found | Console→phone media/call control commands (§6), unmapped opcode space (§7.3) |

---

## 1. Which stack the Xtreme 125R uses

The APK contains **two completely separate** Bluetooth stacks. Picking the wrong one wastes weeks.

### 1a. `com.customerapp.hero.ride_guide` — the instrument cluster (THIS ONE)

- `BluetoothConnectionService.java` — GATT client, TIO state machine
- `BluetoothConnectionService$bluetoothGattCallback$1.java` — GATT callbacks + console→phone command dispatch
- `BluetoothScanService.java` — scan + device picker UI
- `com.customerapp.hero.views.fragments.rideGuide.SendDataToCluster.java` — **the protocol encoder**
- `com.customerapp.hero.thorwrapper.rideguidewrapper.DashboardWrapper.java` — orchestration, phone-state wiring

Legacy broadcast action strings in this package are `com.facomsa.simplebleapp.*`, suggesting the cluster vendor's reference app was the starting point.

### 1b. `com.otcengineering.otcble` — telematics TCU (NOT this bike)

OTC Engineering BLE SDK. Its service UUID is `0010676e-6972-6565-6e69-676e4543544f` — decode the bytes as ASCII and reverse: `OTCEngineering`.

This drives a **Telematics Control Unit**, not the cluster: `DataConstants.java` exposes `IMEI`, `ICCID`, `GeoFence1CentreLatitude`, `Immobilization_signal`, `BuzzerPatternTheftAlert*`, `Accidentalert*`, `APNconfiguration`. It is consumed only by `com/customerapp/hero/otc/*` (Geofence, Immobilise, Trips, Diagnosis) and `com/customerapp/hero/kogs/*`. It uses a **memory-address write protocol** against `MsslAddress` / `JnsAddress` maps (MSSL = Minda Stoneridge Instruments; JNS = Nippon Seiki) with `TCUType` ∈ `{MSSL, MSSL_V2, JNS, JNS_V2}`.

**Ignore §1b entirely for this project** — except for one finding noted in §7.1.

### 1c. Why the 125R is the `CommonFeatures` tier of stack 1a

`VdsBleConnectionObj` (`com.modellib.hmc`) is fetched from Hero's backend per vehicle and cached in SharedPreferences under key `"VdsObject"`:

```
{ featureType, serviceType, modelCode, modelName, bleName, vdsCode, nickname }
```

`featureType` has exactly three values across the APK, and all three route through the same `SendDataToCluster` + `BluetoothConnectionService`:

| `featureType` | Console capability tier |
|---|---|
| `CommonFeatures` | **Calls, SMS, phone battery** — ← Xtreme 125R |
| `CommonFeatureMusic` | the above + music metadata/transport |
| `Navigation` | the above + turn-by-turn |

The Feb-2024 Xtreme 125R manual lists exactly: BT icon, incoming call alert, missed call alert, message alert, phone battery status. That is the `CommonFeatures` set, 1:1.

`bleName` also comes from the backend; the scanner filters on **the first 5 characters** of it (`BluetoothScanService.java:301-313`).

**Observed on a real bike, 2026-11-11:** the cluster advertises as
`JAU02_78A4` on MAC `74:46:B3:B9:78:A4`. So the shape is
`<model>_<last two bytes of the MAC>`, and the model part is **not** `HERO` —
`JAU02` is the five-character model code the backend hands the app, which is
exactly what that first-5-characters filter is matching. The manual's pairing
screenshot shows a different model's name, which is why an earlier draft of this
document wrongly claimed the prefix was `HERO_`.

Do not match on the model prefix. Match the MAC tail:

```kotlin
val tail = mac.replace(":", "").takeLast(4)          // "78A4"
name.substringAfterLast('_').equals(tail, true)      // "JAU02_78A4" -> true
```

> **Confirmed on the bike:** a BLE scan with the ignition on shows `JAU02_78A4`. The device does **not** advertise service `FEFB`; the service is only visible after connecting and running discovery, which is why filtering the scan on it finds nothing.

---

## 2. GATT layer — Telit Terminal I/O

### 2.1 UUIDs (verbatim from `BluetoothConnectionService.java:113-125`)

```
TIO service        0000FEFB-0000-1000-8000-00805F9B34FB
UART data RX       00000001-0000-1000-8000-008025000000   phone WRITES app→console bytes here
UART data TX       00000002-0000-1000-8000-008025000000   console NOTIFIES console→phone bytes
UART credits RX    00000003-0000-1000-8000-008025000000   phone WRITES credits it grants
UART credits TX    00000004-0000-1000-8000-008025000000   console INDICATES credits it grants
CCCD               00002902-0000-1000-8000-00805F9B34FB
```

> **Correction, confirmed on a real bike.** The FEFB set above is only one of
> two, and it is **not** the one an Xtreme 125R uses. A GATT dump of
> `JAU02_78A4` shows exactly two services: `1800` (Generic Access) and a
> vendor-specific one. There is no FEFB.

`serviceTypeValidation` in `BluetoothConnectionService$bluetoothGattCallback$1`
picks between two sets at discovery. Same protocol, same four roles, different
UUIDs:

```
service            E837D9A2-9C49-4493-9547-8E6918A59CA8
UART data RX       64AECB40-849A-44F1-934F-ADDC4B316423   phone WRITES app→console bytes here
UART data TX       B792A4BB-DB87-436A-9066-DB63C5FB3F00   console NOTIFIES console→phone bytes
UART credits RX    F535DD6E-7975-4ABD-9719-491E38A81179   phone WRITES credits it grants
UART credits TX    36E8614B-2DED-45C1-9AD3-C9F59A01F21E   console INDICATES credits it grants
```

Everything else in this document is unchanged: the frames, the opcodes, the
bring-up order, the credit handling. Only the addresses differ.

An implementation targeting one bike can hardcode the set that bike has, which is
what Knight does. One aiming to work across models must resolve it at discovery,
the way the Hero app does.

These UUIDs do appear in the earlier UUID sweep in this document; an earlier
draft wrote them off as DFU or OTA identifiers without checking, which is how
they were missed. The lesson generalises: in that sweep, every 128-bit UUID that
was not immediately recognisable deserved a grep for its call site.

Note the non-standard base `…-008025000000` on the four characteristics — that is Telit's, not the Bluetooth SIG base. Build them as literal strings, do not derive from a 16-bit short UUID.

### 2.2 Connection state machine

`TioState` enum, driven across `BluetoothConnectionService` and its GATT callback:

```
INITIAL
  → connectGatt(autoConnect = false)                            BluetoothConnectionService.java:702
  → onConnectionStateChange(CONNECTED) → discoverServices()
  → onServicesDiscovered: find service FEFB → requestMtu(65)    callback:763-781
  → onMtuChanged: write CCCD ENABLE_INDICATION on credits TX (0004)
UART_CREDITS_TX_INDICATION
  → onDescriptorWrite(0004): write CCCD ENABLE_NOTIFICATION on data TX (0002)
UART_DATA_TX_NOTIFY
  → onDescriptorWrite(0002): writeCharacteristic(credits RX 0003, uint8 = 10)
UART_INITIAL_CREDITS
  → console replies; on receipt of command char 53 ('5') →
UART_CONNECTED                                                  ← link is live, start sending
```

Key constants:
- **MTU request = 65** (`mtuSize`, `BluetoothConnectionService.java:318`) → 62-byte ATT payload. Every frame in §3 fits in one write.
- **Initial credit grant = 10** (`UART_CREDITS_SIZE`, written as `FORMAT_UINT8` at offset 0 via `sendIntData(..., 10, ...)`, callback:716).
- Credits must be **topped up** as they are consumed, otherwise the console stops accepting writes. The app re-grants on a 200 ms-delayed handler whenever it is in `UART_INITIAL_CREDITS` (callback:491-502).

### 2.3 Bonding

The service does **not** call `createBond()` explicitly. It connects GATT; the console's characteristics require encryption, so Android raises pairing itself. The console then displays a 6-digit passkey which the user types into the phone — this matches the manual (console shows "pairing pin", app shows a "Pair" dialog).

A `BondStateReceiver` watches `android.bluetooth.device.action.BOND_STATE_CHANGED` and gates service discovery on `getBondState() == BOND_BONDED (12)`.

**Autopairing** (manual p.32) is just: remember the MAC, re-scan with a `ScanFilter.setDeviceAddress(mac)`, reconnect. `reScanForAutoConnect()` + `getMacAddressFilter()`. Scan settings used: `SCAN_MODE_BALANCED(2)`, `CALLBACK_TYPE_ALL_MATCHES(1)`, `MATCH_MODE_AGGRESSIVE(1)`, legacy = true, PHY = 255.

---

## 3. Phone → console protocol (the part you must implement)

### 3.1 Framing

```
'\n'  <opcode>  <fixed-width fields…>  '\n'
```
Encoded **US-ASCII** (`kotlin.text.Charsets.UTF_8` in the app, but all payload is sanitised to ASCII first). Written with `writeCharacteristic` to **UART data RX `00000001`**.

**Sanitisation is mandatory.** The console chokes on anything else:
- Caller names/numbers: `replaceAll("[^A-Za-z0-9 ]", "")`, then truncate to 17 chars.
- Music/general text: `replaceAll("[^A-Za-z0-9 +\\-._]", "")`.
- Padding char is `~` (tilde).

> **Padding quirk — replicate it exactly.** `padString(s, n)` appends `~` until the length counter equals `n`, but appends once more before breaking. The result is **n+1 characters**, not n. So `padString(name, 17)` → **18 chars**, `padString("~", 5)` → **6 chars**. The console's field widths were evidently built around this, so reproduce the off-by-one rather than "fixing" it.

### 3.2 Opcodes

All from `SendDataToCluster.java`.

| Op | Frame | Meaning | Needed for 125R |
|---|---|---|---|
| `2` | `\n2<number>\n` | **Incoming call** — number, ≤17 chars, alnum+space only | ✅ |
| `3` | `\n3<batt><net><name>\n` | **Battery + signal + user name** | ✅ |
| `5` | `\n5<NN>\n` | **Missed call count**, zero-padded to 2 digits | ✅ |
| `6` | `\n60<count>\n` | **Unread SMS count** (note the literal `0` after the `6`) | ✅ |
| `7` | `\n7<name18><state><name18>0<dur><vol>\n` | Call state detail | optional |
| `9` | `\n9<song20><album20>00<play><dur><vol>\n` | Music metadata | ✗ |
| nav | `\n<id><dir><dist>…<roadName>\n` | Turn-by-turn (`NavigationPacket.toPacketString()`) | ✗ |

#### Op 2 — incoming call
`getCallExampleText(number, featureType)` with `featureType != "CommonFeatureMusic"`:
```
"\n2" + number + "\n"
```
`sendRingingData()` schedules this on a `ScheduledExecutorService` **every 1 second** from the moment of ring, until `sendCallReceive()` shuts the executor down. The console needs the repeat — a single frame is not enough to keep the alert up.

#### Op 3 — battery + network + name
```
"\n3" + batteryBucket + networkLevel + userName + "\n"
```
- `batteryBucket` — one char, **verified against raw Dalvik bytecode** (`--decompilation-mode fallback`; the normal JADX output of this method is wrong because of duplicated blocks, so do not trust a plain decompile here):

  | Battery % | Char |
  |---|---|
  | 0–20 | `0` |
  | 21–39 | `1` |
  | 40–59 | `2` |
  | 60–79 | `3` |
  | ≥80 | `4` |

  This 5-level bucket is what produces the manual's **"Low Batt"** alert — the console renders bucket `0` as the low-battery warning rather than showing a percentage.

- `networkLevel` — one char `0`–`4`, from `SignalStrength.getLevel()` obtained by **reflection** (`DashboardWrapper.java:753`). Send `'0'` if you don't want to implement it; the 125R console does not display signal strength anyway (the manual confirms network strength is app-only).
- `userName` — raw, truncated to 15 chars, **not** padded in the `CommonFeatures` tier. (Only `CommonFeatureMusic` pads it to 18.)
- The app sends this packet **twice**, via `Handler.postDelayed` at **+2000 ms** and **+5000 ms**, as crude retransmission.

#### Op 5 — missed calls
```
"\n5" + count + "\n"     // count zero-padded: 1 → "01", 10 → "10"
```
Sent on a 1000 ms delay. The counter is maintained **in the app** (incremented on RINGING→IDLE without OFFHOOK); it is not read from the call log provider.

#### Op 6 — SMS
```
"\n60" + count + "\n"
```
Counter maintained in the app, incremented per `SMS_RECEIVED` broadcast.

#### Op 7 — call state detail (optional)
```
"\n7" + name(18) + state + name2(18) + "0" + duration(6) + volume(1) + "\n"
```
`state`: `1` ringing · `2` active · `4` outgoing · `0` none.
Clear the display with `sendNoActiveData()`:
```
"\n7" + "~"*18 + "0" + "~"*18 + "0" + "~"*6 + "d" + "\n"      // 48 bytes
```
`volume` is a letter from a bucketed map (`ContextExtKt.m13274O`): `d` 1-5 · `e` 5-10 · `f` 10-15 · `g` 15-40 · `h` 40-50 · `i` 50-60 · `j` 60-70 · `k` 70-80 · `l` 80-90 · `m` ≥90.

---

## 4. Console → phone (notifications on `00000002`)

The console sends commands **to** the phone. The app reads **byte index 2** of the notification payload as the command code (`callback:92-472`). It checks the same position two ways — `characteristic.getStringValue(0).charAt(2)` and `bytes[3]` of a `"\n"+value+"\n"` wrapper — so index 2 of the raw payload is confirmed.

| Code | Char | Action the app takes |
|---|---|---|
| 10 | `\n` | `TelecomManager.acceptRingingCall()` |
| 15 | `SI` | `TelecomManager.endCall()` |
| 35 | `#` | Media play/pause toggle |
| 40 | `(` | `skipToNext()` |
| 45 | `-` | `skipToPrevious()` |
| 50 | `2` | Mute / unmute music |
| 53 | `5` | **Link established ack** → state `UART_CONNECTED` |
| 55 | `7` | Call volume up (`STREAM_VOICE_CALL`) |
| 60 | `<` | Call volume down |
| 65 | `A` | Music volume up (`STREAM_MUSIC`) |
| 70 | `F` | Music volume down |

**For the barebone app you only need code 53** (to know the link is up). Everything else can be ignored — the 125R's handlebar has no media keys wired to this.

Note the codes are spaced by 5 (10,15,…,70). **20, 25, 30 are absent** from the app's dispatch — see §7.3.

---

## 5. Permissions — what you actually need

### 5.1 What the Hero app declares (46 permissions)

For reference, the ones relevant to BLE/console: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `READ_PHONE_STATE`, `READ_CALL_LOG`, `READ_CONTACTS`, `ANSWER_PHONE_CALLS`, `RECEIVE_SMS`, `READ_SMS`, `SEND_SMS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `ACCESS_NOTIFICATION_POLICY`, `MEDIA_CONTENT_CONTROL`.

Most of the rest (`CAMERA`, `RECORD_AUDIO`, `READ_CALENDAR`, `ACTIVITY_RECOGNITION`, `AD_ID`, …) belong to the service-booking / telematics / analytics halves of the app.

### 5.2 Minimal set for the 5 features

**Runtime (must prompt):**

| Permission | Why | Drop it if… |
|---|---|---|
| `BLUETOOTH_CONNECT` (API 31+) | GATT connect, bond, write | never |
| `BLUETOOTH_SCAN` (API 31+) | find the console | never — but see the trap below |
| `ACCESS_FINE_LOCATION` | BLE scanning | you assert `neverForLocation` on `BLUETOOTH_SCAN` |
| `READ_PHONE_STATE` | call state RINGING/OFFHOOK/IDLE → ops 2, 5, 7 | — |
| `READ_CALL_LOG` | **required on API 28+ for `EXTRA_INCOMING_NUMBER` to be non-null** | you send a placeholder instead of the number (see note) |
| `RECEIVE_SMS` | `SMS_RECEIVED` broadcast → op 6 | you use a `NotificationListenerService` instead (see note) |

**Manifest-only (no prompt):**
`INTERNET` is **not** needed. You need:
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_CONNECTED_DEVICE` (API 34+; the type for a BLE link)
- `POST_NOTIFICATIONS` (API 33+, runtime-prompted, for the foreground-service notification)
- `BLUETOOTH` + `BLUETOOTH_ADMIN` with `android:maxSdkVersion="30"`
- `RECEIVE_BOOT_COMPLETED` — only if you want auto-reconnect after reboot

**Explicitly NOT needed** (and the main reason the Hero app feels invasive):
`SEND_SMS` (that's only the DND auto-reply you don't want), `READ_SMS` (you only need the arrival event, not the content), `READ_CONTACTS` (only needed to show a *name*; the 125R tier sends the raw number), `ANSWER_PHONE_CALLS` (only for console→phone code 10), `ACCESS_NOTIFICATION_POLICY` (DND), `MEDIA_CONTENT_CONTROL`, `ACCESS_BACKGROUND_LOCATION`, `CALL_PHONE`, `NEW_OUTGOING_CALL`, `READ_PHONE_NUMBERS`.

**Battery level needs no permission at all** — sticky broadcast `Intent.ACTION_BATTERY_CHANGED`, read `EXTRA_LEVEL` / `EXTRA_SCALE`.

### 5.2b The scan permission trap

On Android 12 and up, `BLUETOOTH_SCAN` on its own is **not enough**. You must
pick one of two routes, and declaring neither means the scan quietly returns no
results at all:

```xml
<!-- Route A, what Knight does. No location, no GPS prompt. -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
```

```xml
<!-- Route B, what the Hero app does. Needs location granted AND enabled. -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

The Hero app takes route B, which is why its manual tells you to turn GPS on.
Route A is strictly better for this use case: BLE scan results are never used to
derive position here.

This is worth stating loudly because the failure mode is silent. No exception
reaches your callback, `startScan` returns normally, and you simply get an empty
list forever.

### 5.3 Two ways to dodge the scary permissions

Google Play restricts `READ_CALL_LOG` / `RECEIVE_SMS` / `READ_SMS` for apps not set as the default handler. For a personal sideloaded build this is irrelevant, but if you ever want to publish:

- **`NotificationListenerService`** (`BIND_NOTIFICATION_LISTENER_SERVICE`) gives you incoming-call and SMS *events* without any of the SMS/call-log permissions. The Hero app already ships one (`CustomNotificationLisnter`). You lose the clean "missed call" distinction and the reliable number; you'd parse the notification.
- **`READ_PHONE_STATE` alone** still gives RINGING/IDLE transitions, so you can count missed calls and drive op 5 and op 7 — you just can't fill in the *number* for op 2. Sending a constant like `CALL` in op 2 would still light the console's call alert.

Given your "just the 5 features" goal, the honest minimum that keeps the number visible is: `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `READ_PHONE_STATE`, `READ_CALL_LOG`, `RECEIVE_SMS`, `POST_NOTIFICATIONS`.

---

## 6. Minimal implementation plan

1. **Foreground service** (`connectedDevice` type) holding the `BluetoothGatt`. The link dies with the process otherwise — this is why the Hero app nags about battery optimisation.
2. **Scan with no `ScanFilter` at all**, and classify results yourself. A device
   is not obliged to put its GATT services in the 31-byte advertisement, and the
   cluster does not: filtering the scan on `FEFB` rejects the one device you are
   looking for. Treat a result as a console if its advertised service UUIDs
   contain `FEFB` **or** its name begins with `HERO`, and keep a "show
   everything" escape hatch, because the name comes from Hero's backend and can
   differ. Confirm by connecting: `FEFB` is only reliably visible after service
   discovery.
3. **Connect → discover → MTU 65 → CCCD indicate 0004 → CCCD notify 0002 → write uint8 10 to 0003.** Wait for console command `53`.
4. **Credit management:** re-grant credits on 0003 as they drain. Start with 10, top up on each notification burst.
5. **Sources → opcodes:**
   - `ACTION_BATTERY_CHANGED` → bucket → op 3 (send on change, not on a timer)
   - `PHONE_STATE` RINGING → start 1 Hz op 2; IDLE → stop, and if no OFFHOOK occurred, increment missed count → op 5
   - `SMS_RECEIVED` → increment → op 6
6. **Serialise writes.** One `writeCharacteristic` at a time, wait for `onCharacteristicWrite`. Android's GATT queue silently drops concurrent writes.
7. **Reconnect** on `onConnectionStateChange(DISCONNECTED)` with backoff; the app uses `maxFailure = 10`.

A single Kotlin service of roughly 400–600 lines covers all of this.

---

## 7. Hidden / extra capability — what's really there

### 7.1 Clock setting: exists in the APK, but **not for this console**

`com.otcengineering.otcble.blefeaturelibrary.internal.BleFunc` has:
```kotlin
fun startTimeSync()   // every 60s, after a 5s delay
fun sendTime()        // packs Smart_Rtc_hours_24 / _Minutes / _Seconds into a bit-packed frame
```
It bit-packs RTC hours/minutes/seconds together with battery %, signal strength and nav ETA into a 6-byte buffer and writes it to a **memory address** from the `MsslAddress`/`JnsAddress` map.

**But** this lives in the OTC/TCU stack (§1b), which the Xtreme 125R does not use. The `ride_guide` cluster protocol has **no time opcode** — the complete opcode set is `{2, 3, 5, 6, 7, 9, nav}` and that is exhaustive: a repo-wide grep shows `SendDataToCluster` and `NavigateFragment` are the **only two** classes in the entire APK that write to the TIO RX characteristic.

The manual agrees — p.24 documents setting the clock with the console's own Mode/Set buttons.

### 7.2 Brightness: not available over BLE, anywhere

No brightness-related command exists in either stack. `BleFunc`'s complete public surface (navigation, call state, music volume, battery, BT enable, time) has nothing for display brightness. The manual (p.26) sets brightness in 20% steps via the Mode button, with the vehicle under 3 km/h — a local-only UI.

### 7.3 Where undiscovered capability could still hide

Three concrete leads, in order of promise:

1. **The gap at console→phone codes 20, 25, 30.** The command codes run 10, 15, 35, 40, 45, 50, 53, 55, 60, 65, 70 — a clean step of 5 with **20, 25 and 30 missing** from the app's dispatch. Those codes very likely exist in the console firmware and are simply unhandled by this app tier. Sniffing an active link (or `btsnoop_hci.log` with Bluetooth HCI logging on) while pressing each handlebar/console button would reveal them.
2. **Phone→console opcodes `0`, `1`, `4`, `8`.** The app uses 2, 3, 5, 6, 7, 9. The unused single-digit opcodes are the obvious place to probe. **Probe carefully** — write one frame at a time and watch the display; a malformed frame at worst gets ignored, but there is no way to know from the app alone whether any opcode touches persistent state.
3. **The console's own GATT table.** Dump every service/characteristic with nRF Connect once bonded. If the console exposes anything beyond `FEFB` + GAP/GATT/DIS, that's undocumented surface. A Device Information Service would at least give you the firmware revision, which is worth recording.

**Realistic expectation:** this cluster is a dumb display driven by a one-way ASCII feed. Time and brightness are deliberately local-only. Plan the app around the 5 features and treat anything from §7.3 as a bonus.

---

## 8. Evidence index

| Finding | File (in `jadx` output of `Hero App.apk`) |
|---|---|
| TIO UUIDs, MTU, credits | `com/customerapp/hero/ride_guide/BluetoothConnectionService.java:113-125, 318` |
| TIO handshake, console→phone commands | `com/customerapp/hero/ride_guide/BluetoothConnectionService$bluetoothGattCallback$1.java:92-472, 660-781` |
| All phone→console frame builders | `com/customerapp/hero/views/fragments/rideGuide/SendDataToCluster.java` |
| Battery bucket ground truth | same method via `jadx --decompilation-mode fallback`, labels `L7f`–`L9b` |
| Feature tiers | `com/modellib/hmc/VdsBleConnectionObj.java`; `com/customerapp/hero/thorwrapper/rideguidewrapper/DashboardWrapper.java:2242` |
| Scan name prefix | `com/customerapp/hero/ride_guide/BluetoothScanService.java:301-313` |
| Call/SMS/battery sources | `com/customerapp/hero/ride_guide/receiver/{PhoneStateReceiver,SmsReceiver,BatteryLevelReciever}.java` |
| OTC TCU stack + time sync | `com/otcengineering/otcble/blefeaturelibrary/internal/{BleFunc,MsslAddress,MemoryAddress}.java` |

### Reproducing

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
export REA_JADX_MCP_JAR="$HOME/.local/share/rea/android/jadx-headless-mcp-0.7.1-all.jar"

rea inspect-android-package "Hero App.apk"
rea search-android-classes "Hero App.apk" SendDataToCluster
rea inspect-android-method "Hero App.apk" \
  com.customerapp.hero.views.fragments.rideGuide.SendDataToCluster sendBatteryNetworkData

# raw bytecode when a decompile looks wrong (duplicated blocks):
jadx --decompilation-mode fallback --single-class \
  com.customerapp.hero.views.fragments.rideGuide.SendDataToCluster classes8.dex -d out
```

---

## 9. Open items to verify on the bike

- [ ] Console advertises/exposes service `FEFB` (nRF Connect, ignition on)
- [x] Advertised name is `JAU02_78A4`, i.e. `<model>_<MAC tail>`, not `HERO_`
- [ ] Dump the full GATT table; record firmware revision if a DIS is present
- [ ] Confirm op 3 battery buckets render as expected (send `\n30<...>` through `\n34<...>`)
- [ ] Confirm op 2 lights the call alert with a bare number
- [ ] Capture console→phone traffic with HCI snoop log; look for codes 20/25/30

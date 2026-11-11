# Knight

A barebone Android app that mirrors four things from your phone onto the
instrument console of a Hero Xtreme 125R: battery level, incoming calls, missed
calls and unread messages.

It does nothing else. No DND, no auto-replies, no service reminders, no
telematics, and **no network code at all** — the app does not hold the
`INTERNET` permission.

## Why it exists

The official Hero app does this, but it is a 140 MB application that asks for 46
permissions, most of them for parts of the product that have nothing to do with
the console. Knight is the console link on its own.

The protocol was reverse-engineered from that app. The full write-up, including
the evidence for every claim, is in
[XTREME-125R-CONSOLE-BLE-SPEC.md](XTREME-125R-CONSOLE-BLE-SPEC.md).

## How it talks to the console

BLE GATT, using a Terminal I/O profile on service
`E837D9A2-9C49-4493-9547-8E6918A59CA8`.

| Role | Characteristic |
|---|---|
| Phone writes app-to-console bytes | `64AECB40-849A-44F1-934F-ADDC4B316423` |
| Console notifies console-to-phone | `B792A4BB-DB87-436A-9066-DB63C5FB3F00` |
| Phone grants credits | `F535DD6E-7975-4ABD-9719-491E38A81179` |
| Console grants credits | `36E8614B-2DED-45C1-9AD3-C9F59A01F21E` |

Hero's app also speaks a Telit-assigned set on `0000FEFB`, chosen at discovery.
Knight does not: the cluster this is written for exposes only the service above,
so a second, untestable code path would be carried for nothing. Only the
addresses differ between the two anyway. Frames, opcodes, MTU and credits are
identical.

Frames are plain ASCII, shaped `\n` + opcode + fields + `\n`:

| Opcode | Frame | Meaning |
|---|---|---|
| `2` | `\n2<number>\n` | Incoming call, resent once a second while ringing |
| `3` | `\n3<batt><signal><name>\n` | Battery as a 0-4 bucket |
| `5` | `\n5<NN>\n` | Missed call count, zero padded |
| `6` | `\n60<count>\n` | Unread messages, note the literal zero |

`Protocol.kt` is the whole encoder and has no Android dependencies, so it is
covered by ordinary JVM unit tests.

## Permissions

Six, each mapping to exactly one thing the console can show:

- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` — find the console, hold the link
- `READ_PHONE_STATE` — call ringing, answered, ended
- `READ_CALL_LOG` — the caller's number, which Android withholds without it
- `RECEIVE_SMS` — arrival events only, message content is never read
- `POST_NOTIFICATIONS` — the ongoing notification that keeps the service alive

`minSdk` is 31 deliberately: from Android 12 the Bluetooth permissions stand on
their own, so the app never asks for location.

## Build

Needs JDK 17 or newer. Android Studio's bundled JDK works:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

Run the protocol tests:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest
```

The release build is signed, minified by R8 and resource shrunk, which takes it
from 22 MB to about 1.5 MB:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleRelease
```

Signing reads `keystore.properties` at the repository root, which points at
`knight-release.jks`. Both are gitignored and neither is in this repository.
Without them the release build still compiles but comes out unsigned. **Keep
that keystore.** An APK signed by a different key cannot upgrade one already
installed; Android makes you uninstall first, which clears the saved console.

## CI

Two workflows.

`ci.yml` runs the protocol tests and builds a debug APK on every push to main
and every pull request. It uses no secrets, so a pull request from a fork works
normally.

`release.yml` runs when a `v*` tag is pushed. It refuses to start without
signing credentials, because an unsigned APK cannot upgrade an installed build
and shipping one would be worse than shipping nothing. It then checks the tag
against `versionName`, builds, verifies the APK really is signed, and attaches
`knight-<version>.apk` to a GitHub release.

It needs four repository secrets:

| Secret | What it holds |
|---|---|
| `KEYSTORE_BASE64` | `knight-release.jks`, base64 encoded |
| `KEYSTORE_PASSWORD` | the `storePassword` from `keystore.properties` |
| `KEY_ALIAS` | the `keyAlias`, which is `knight` |
| `KEY_PASSWORD` | the `keyPassword` |

Set them from a checkout that has the keystore, so the values never pass
through a browser or a terminal history:

```bash
gh secret set KEYSTORE_BASE64 < <(base64 -i knight-release.jks)
gh secret set KEYSTORE_PASSWORD --body "$(sed -n 's/^storePassword=//p' keystore.properties)"
gh secret set KEY_ALIAS --body "$(sed -n 's/^keyAlias=//p' keystore.properties)"
gh secret set KEY_PASSWORD --body "$(sed -n 's/^keyPassword=//p' keystore.properties)"
```

To cut a release: bump `versionCode` and `versionName` in
`app/build.gradle.kts`, commit, then

```bash
git tag -a v1.2 -m "Knight 1.2" && git push origin v1.2
```

The tag and `versionName` must agree or the workflow stops before building.

## Layout

```
app/src/main/java/com/knight/console/
  Protocol.kt      frame encoding and decoding, pure Kotlin
  TioClient.kt     GATT client and the Terminal I/O bring-up sequence
  Scanner.kt       BLE scan, unfiltered, classified after the fact
  LinkService.kt   foreground service: holds the link, watches phone state
  Knight.kt        shared state and persistence
  MainActivity.kt  permissions and wiring
  ui/              Compose screens
icon/              app icon sources and where each file goes
```

## The link

Three states, and nothing else:

| State | How you get there | What it does |
|---|---|---|
| Connected | Pick a console, or tap Connect | Holds the link, mirrors what is switched on |
| Disconnected | Tap Disconnect | Stops, and stays stopped |
| Link lost | Ignition off, out of range, radio drop | Retries until it is back, or until you disconnect |

The third one is the interesting case. One direct retry covers a momentary
blip, and after that the connection is handed to the Bluetooth controller with
`autoConnect = true`, which waits indefinitely and costs nothing while waiting,
because the radio does the watching rather than the app. There is no attempt
ceiling: switch the ignition on an hour later, with the phone asleep in a
pocket, and the link comes back without the app being opened.

Battery is restated every thirty seconds. The cluster shows one thing at a
time, so a call or message alert takes the display, and without a heartbeat the
battery was stated once per link and never seen again. The heartbeat pauses
while a call is ringing, which has its own once a second repeat.

What gets mirrored can only be changed while disconnected. The cluster keeps
whatever it was last sent and the protocol has no frame that takes something
back off the dial, so the toggles apply on connect and are greyed out while the
link is up rather than appearing to do nothing.

## Status

Working on the bike. Battery, incoming calls, missed calls and unread messages
have all been confirmed on the cluster of a real `JAU02_78A4`, from a signed
release build, along with pairing by passkey, recovery from a supervision
timeout, and reconnection after the ignition is switched off and on.

The console advertises as `<model>_<last two bytes of its MAC>`, and does not
put its service UUID in the advertisement, so the scan has to be unfiltered and
classified afterwards.

Readiness is the console's own credit grant on `CREDITS_TX`. The Hero app waits
for console command `53` first; this firmware never sends it, so waiting on it
stalls the link forever. Credits arrive about 150 ms after we grant ours.

There is no in-app frame log. The same traffic goes to logcat:

```bash
adb logcat -s Knight
```

Console command codes 20, 25 and 30 are missing from the Hero app's dispatch
table and are the obvious place to look for behaviour nobody ships.

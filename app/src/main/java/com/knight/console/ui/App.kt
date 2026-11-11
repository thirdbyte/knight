package com.knight.console.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knight.console.Found
import com.knight.console.Knight
import com.knight.console.LinkState
import com.knight.console.Mirror

interface Host {
    fun link(device: Found)
    fun reconnect()
    fun disconnect()
    fun forget()
    fun setScanning(on: Boolean)
    fun enableBluetooth()
    fun setMirror(mirror: Mirror)
    fun askPermissions()
    fun openAppSettings()
    fun missingPermissions(): List<String>
}

private enum class Screen { SCAN, LINK }

@Composable
fun App(host: Host) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.Amber,
            background = Ink.Ground,
            surface = Ink.Ground,
            onSurface = Ink.Text,
        )
    ) {
        // targetSdk 35 forces edge to edge on Android 15, so the app owns the
        // inset padding or the header sits under the clock.
        Surface(color = Ink.Ground, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                Router(host)
            }
        }
    }
}

@Composable
private fun Router(host: Host) {
    val console by Knight.console.collectAsState()
    val state by Knight.link.collectAsState()
    val bluetoothOn by Knight.bluetoothOn.collectAsState()
    val wanted by Knight.wanted.collectAsState()
    val missing = host.missingPermissions()

    var screen by remember {
        mutableStateOf(if (console == null) Screen.SCAN else Screen.LINK)
    }

    // Both of these are gates on every composition, not a starting screen
    // chosen once: a permission can be revoked from Settings and the radio
    // turned off while the app sits in the background, and the app is useless
    // in either state whichever screen it happens to be showing. Permissions
    // come first, because asking to enable the adapter needs BLUETOOTH_CONNECT.
    if (missing.isNotEmpty()) {
        PermissionsScreen(host)
        return
    }
    if (!bluetoothOn) {
        BluetoothOffScreen(host)
        return
    }

    when (screen) {
        Screen.SCAN -> ScanScreen(host) {
            host.link(it)
            screen = Screen.LINK
        }

        // One screen for the link in every state. CONNECTING already says
        // the console is not there and the app is working on it, whether that
        // is a first attempt or the radio waiting for the ignition.
        Screen.LINK -> LinkScreen(host) { host.forget(); screen = Screen.SCAN }
    }
}

@Composable
private fun BluetoothOffScreen(host: Host) {
    Column(Modifier.fillMaxSize()) {
        AppBar(
            left = {
                Text("KNIGHT", color = Ink.Faint, fontFamily = Mono, fontSize = MonoSmall, letterSpacing = 2.sp)
            },
            right = { StatusChip("BLUETOOTH OFF", Ink.Red) },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("Bluetooth is off", color = Ink.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Knight reaches the console over Bluetooth. Turn it on to carry on.",
                color = Ink.Dim, fontSize = 13.sp, lineHeight = 20.sp,
            )
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 22.dp)) {
            Filled("Turn on Bluetooth") { host.enableBluetooth() }
        }
    }
}

// ----- first run ------------------------------------------------------------

private data class PermRow(val permission: String, val title: String, val why: String, val constant: String)

@Composable
private fun PermissionsScreen(host: Host) {
    val missing = host.missingPermissions()
    val rows = permissionRows()

    Column(Modifier.fillMaxSize()) {
        AppBar(left = {
            Text("KNIGHT", color = Ink.Faint, fontFamily = Mono, fontSize = MonoSmall, letterSpacing = 2.sp)
        })
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Permissions", color = Ink.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

            Panel {
                rows.forEachIndexed { index, row ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Hair))
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.title, color = Ink.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(row.why, color = Ink.Dim, fontSize = 12.sp, lineHeight = 17.sp)
                            Text(row.constant, color = Ink.Faint, fontFamily = Mono, fontSize = LabelSize)
                        }
                        val granted = row.permission !in missing
                        Text(
                            text = if (granted) "GRANTED" else "NEEDED",
                            color = if (granted) Ink.Amber else Ink.Dim,
                            fontFamily = Mono,
                            fontSize = LabelSize,
                            letterSpacing = 1.sp,
                        )
                    }
                }
            }

        }

        // Outside the scroll, so a tall list never squashes the buttons.
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Filled("Continue") { host.askPermissions() }
            // Android stops showing the dialog after two refusals, which would
            // otherwise leave this screen with no way off it.
            Outline("Open app settings") { host.openAppSettings() }
        }
    }
}

@Composable
private fun permissionRows(): List<PermRow> = remember {
    buildList {
        add(PermRow(android.Manifest.permission.BLUETOOTH_SCAN, "Nearby devices", "Scan for the console and keep the connection open", "BLUETOOTH_SCAN · BLUETOOTH_CONNECT"))
        add(PermRow(android.Manifest.permission.READ_PHONE_STATE, "Phone", "Detect when a call rings, is answered or ends", "READ_PHONE_STATE"))
        add(PermRow(android.Manifest.permission.READ_CALL_LOG, "Call logs", "Required for the caller's number. Android withholds it otherwise.", "READ_CALL_LOG"))
        add(PermRow(android.Manifest.permission.RECEIVE_SMS, "SMS", "Counts arrivals. Message content is not read.", "RECEIVE_SMS"))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            add(PermRow(android.Manifest.permission.POST_NOTIFICATIONS, "Notifications", "Required for the ongoing notification that keeps the service running", "POST_NOTIFICATIONS"))
        }
    }
}

@Composable
private fun ScanScreen(host: Host, onPick: (Found) -> Unit) {
    val scanning by Knight.scanning.collectAsState()
    val found by Knight.found.collectAsState()
    val error by Knight.scanError.collectAsState()

    val consoles = found.filter { it.isConsole }

    Column(Modifier.fillMaxSize()) {
        AppBar(
            left = {
                Text("KNIGHT", color = Ink.Faint, fontFamily = Mono, fontSize = MonoSmall, letterSpacing = 2.sp)
            },
            right = {
                if (scanning) StatusChip("SCANNING", Ink.Amber)
                else StatusChip("STOPPED", Ink.Faint, dot = Ink.Edge)
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Find your console", color = Ink.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Turn the ignition on. Your console will appear below.",
                    color = Ink.Dim, fontSize = 13.sp, lineHeight = 20.sp,
                )
            }

            error?.let { message ->
                Text(message, color = Ink.Red, fontFamily = Mono, fontSize = MonoSmall)
            }

            consoles.forEach { device -> ConsoleCard(device) { onPick(device) } }

            if (consoles.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            color = Ink.Amber, strokeWidth = 2.dp, modifier = Modifier.size(44.dp),
                        )
                    } else {
                        Text(
                            "NOTHING YET", color = Ink.Faint, fontFamily = Mono,
                            fontSize = MonoSmall, letterSpacing = 1.8.sp,
                        )
                    }
                }
            }
        }

        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 22.dp)) {
            if (scanning) Outline("Stop scanning") { host.setScanning(false) }
            else Filled("Start scanning") { host.setScanning(true) }
        }
    }
}

@Composable
private fun ConsoleCard(device: Found, onClick: () -> Unit) {
    val known = device.isConsole
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (known) Ink.AmberSkin else Ink.Panel, RoundedCornerShape(10.dp))
            .border(1.dp, if (known) Ink.AmberEdge else Ink.Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                device.name,
                color = if (known) Ink.Text else Ink.Dim,
                fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
            Text(device.mac, color = Ink.Faint, fontFamily = Mono, fontSize = MonoSmall)
        }
        Text(
            "${device.rssi} dBm",
            color = if (known) Ink.Text else Ink.Faint,
            fontFamily = Mono, fontSize = MonoSmall,
        )
    }
}

// ----- the app ---------------------------------------------------------------

@Composable
private fun LinkScreen(host: Host, onForget: () -> Unit) {
    val console by Knight.console.collectAsState()
    val state by Knight.link.collectAsState()
    val mirror by Knight.mirror.collectAsState()
    val wanted by Knight.wanted.collectAsState()

    Column(Modifier.fillMaxSize()) {
        AppBar(
            left = { Text("KNIGHT", color = Ink.Faint, fontFamily = Mono, fontSize = MonoSmall, letterSpacing = 2.sp) },
            right = {
                when {
                    !wanted -> StatusChip("DISCONNECTED", Ink.Faint, dot = Ink.Edge)
                    state == LinkState.LINKED -> StatusChip("LINKED", Ink.Amber)
                    else -> StatusChip("CONNECTING", Ink.Faint, dot = Ink.Edge)
                }
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    console?.name ?: "No console",
                    color = Ink.Text, fontFamily = Mono, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                )
                Text(console?.mac.orEmpty(), color = Ink.Faint, fontFamily = Mono, fontSize = MonoSmall)
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("SHOW ON THE CONSOLE")
                // The console keeps whatever it was last sent, and the
                // protocol has no frame that takes something back off the
                // dial. These therefore apply on connect, and stay locked
                // while the link is up rather than appearing to do nothing.
                val editable = !wanted
                Panel {
                    ToggleRow("Battery level", mirror.battery, false, editable) { host.setMirror(mirror.copy(battery = it)) }
                    ToggleRow("Incoming calls", mirror.incomingCalls, true, editable) { host.setMirror(mirror.copy(incomingCalls = it)) }
                    ToggleRow("Missed calls", mirror.missedCalls, true, editable) { host.setMirror(mirror.copy(missedCalls = it)) }
                    ToggleRow("Unread messages", mirror.unreadSms, true, editable) { host.setMirror(mirror.copy(unreadSms = it)) }
                }
                Text(
                    if (editable) "These apply when you connect."
                    else "Disconnect to change what the console shows.",
                    color = Ink.Faint, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        }

        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (wanted) Outline("Disconnect") { host.disconnect() }
            else Filled("Connect") { host.reconnect() }
            Danger("Forget this console", onForget)
        }
    }
}

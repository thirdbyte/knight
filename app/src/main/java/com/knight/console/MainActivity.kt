package com.knight.console

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.knight.console.ui.App
import com.knight.console.ui.Host

class MainActivity : ComponentActivity() {

    private lateinit var scanner: Scanner
    private val missing = mutableStateOf<List<String>>(emptyList())

    private val askFor = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refresh()
        resumeWork()
    }

    private val askBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshBluetooth()
        resumeWork()
    }

    private val bluetoothWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshBluetooth()
            if (Knight.bluetoothOn.value) {
                // Covers the radio being switched on from quick settings
                // rather than from our own button.
                resumeWork()
            } else {
                scanner.stop()
                Knight.scanning.value = false
            }
        }
    }

    private val host = object : Host {

        override fun link(device: Found) {
            setScanning(false)
            Knight.wanted.value = true
            Knight.remember(this@MainActivity, device)
            LinkService.start(this@MainActivity, device.mac)
        }

        override fun reconnect() {
            Knight.wanted.value = true
            Knight.console.value?.let { LinkService.start(this@MainActivity, it.mac) }
        }

        override fun disconnect() {
            Knight.wanted.value = false
            LinkService.stop(this@MainActivity)
        }

        override fun forget() {
            Knight.wanted.value = true
            LinkService.stop(this@MainActivity)
            // Read the console before Knight.forget clears it. Forgetting here
            // while Android keeps the bond would leave the console paired in
            // system settings and skip the passkey on the next connect, which
            // is not what "forget" reads as.
            Knight.console.value?.mac?.let { dropBond(it) }
            Knight.forget(this@MainActivity)
            setScanning(true)
        }

        override fun setScanning(on: Boolean) {
            if (on) beginScan() else {
                scanner.stop()
                Knight.scanning.value = false
            }
        }

        override fun enableBluetooth() {
            askBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }

        override fun setMirror(mirror: Mirror) = Knight.saveMirror(this@MainActivity, mirror)

        override fun askPermissions() = askFor.launch(required())

        override fun openAppSettings() {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                )
            )
        }

        override fun missingPermissions(): List<String> = missing.value
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = Scanner(this)
        Knight.load(this)
        refresh()
        refreshBluetooth()
        setContent { App(host) }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            bluetoothWatcher,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        refresh()
        refreshBluetooth()
        resumeWork()
    }

    /**
     * Drops the Android pairing as well as our own record.
     *
     * There is no public API for this. removeBond is an unsupported method on
     * BluetoothDevice, reachable by reflection the same way the GATT cache
     * refresh is. If the platform ever closes it the app still forgets its own
     * side, and the note says the system pairing was left behind.
     */
    private fun dropBond(mac: String) {
        val device: BluetoothDevice = scanner.device(mac) ?: return
        if (device.bondState == BluetoothDevice.BOND_NONE) return
        val removed = runCatching {
            device.javaClass.getMethod("removeBond").invoke(device) as? Boolean ?: false
        }.getOrDefault(false)
        Knight.note(
            if (removed) "Pairing removed" else "Pairing not removed",
            if (removed) mac else "unpair $mac in Bluetooth settings",
        )
    }

    /**
     * Scan when no console is remembered, otherwise hold the link. Called from
     * every point where the app becomes able to work: resume, permissions
     * granted, radio switched on. Starting a scan when a console is already
     * saved would burn the battery looking for something we already have.
     */
    private fun resumeWork() {
        if (missing.value.isNotEmpty() || !Knight.bluetoothOn.value) return
        val console = Knight.console.value
        if (console == null) {
            if (!Knight.scanning.value) beginScan()
        } else if (Knight.wanted.value && Knight.link.value != LinkState.LINKED) {
            // Covers IDLE, DOWN and a CONNECTING left stranded by the radio
            // going away. ACTION_START resets the retry budget.
            LinkService.start(this, console.mac)
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(bluetoothWatcher) }
        scanner.stop()
        Knight.scanning.value = false
    }

    private fun beginScan() {
        refreshBluetooth()
        Knight.found.value = emptyList()
        Knight.scanError.value = null
        if (!Knight.bluetoothOn.value) {
            Knight.scanning.value = false
            return
        }
        val started = scanner.start(
            onFound = { device ->
                val current = Knight.found.value
                val existing = current.indexOfFirst { it.mac == device.mac }
                Knight.found.value = when {
                    existing < 0 -> current + device
                    // Keep the better reading, and never downgrade a console.
                    else -> current.toMutableList().also {
                        val old = it[existing]
                        it[existing] = device.copy(isConsole = device.isConsole || old.isConsole)
                    }
                }
            },
            onFailed = { reason ->
                Knight.scanError.value = reason
                Knight.note("Scan problem", reason)
                Knight.scanning.value = false
            },
        )
        Knight.scanning.value = started
    }

    private fun refresh() {
        missing.value = required().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun refreshBluetooth() {
        Knight.bluetoothOn.value = scanner.bluetoothOn
    }

    private fun required(): Array<String> = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
}

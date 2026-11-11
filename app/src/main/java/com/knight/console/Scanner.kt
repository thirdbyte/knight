package com.knight.console

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context

@SuppressLint("MissingPermission")
class Scanner(private val context: Context) {

    private var callback: ScanCallback? = null

    private val adapter
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    fun device(mac: String): BluetoothDevice? = runCatching {
        adapter?.getRemoteDevice(mac)
    }.getOrNull()

    /**
     * Scans with no filter on purpose.
     *
     * A device is under no obligation to put its GATT services in the 31 byte
     * advertisement, and the cluster does not. Filtering the scan on the
     * Terminal I/O UUID therefore rejected the one device we were looking for.
     * Results are classified here instead, after the fact.
     */
    fun start(onFound: (Found) -> Unit, onFailed: (String) -> Unit): Boolean {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            onFailed("Bluetooth is off")
            return false
        }
        stop()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onFound(result.toFound())
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onFound(it.toFound()) }
            }

            override fun onScanFailed(errorCode: Int) = onFailed(describe(errorCode))
        }
        callback = cb

        return try {
            scanner.startScan(null, settings, cb)
            true
        } catch (e: SecurityException) {
            // The usual cause is a BLUETOOTH_SCAN declaration that Android
            // considers incomplete. Surface it rather than showing an empty list.
            onFailed("Scan refused: ${e.message}")
            false
        } catch (e: Exception) {
            onFailed("Scan failed: ${e.message}")
            false
        }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
    }

    private fun ScanResult.toFound(): Found {
        val advertised = runCatching { scanRecord?.deviceName }.getOrNull()
        val bonded = runCatching { device.name }.getOrNull()
        val name = advertised ?: bonded
        val services = scanRecord?.serviceUuids.orEmpty().map { it.uuid }
        return Found(
            name = name ?: "Unnamed",
            mac = device.address,
            rssi = rssi,
            isConsole = looksLikeConsole(name, device.address, services),
        )
    }

    /**
     * Clusters advertise `<model>_<last two bytes of the MAC>`: JAU02_78A4 on a
     * 74:46:B3:B9:78:A4 radio, HERO_21A4 on another. The model part is whatever
     * Hero's backend hands the official app, so matching on it is pointless;
     * matching the MAC tail is not, and it holds across models.
     */
    private fun looksLikeConsole(name: String?, mac: String, services: List<java.util.UUID>): Boolean {
        if (services.any { it == Protocol.SERVICE }) return true
        val label = name ?: return false
        if (!label.contains('_')) return false
        val tail = mac.replace(":", "").takeLast(4)
        return label.substringAfterLast('_').equals(tail, ignoreCase = true)
    }

    private fun describe(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Already scanning"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Could not register the scan"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth internal error"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "This phone cannot scan for BLE"
        5 -> "Out of hardware scan slots"
        6 -> "Scanning too frequently, wait a moment"
        else -> "Scan failed, code $code"
    }
}

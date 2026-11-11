package com.knight.console

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Terminal I/O client.
 *
 * Bring-up order, which the cluster is strict about:
 *   connect, discover, pick the service variant, MTU 65,
 *   indicate on credits TX, notify on data TX, grant the console 10 credits,
 *   then wait for the console to say it is ready (command 53).
 *
 * Every step reports through [onLog] so a failed link leaves a trail in the
 * frame log instead of a blank screen.
 */
@SuppressLint("MissingPermission")
class TioClient(
    private val context: Context,
    private val onState: (State) -> Unit,
    private val onCommand: (Protocol.Console?, ByteArray) -> Unit,
    private val onLog: (String, String) -> Unit = { _, _ -> },
) {

    enum class State { CONNECTING, READY, DISCONNECTED, NO_SERVICE }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private val queue = ArrayDeque<Pair<UUID, ByteArray>>()
    private var busy = false
    private var credits = 0
    private var pending: String? = null

    /**
     * @param auto hands the connection to the Bluetooth controller instead of
     * dialling out now. A direct connect gives up after about thirty seconds;
     * a background one waits indefinitely and costs nothing while it waits,
     * because the controller does the watching. It is how the link comes back
     * on its own when the ignition is switched on with the phone in a pocket.
     */
    fun connect(device: BluetoothDevice, auto: Boolean = false) {
        close()
        onState(State.CONNECTING)
        onLog(
            "Connecting",
            "${device.address} · bond ${bondName(device.bondState)} · ${if (auto) "background" else "direct"}",
        )
        main.post {
            gatt = device.connectGatt(context, auto, callback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    /**
     * Re-arms a background connection after a drop, without tearing the client
     * down. Android cancels the pending connection when the link closes, so it
     * has to be asked again each time.
     */
    fun rearm(): Boolean {
        val g = gatt ?: return false
        return runCatching { g.connect() }.getOrDefault(false)
    }

    fun close() {
        synchronized(queue) {
            queue.clear()
            busy = false
        }
        main.removeCallbacksAndMessages(null)
        pending = null
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    fun send(bytes: ByteArray) {
        val g = gatt ?: return
        synchronized(queue) { queue.addLast(Protocol.DATA_RX to bytes) }
        pump(g)
    }

    // ----- plumbing ---------------------------------------------------------

    private fun pump(g: BluetoothGatt) {
        val next = synchronized(queue) {
            if (busy) null else queue.removeFirstOrNull()?.also { busy = true }
        } ?: return
        if (!write(g, next.first, next.second)) {
            synchronized(queue) { busy = false }
            onLog("Write rejected", short(next.first))
        }
    }

    private fun release(g: BluetoothGatt) {
        synchronized(queue) { busy = false }
        pump(g)
    }

    private fun characteristic(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        g.getService(Protocol.SERVICE)?.getCharacteristic(uuid)

    /** Legacy write for the same reason as [subscribe]: it matches the Hero app. */
    @Suppress("DEPRECATION")
    private fun write(g: BluetoothGatt, uuid: UUID, value: ByteArray): Boolean {
        val c = characteristic(g, uuid) ?: return false
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = value
        return g.writeCharacteristic(c)
    }

    /**
     * Uses the deprecated descriptor write on every API level on purpose. It is
     * what the Hero app does against this exact cluster, and the newer
     * writeDescriptor(descriptor, value) overload behaves differently on some
     * stacks: it can report success and never produce a callback.
     */
    @Suppress("DEPRECATION")
    private fun subscribe(g: BluetoothGatt, uuid: UUID, value: ByteArray) {
        // Logged before anything can return early, so the log always shows
        // whether this step was even reached.
        onLog("Subscribe step", short(uuid))
        val c = characteristic(g, uuid)
        if (c == null) {
            onLog("Missing characteristic", short(uuid))
            return
        }
        val local = g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Protocol.CCCD)
        if (d == null) {
            onLog("Missing CCCD on", short(uuid))
            return
        }
        val kind = if (value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE))
            "indicate" else "notify"
        d.value = value
        val queued = g.writeDescriptor(d)
        onLog("Subscribing ${short(uuid)}", "$kind · local $local · queued $queued · ${props(c)}")
        if (!queued) {
            onLog("Subscribe rejected", short(uuid))
            return
        }
        expect("subscribe ${short(uuid)}") {
            // Some firmware only answers one of the two. If the console ignored
            // an indication request, ask for a plain notification instead.
            if (kind == "indicate") {
                onLog("Retrying as notify", short(uuid))
                subscribe(g, uuid, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
        }
    }

    /** Names the properties a characteristic claims, so a wrong role shows up. */
    private fun props(c: BluetoothGattCharacteristic): String {
        val p = c.properties
        val out = buildString {
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append('R')
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append('W')
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append('w')
            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append('N')
            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append('I')
        }
        return if (out.isEmpty()) "no properties" else out
    }

    /** Logs, and optionally acts, when a step gets no answer. */
    private fun expect(step: String, after: Long = 4_000L, onSilence: () -> Unit = {}) {
        pending = step
        main.postDelayed({
            if (pending == step) {
                onLog("No answer", "stalled at $step")
                pending = null
                onSilence()
            }
        }, after)
    }

    private fun settled(step: String): Boolean {
        if (pending != step) return false
        pending = null
        return true
    }

    /** The hidden cache drop. Unsupported, widely used, harmless when absent. */
    private fun refreshCache(g: BluetoothGatt): Boolean = runCatching {
        g.javaClass.getMethod("refresh").invoke(g) as? Boolean ?: false
    }.getOrDefault(false)

    private fun grant(g: BluetoothGatt, n: Int) {
        credits = n
        synchronized(queue) { queue.addLast(Protocol.CREDITS_RX to byteArrayOf(n.toByte())) }
        pump(g)
    }

    private fun incoming(g: BluetoothGatt, uuid: UUID, value: ByteArray) {
        if (uuid == Protocol.CREDITS_TX) {
            // The console's own credit grant, and on this cluster the only
            // readiness signal there is. Terminal I/O hands out credits when it
            // is willing to receive, which is the thing worth waiting for;
            // command 53 is documented but this firmware never sends it.
            val granted = value.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            onLog("Console credits", "$granted")
            if (settled("console ready")) {
                onLog("Console ready", "credits granted")
                onState(State.READY)
            }
            return
        }
        if (uuid != Protocol.DATA_TX) {
            onLog("From ${short(uuid)}", "${Protocol.hex(value)} · ${Protocol.printable(value)}")
            return
        }
        settled("console ready")
        val command = Protocol.decode(value)
        if (command == Protocol.Console.LINK_READY) {
            onLog("Console ready", "command 53")
            onState(State.READY)
        }
        onCommand(command, value)
        if (--credits <= 2) grant(g, Protocol.CREDITS)
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            onLog(
                "GATT ${stateName(newState)}",
                if (status == BluetoothGatt.GATT_SUCCESS) "ok" else gattError(status),
            )
            when (newState) {
                BluetoothProfile.STATE_CONNECTED ->
                    // Discovering immediately after connecting is the classic
                    // source of status 133 on Android. Give the stack a moment.
                    main.postDelayed({
                        // Android caches the attribute table for bonded devices.
                        // This one was bonded by the Hero app, so the cache was
                        // not necessarily built by us. Drop it and re-read.
                        onLog("Service cache", if (refreshCache(g)) "cleared" else "kept")
                        runCatching { g.discoverServices() }
                    }, 600L)

                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(queue) {
                        queue.clear()
                        busy = false
                    }
                    onState(State.DISCONNECTED)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("Discovery failed", gattError(status))
                return
            }
            onLog("Services discovered", "${g.services.size} found")
            g.services.forEach { service ->
                if (service.uuid != Protocol.SERVICE) {
                    val chars = service.characteristics.joinToString(" ") { short(it.uuid) }
                    onLog(short(service.uuid), if (chars.isEmpty()) "no characteristics" else chars)
                    return@forEach
                }
                // Full attribute dump for the one that matters. A characteristic
                // with no 2902 descriptor cannot be subscribed to at all, which
                // would explain a descriptor write that never answers.
                onLog(short(service.uuid), "${service.characteristics.size} characteristics")
                service.characteristics.forEach { c ->
                    val descriptors = c.descriptors.joinToString(" ") { short(it.uuid) }
                    onLog(
                        "  ${short(c.uuid)}  ${props(c)}",
                        if (descriptors.isEmpty()) "NO DESCRIPTORS" else "desc $descriptors",
                    )
                }
            }

            if (g.getService(Protocol.SERVICE) == null) {
                onLog("Not a console", "no ${short(Protocol.SERVICE)} service here")
                onState(State.NO_SERVICE)
                return
            }
            val missing = listOf(
                Protocol.DATA_RX, Protocol.DATA_TX, Protocol.CREDITS_RX, Protocol.CREDITS_TX,
            ).filter { characteristic(g, it) == null }
            if (missing.isNotEmpty()) {
                onLog("Service incomplete", missing.joinToString(" ") { short(it) })
                onState(State.NO_SERVICE)
                return
            }
            onLog("Console confirmed", "all four characteristics present")

            if (!g.requestMtu(Protocol.MTU)) onLog("MTU request rejected", "using the default")
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            onLog("MTU $mtu", if (status == BluetoothGatt.GATT_SUCCESS) "ok" else gattError(status))
            // Off the callback thread, and with a beat after the MTU exchange.
            // Some stacks drop a descriptor write issued from inside a callback.
            main.postDelayed({
                subscribe(g, Protocol.CREDITS_TX, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            }, 400L)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val target = d.characteristic.uuid
            settled("subscribe ${short(target)}")
            onLog(
                "Subscribed ${short(target)}",
                if (status == BluetoothGatt.GATT_SUCCESS) "ok" else gattError(status),
            )
            if (status != BluetoothGatt.GATT_SUCCESS) return
            when (target) {
                Protocol.CREDITS_TX -> main.postDelayed({
                    subscribe(g, Protocol.DATA_TX, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }, 250L)

                Protocol.DATA_TX -> {
                    onLog("Granting credits", "${Protocol.CREDITS} to the console")
                    grant(g, Protocol.CREDITS)
                    // Readiness normally arrives as the console's credit grant
                    // on CREDITS_TX, handled in incoming(). This covers the case
                    // where it grants none: the link is up either way, so send
                    // and let the cluster speak for itself.
                    expect("console ready", 2_500L) {
                        onLog("No credit grant", "sending anyway")
                        onState(State.READY)
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int,
        ) {
            onLog(
                "Wrote ${short(c.uuid)}",
                if (status == BluetoothGatt.GATT_SUCCESS) "accepted" else gattError(status),
            )
            release(g)
        }

        @Deprecated("Kept for API 31 and 32, which never call the value overload.")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                incoming(g, c.uuid, c.value ?: ByteArray(0))
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = incoming(g, c.uuid, value)
    }

    // ----- readable codes ---------------------------------------------------

    private fun short(uuid: UUID): String {
        val text = uuid.toString().uppercase()
        return if (text.endsWith("-0000-1000-8000-00805F9B34FB")) text.take(8).trimStart('0')
        else text.take(8)
    }

    private fun stateName(state: Int) = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        else -> "disconnected"
    }

    private fun bondName(bond: Int) = when (bond) {
        BluetoothDevice.BOND_BONDED -> "bonded"
        BluetoothDevice.BOND_BONDING -> "bonding"
        else -> "none"
    }

    private fun gattError(status: Int) = when (status) {
        8 -> "status 8, link supervision timeout"
        19 -> "status 19, the console closed the link"
        22 -> "status 22, closed locally"
        34 -> "status 34, connection interval unacceptable"
        62 -> "status 62, could not establish"
        133 -> "status 133, generic failure, often a bonding or timing problem"
        147 -> "status 147, connection cancelled"
        else -> "status $status"
    }
}

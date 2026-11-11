package com.knight.console

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

enum class LinkState { IDLE, CONNECTING, LINKED, DOWN }

data class Found(
    val name: String,
    val mac: String,
    val rssi: Int,
    /** Advertises the Terminal I/O service, or is named like a Hero cluster. */
    val isConsole: Boolean = false,
)

data class Mirror(
    val battery: Boolean = true,
    val incomingCalls: Boolean = true,
    val missedCalls: Boolean = true,
    val unreadSms: Boolean = true,
)

/**
 * Everything the UI watches. One process, one link, so a singleton is honest
 * here and saves wiring a dependency graph through a foreground service.
 */
object Knight {

    val link = MutableStateFlow(LinkState.IDLE)
    val console = MutableStateFlow<Found?>(null)
    
    val scanning = MutableStateFlow(false)
    val found = MutableStateFlow<List<Found>>(emptyList())
    val bluetoothOn = MutableStateFlow(true)

    /**
     * Whether the user wants the link up. Disconnect clears it, and nothing
     * reconnects until they ask. Without this, resuming the activity saw an
     * idle link next to a remembered console and helpfully undid the
     * disconnect the user had just asked for.
     */
    val wanted = MutableStateFlow(true)
    val scanError = MutableStateFlow<String?>(null)
    val mirror = MutableStateFlow(Mirror())

    /**
     * The frame log goes to logcat. There is no in-app console any more, and a
     * list nobody can read is just memory held for nothing. `adb logcat -s
     * Knight` shows the same traffic, with the stack's own timestamps.
     */
    private const val TAG = "Knight"

    fun sent(title: String, bytes: ByteArray) =
        Log.d(TAG, "-> $title · ${Protocol.printable(bytes)}")

    fun received(command: Protocol.Console?, bytes: ByteArray) {
        val code = if (bytes.size >= 3) bytes[2].toInt() and 0xFF else -1
        Log.d(TAG, "<- %s %s 0x%02X".format(command?.label ?: "Unknown command", "·", code))
    }

    fun note(title: String, detail: String) = Log.d(TAG, "$title · $detail")

    // ----- persistence ------------------------------------------------------

    private const val PREFS = "knight"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mac = p.getString("mac", null) ?: return
        console.value = Found(p.getString("name", mac) ?: mac, mac, 0)
        mirror.value = Mirror(
            battery = p.getBoolean("battery", true),
            incomingCalls = p.getBoolean("incomingCalls", true),
            missedCalls = p.getBoolean("missedCalls", true),
            unreadSms = p.getBoolean("unreadSms", true),
        )
    }

    fun remember(context: Context, device: Found) {
        console.value = device
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mac", device.mac)
            .putString("name", device.name)
            .apply()
    }

    fun forget(context: Context) {
        console.value = null
        link.value = LinkState.IDLE
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("mac").remove("name").apply()
    }

    fun saveMirror(context: Context, value: Mirror) {
        mirror.value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("battery", value.battery)
            .putBoolean("incomingCalls", value.incomingCalls)
            .putBoolean("missedCalls", value.missedCalls)
            .putBoolean("unreadSms", value.unreadSms)
            .apply()
    }
}

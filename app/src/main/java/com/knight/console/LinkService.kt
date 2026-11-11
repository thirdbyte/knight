package com.knight.console

import android.annotation.SuppressLint
import android.app.Notification
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.core.content.IntentCompat

/**
 * Holds the GATT link for as long as the app is meant to be mirroring. The link
 * dies with the process otherwise, which is the whole reason this is a
 * foreground service rather than something the activity owns.
 */
@SuppressLint("MissingPermission")
class LinkService : Service() {

    companion object {
        const val ACTION_START = "com.knight.console.START"
        const val ACTION_STOP = "com.knight.console.STOP"
        const val EXTRA_MAC = "mac"

        private const val CHANNEL = "link"
        private const val NOTIFICATION = 1

        fun start(context: Context, mac: String) {
            context.startForegroundService(
                Intent(context, LinkService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_MAC, mac)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LinkService::class.java).setAction(ACTION_STOP)
            )
        }

    }

    private val main = Handler(Looper.getMainLooper())

    /** Separate so cancelling the ring repeat never cancels a pending reconnect. */
    private val ring = Handler(Looper.getMainLooper())

    /** Also separate: the battery heartbeat outlives both of the above. */
    private val beat = Handler(Looper.getMainLooper())
    private lateinit var client: TioClient
    private lateinit var scanner: Scanner

    private var mac: String? = null
    private var attempt = 0
    private var stopping = false
    private var background = false

    private var lastBucket: Char? = null
    private var missed = 0
    private var unread = 0
    private var ringingNumber: String? = null
    private var answered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scanner = Scanner(this)
        client = TioClient(this, ::onLinkState, ::onConsoleCommand, ::onClientLog)
        registerSources()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotice()
        when (intent?.action) {
            ACTION_START -> {
                stopping = false
                mac = intent.getStringExtra(EXTRA_MAC) ?: Knight.console.value?.mac
                attempt = 0
                background = false
                missed = 0
                unread = 0
                lastBucket = null
                connect()
            }

            ACTION_STOP -> {
                stopping = true
                background = false
                stopHeartbeat()
                client.close()
                Knight.link.value = LinkState.IDLE
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        ring.removeCallbacksAndMessages(null)
        beat.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(receiver) }
        client.close()
        scanner.stop()
        super.onDestroy()
    }

    // ----- link -------------------------------------------------------------

    private fun onClientLog(title: String, detail: String) = main.post {
        Knight.note(title, detail)
    }

    private fun connect() {
        val address = mac ?: return
        if (!scanner.bluetoothOn) {
            // Nothing can be attempted with the radio off, and burning retries
            // against a disabled adapter just exhausts the budget before the
            // user turns it back on. onAdapterState resumes from here.
            Knight.note("Bluetooth off", "waiting for the radio")
            Knight.link.value = LinkState.DOWN
            return
        }
        val device = scanner.device(address)
        if (device == null) {
            Knight.note("Cannot resolve device", address)
            Knight.link.value = LinkState.DOWN
            return
        }
        Knight.link.value = LinkState.CONNECTING

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            client.connect(device)
            return
        }

        // The cluster shows a six digit passkey on the odometer line and
        // refuses unencrypted access, so an unbonded GATT connect can only
        // fail. Bond first and let the bond broadcast drive the connect.
        //
        // createBond returns false while the previous ACL is still tearing
        // down, which is exactly the state a reconnect arrives in. Connecting
        // anyway, as this used to, left the link stuck at CONNECTING forever.
        client.close()
        Knight.note("Pairing", "look for a six digit code on the console")
        if (!device.createBond()) {
            Knight.note("Pairing not accepted yet", "the previous link is still closing")
            scheduleReconnect()
        }
    }

    private fun onAdapterState(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_ON -> {
                if (stopping || mac == null) return
                Knight.note("Bluetooth on", "reconnecting")
                // A fresh budget: the attempts spent while the radio was off
                // were never real attempts.
                attempt = 0
                main.removeCallbacksAndMessages(null)
                main.postDelayed({ if (!stopping) connect() }, 1_500L)
            }

            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                background = false
                main.removeCallbacksAndMessages(null)
                client.close()
                stopRinging()
                stopHeartbeat()
                Knight.link.value = LinkState.DOWN
            }
        }
    }

    private fun onBondState(device: BluetoothDevice, state: Int) {
        if (!device.address.equals(mac, ignoreCase = true)) return
        when (state) {
            BluetoothDevice.BOND_BONDED -> {
                Knight.note("Paired", device.address)
                client.connect(device)
            }

            BluetoothDevice.BOND_NONE -> {
                Knight.note("Pairing failed", "wrong code, or the console gave up")
                Knight.link.value = LinkState.DOWN
                if (!stopping) scheduleReconnect()
            }
        }
    }

    private fun onLinkState(state: TioClient.State) = main.post {
        when (state) {
            TioClient.State.CONNECTING -> Knight.link.value = LinkState.CONNECTING

            TioClient.State.READY -> {
                attempt = 0
                background = false
                Knight.link.value = LinkState.LINKED
                lastBucket = null
                pushBattery()
                pushCounts()
                startHeartbeat()
            }

            TioClient.State.NO_SERVICE -> {
                Knight.note("No Terminal I/O service", "this device is not a console")
                Knight.link.value = LinkState.DOWN
            }

            TioClient.State.DISCONNECTED -> {
                Knight.link.value = LinkState.DOWN
                stopRinging()
                stopHeartbeat()
                if (stopping) return@post
                // A background connection is cancelled by the disconnect, so
                // it has to be asked for again rather than scheduled.
                if (background) {
                    if (!client.rearm()) armBackground()
                } else {
                    scheduleReconnect()
                }
            }
        }
    }

    /**
     * One quick direct retry for a momentary blip, then the connection is
     * handed to the controller for good.
     *
     * The old ladder gave up after ten attempts, roughly two minutes. Switch
     * the ignition off, ride away for longer than that, and nothing would ever
     * reconnect: opening the app was the only thing that reset the counter.
     */
    private fun scheduleReconnect() {
        attempt++
        if (attempt > 1) {
            armBackground()
            return
        }
        main.postDelayed({ if (!stopping) connect() }, 2_000L)
    }

    private fun armBackground() {
        val device = mac?.let { scanner.device(it) }
        if (device == null) {
            // The adapter could not hand back a device, usually because it is
            // still coming up. Retrying is the only wrong thing to stop doing.
            main.postDelayed({ if (!stopping) connect() }, 30_000L)
            return
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            // An unbonded device needs the user at the console for the passkey,
            // so there is nothing for the controller to wait on. Keep polling
            // slowly instead of pretending otherwise.
            main.postDelayed({ if (!stopping) connect() }, 30_000L)
            return
        }
        background = true
        Knight.note("Waiting for the console", "the radio reconnects when it powers up")
        client.connect(device, auto = true)
    }

    private fun onConsoleCommand(command: Protocol.Console?, bytes: ByteArray) = main.post {
        Knight.received(command, bytes)
    }

    private fun send(title: String, bytes: ByteArray) {
        if (Knight.link.value != LinkState.LINKED) return
        client.send(bytes)
        Knight.sent(title, bytes)
    }

    // ----- what gets mirrored ----------------------------------------------

    /**
     * Re-states the badges after a link comes up, for whatever is switched on.
     *
     * The cluster decides what to show from what it is sent after a fresh
     * link, and there is no frame that retracts something already on the dial.
     * That is why the toggles are only editable while disconnected: they take
     * effect here, on the next connect.
     */
    private fun pushCounts() {
        val mirror = Knight.mirror.value
        if (mirror.missedCalls && missed > 0) {
            send("$missed missed call${if (missed == 1) "" else "s"}", Protocol.missedCalls(missed))
        }
        if (mirror.unreadSms && unread > 0) {
            send("$unread unread message${if (unread == 1) "" else "s"}", Protocol.unreadSms(unread))
        }
    }

    /**
     * @param force sends even when the bucket has not moved.
     *
     * The bucket check exists so a battery that drifts 58 to 57 does not
     * chatter at the console. It also meant the level was stated exactly once
     * per link: the cluster shows one thing at a time, so the first call or
     * message alert replaced the battery and nothing ever put it back. The
     * heartbeat below is what forces it.
     */
    private fun pushBattery(force: Boolean = false) {
        if (!Knight.mirror.value.battery) return
        val percent = batteryPercent() ?: return
        val bucket = Protocol.batteryBucket(percent)
        if (!force && bucket == lastBucket) return
        lastBucket = bucket
        send("Battery level $percent%", Protocol.battery(percent))
    }

    /**
     * Re-states the battery every half minute, so the dial comes back on its
     * own after an alert has had its turn. Skipped while a call is ringing,
     * which has its own once a second repeat and should not be interrupted.
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        beat.postDelayed(object : Runnable {
            override fun run() {
                if (ringingNumber == null) pushBattery(force = true)
                beat.postDelayed(this, 30_000L)
            }
        }, 30_000L)
    }

    private fun stopHeartbeat() = beat.removeCallbacksAndMessages(null)

    private fun batteryPercent(): Int? {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return level * 100 / scale
    }

    private fun startRinging(number: String) {
        ringingNumber = number
        answered = false
        if (!Knight.mirror.value.incomingCalls) return
        val tick = object : Runnable {
            override fun run() {
                val current = ringingNumber ?: return
                send("Incoming call", Protocol.incomingCall(current))
                ring.postDelayed(this, 1_000L)
            }
        }
        ring.post(tick)
    }

    private fun stopRinging() {
        ringingNumber = null
        ring.removeCallbacksAndMessages(null)
    }

    private fun onPhoneState(state: String?, number: String?) {
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING ->
                if (ringingNumber == null) startRinging(number.orEmpty())

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                answered = true
                stopRinging()
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val wasRinging = ringingNumber != null
                stopRinging()
                if (wasRinging && !answered && Knight.mirror.value.missedCalls) {
                    missed++
                    send("$missed missed call${if (missed == 1) "" else "s"}", Protocol.missedCalls(missed))
                }
                answered = false
            }
        }
    }

    private fun onSms() {
        if (!Knight.mirror.value.unreadSms) return
        unread++
        send("$unread unread message${if (unread == 1) "" else "s"}", Protocol.unreadSms(unread))
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> pushBattery()

                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> onPhoneState(
                    intent.getStringExtra(TelephonyManager.EXTRA_STATE),
                    @Suppress("DEPRECATION")
                    intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER),
                )

                "android.provider.Telephony.SMS_RECEIVED" -> onSms()

                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    onAdapterState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1))

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                    )
                    if (device != null) {
                        onBondState(device, intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1))
                    }
                }
            }
        }
    }

    private fun registerSources() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            addAction("android.provider.Telephony.SMS_RECEIVED")
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    // ----- the badge that keeps us alive ------------------------------------

    private fun startForegroundNotice() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Console link", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Knight")
            .setContentText(Knight.console.value?.name ?: "Console link")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION, notification)
        }
    }
}

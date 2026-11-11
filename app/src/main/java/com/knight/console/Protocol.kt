package com.knight.console

import java.util.UUID

/**
 * The Xtreme 125R cluster speaks Telit Terminal I/O over BLE, carrying ASCII
 * frames of the shape `\n` + opcode + fields + `\n`.
 *
 * Everything here is reverse-engineered from the Hero app; see
 * XTREME-125R-CONSOLE-BLE-SPEC.md in the repository root for the evidence.
 */
object Protocol {

    private fun uuid(text: String) = UUID.fromString(text)

    /**
     * The Terminal I/O service an Xtreme 125R cluster actually exposes,
     * confirmed by a GATT dump of JAU02_78A4: two services, Generic Access and
     * this one.
     *
     * Hero's app also carries a Telit-assigned set on 0000FEFB and picks between
     * the two at discovery. This app deliberately supports only the set below.
     * The bike it is written for does not have the other one, and an untestable
     * second code path is worse than none.
     *
     * Only the addresses differ between the two. Frames, opcodes, MTU and the
     * credit handling are identical, so nothing above this line cares which is
     * in use.
     */
    val SERVICE: UUID = uuid("E837D9A2-9C49-4493-9547-8E6918A59CA8")

    /** Phone writes app-to-console bytes here. */
    val DATA_RX: UUID = uuid("64AECB40-849A-44F1-934F-ADDC4B316423")

    /** Console notifies console-to-phone bytes here. */
    val DATA_TX: UUID = uuid("B792A4BB-DB87-436A-9066-DB63C5FB3F00")

    /** Phone writes the credits it grants the console. */
    val CREDITS_RX: UUID = uuid("F535DD6E-7975-4ABD-9719-491E38A81179")

    /** Console indicates the credits it grants the phone. */
    val CREDITS_TX: UUID = uuid("36E8614B-2DED-45C1-9AD3-C9F59A01F21E")

    val CCCD: UUID = uuid("00002902-0000-1000-8000-00805f9b34fb")

    const val MTU = 65
    const val CREDITS = 10

    /**
     * Five levels, not a percentage. Verified against the raw Dalvik of
     * SendDataToCluster.sendBatteryNetworkData; the plain decompile of that
     * method is wrong because jadx duplicates its blocks.
     */
    fun batteryBucket(percent: Int): Char = when {
        percent <= 20 -> '0'
        percent < 40 -> '1'
        percent < 60 -> '2'
        percent < 80 -> '3'
        else -> '4'
    }

    private val UNSAFE = Regex("[^A-Za-z0-9 ]")

    /** The console accepts letters, digits and spaces only. */
    fun sanitise(text: String, max: Int = 17): String = UNSAFE.replace(text, "").take(max)

    // ----- phone to console -------------------------------------------------

    /** op 3. Battery bucket, signal level, rider name. */
    fun battery(percent: Int, signal: Char = '0', name: String = ""): ByteArray =
        frame("3${batteryBucket(percent)}$signal${sanitise(name, 15)}")

    /** op 2. Resend once a second for as long as the phone is ringing. */
    fun incomingCall(number: String): ByteArray = frame("2${sanitise(number)}")

    /** op 5. Count is zero padded to two digits. */
    fun missedCalls(count: Int): ByteArray =
        frame("5" + if (count > 9) count.toString() else "0$count")

    /** op 6. Note the literal zero after the opcode. */
    fun unreadSms(count: Int): ByteArray = frame("60$count")

    /** Anything else, for probing opcodes the Hero app never sends. */

    private fun frame(body: String): ByteArray = "\n$body\n".toByteArray(Charsets.US_ASCII)

    // ----- console to phone -------------------------------------------------

    /** The console puts its command code in byte 2 of the notification payload. */
    fun decode(payload: ByteArray): Console? {
        if (payload.size < 3) return null
        return Console.of(payload[2].toInt() and 0xFF)
    }

    enum class Console(val code: Int, val label: String) {
        ANSWER_CALL(10, "Answer call"),
        END_CALL(15, "End call"),
        PLAY_PAUSE(35, "Play or pause pressed"),
        NEXT_TRACK(40, "Next track"),
        PREV_TRACK(45, "Previous track"),
        MUTE(50, "Mute"),
        LINK_READY(53, "Console ready"),
        CALL_VOLUME_UP(55, "Call volume up"),
        CALL_VOLUME_DOWN(60, "Call volume down"),
        MUSIC_VOLUME_UP(65, "Music volume up"),
        MUSIC_VOLUME_DOWN(70, "Music volume down");

        companion object {
            fun of(code: Int): Console? = entries.firstOrNull { it.code == code }
        }
    }

    /** Renders a frame the way the log shows it, with newlines made visible. */
    fun printable(bytes: ByteArray): String = buildString {
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            when {
                c == 0x0A -> append("\\n")
                c in 0x20..0x7E -> append(c.toChar())
                else -> append("\\x%02X".format(c))
            }
        }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }
}

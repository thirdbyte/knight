package com.knight.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * The frame encoder is the one piece that has to be right before the bike is
 * ever involved, so it is checked against the spec rather than against a guess.
 */
class ProtocolTest {

    private fun text(bytes: ByteArray) = String(bytes, Charsets.US_ASCII)

    @Test
    fun `battery buckets follow the five level ladder`() {
        // Verified against the raw Dalvik, not the (wrong) plain decompile.
        assertEquals('0', Protocol.batteryBucket(0))
        assertEquals('0', Protocol.batteryBucket(20))
        assertEquals('1', Protocol.batteryBucket(21))
        assertEquals('1', Protocol.batteryBucket(39))
        assertEquals('2', Protocol.batteryBucket(40))
        assertEquals('2', Protocol.batteryBucket(59))
        assertEquals('3', Protocol.batteryBucket(60))
        assertEquals('3', Protocol.batteryBucket(79))
        assertEquals('4', Protocol.batteryBucket(80))
        assertEquals('4', Protocol.batteryBucket(100))
    }

    @Test
    fun `battery frame carries bucket then signal then name`() {
        assertEquals("\n330\n", text(Protocol.battery(78)))
        assertEquals("\n344Apaar\n", text(Protocol.battery(92, '4', "Apaar")))
    }

    @Test
    fun `missed call count is padded to two digits`() {
        assertEquals("\n501\n", text(Protocol.missedCalls(1)))
        assertEquals("\n509\n", text(Protocol.missedCalls(9)))
        assertEquals("\n510\n", text(Protocol.missedCalls(10)))
    }

    @Test
    fun `sms opcode keeps its literal zero`() {
        assertEquals("\n602\n", text(Protocol.unreadSms(2)))
        assertEquals("\n6012\n", text(Protocol.unreadSms(12)))
    }

    @Test
    fun `incoming call strips punctuation and keeps spaces`() {
        assertEquals("\n291 98765 43210\n", text(Protocol.incomingCall("+91 98765 43210")))
    }

    @Test
    fun `caller text is cut at seventeen characters`() {
        val long = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        assertEquals(17, Protocol.sanitise(long).length)
    }

    @Test
    fun `console commands decode from byte two`() {
        assertEquals(Protocol.Console.LINK_READY, Protocol.decode(byteArrayOf(0, 0, 53)))
        assertEquals(Protocol.Console.PLAY_PAUSE, Protocol.decode(byteArrayOf(0, 0, 35)))
        assertEquals(Protocol.Console.ANSWER_CALL, Protocol.decode(byteArrayOf(0, 0, 10)))
    }

    @Test
    fun `the gap at twenty five stays unknown`() {
        // 20, 25 and 30 are missing from the Hero app's dispatch. If one of
        // these ever decodes, the probe found something.
        assertNull(Protocol.decode(byteArrayOf(0, 0, 20)))
        assertNull(Protocol.decode(byteArrayOf(0, 0, 25)))
        assertNull(Protocol.decode(byteArrayOf(0, 0, 30)))
    }

    @Test
    fun `short payloads do not crash the decoder`() {
        assertNull(Protocol.decode(byteArrayOf()))
        assertNull(Protocol.decode(byteArrayOf(10, 51)))
    }

    @Test
    fun `the UUIDs are the ones this cluster exposes`() {
        // Transcribed from serviceTypeValidation in the Hero app and confirmed
        // against a GATT dump of JAU02_78A4.
        assertEquals(UUID.fromString("E837D9A2-9C49-4493-9547-8E6918A59CA8"), Protocol.SERVICE)
        assertEquals(UUID.fromString("64AECB40-849A-44F1-934F-ADDC4B316423"), Protocol.DATA_RX)
        assertEquals(UUID.fromString("B792A4BB-DB87-436A-9066-DB63C5FB3F00"), Protocol.DATA_TX)
        assertEquals(UUID.fromString("F535DD6E-7975-4ABD-9719-491E38A81179"), Protocol.CREDITS_RX)
        assertEquals(UUID.fromString("36E8614B-2DED-45C1-9AD3-C9F59A01F21E"), Protocol.CREDITS_TX)
    }

    @Test
    fun `the four characteristics are distinct`() {
        val all = listOf(Protocol.DATA_RX, Protocol.DATA_TX, Protocol.CREDITS_RX, Protocol.CREDITS_TX)
        assertEquals("a role must not reuse another role's characteristic", 4, all.toSet().size)
    }

    @Test
    fun `the Telit set is not what this app targets`() {
        // Hero's app also speaks 0000FEFB. This one deliberately does not.
        assertNotEquals(UUID.fromString("0000FEFB-0000-1000-8000-00805F9B34FB"), Protocol.SERVICE)
    }

    @Test
    fun `printable shows newlines and hex is byte accurate`() {
        assertEquals("\\n330\\n", Protocol.printable(Protocol.battery(78)))
        assertEquals("0A 33 33 30 0A", Protocol.hex(Protocol.battery(78)))
    }
}

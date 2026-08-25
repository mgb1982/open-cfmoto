package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HuTimeSyncTest {
    private fun req(stamp: String, modelId: Int = 37426, seq: Int = 7): ByteArray {
        val req = ByteArray(45)
        ByteBuffer.wrap(req).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(-2)
            .putInt(modelId)
            .putInt(seq)
            .putInt(0)
        stamp.toByteArray(Charsets.US_ASCII).copyInto(req, 16)
        return req
    }

    @Test
    fun saneStamp_echoesBikeTime() {
        val bike = "2026-07-24 17:09:20.190000000"
        val ack = HuTimeSync.ack(req(bike))
        assertEquals("echo", ack.mode)
        assertEquals(bike, ack.stamp)
        assertEquals(45, ack.payload.size)
        assertEquals(-2, ByteBuffer.wrap(ack.payload).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(bike, String(ack.payload, 16, 29, Charsets.US_ASCII))
    }

    @Test
    fun shortFractionalStamp_stillEchoes() {
        val bike = "2026-08-13 14:05:20.190"
        val raw = ByteArray(16 + bike.length)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(-2).putInt(1).putInt(1).putInt(0)
        bike.toByteArray(Charsets.US_ASCII).copyInto(raw, 16)
        val ack = HuTimeSync.ack(raw)
        assertEquals("echo", ack.mode)
        assertTrue(ack.stamp.startsWith("2026-08-13 14:05:20"))
    }

    @Test
    fun epochStamp_writesPhoneTime() {
        val ack = HuTimeSync.ack(req("1970-01-01 00:00:00.000000000"))
        assertEquals("phone", ack.mode)
        assertTrue(HuTimeSync.isSaneStamp(ack.stamp))
        assertEquals(45, ack.payload.size)
    }

    @Test
    fun shortRequest_writesPhoneTime() {
        val ack = HuTimeSync.ack(ByteArray(0))
        assertEquals("phone", ack.mode)
        assertEquals(45, ack.payload.size)
        assertTrue(ack.stamp.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{9}""")))
    }

    @Test
    fun forcePhone_overwritesSaneBikeStamp() {
        val bike = "2026-07-24 17:09:20.190000000"
        val ack = HuTimeSync.ack(req(bike), forcePhone = true)
        assertEquals("phone", ack.mode)
        assertTrue(HuTimeSync.isSaneStamp(ack.stamp))
        assertFalse(ack.stamp.startsWith("2026-07-24 17:09:20"))
    }

    @Test
    fun isSaneStamp_rejectsJunk() {
        assertFalse(HuTimeSync.isSaneStamp(""))
        assertFalse(HuTimeSync.isSaneStamp("1970-01-01 00:00:00.000000000"))
        assertTrue(HuTimeSync.isSaneStamp("2026-01-01 00:00:00"))
        assertTrue(HuTimeSync.isSaneStamp("2026-01-01 00:00:00.000000000"))
    }
}

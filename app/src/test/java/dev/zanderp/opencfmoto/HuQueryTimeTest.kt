package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class HuQueryTimeTest {
    private val now = 1_786_272_219_244L
    private val madrid = TimeZone.getTimeZone("Europe/Madrid")

    @Test
    fun otherChannelsStayNull() {
        assertNull(HuQueryTime.ackIfZontes(null, now, madrid))
        assertNull(HuQueryTime.ackIfZontes("", now, madrid))
        assertNull(HuQueryTime.ackIfZontes("21312", now, madrid))
        assertNull(HuQueryTime.ackIfZontes("66660742", now, madrid))
    }

    @Test
    fun zontesChannelAddsOemFields() {
        val ack = HuQueryTime.ackIfZontes(" 21340 ", now, madrid)!!
        val body = String(ack.payload, Charsets.UTF_8)
        val currentTime = now + madrid.getOffset(now)
        assertEquals(
            "{\"time\":$now,\"currentTime\":$currentTime," +
                "\"currentTimeZone\":\"Europe/Madrid\",\"dateTime\":\"09.08.2026 12:43:39:244\"}",
            body,
        )
        assertEquals("09.08.2026 12:43:39:244", ack.dateTime)
        assertEquals(currentTime, ack.currentTime)
        assertEquals("Europe/Madrid", ack.timeZone)
    }
}

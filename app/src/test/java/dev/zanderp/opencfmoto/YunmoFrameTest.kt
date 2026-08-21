package dev.zanderp.opencfmoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class YunmoFrameTest {

    @Test
    fun encodeSimple_matchesKnownLayout() {
        val payload = byteArrayOf(7)
        val frame = YunmoFrame.encodeSimple(YunmoFrame.CMD_DISPLAY, payload)
        assertEquals(10, frame.size)
        assertEquals(0xFE.toByte(), frame[0])
        assertEquals(0xFE.toByte(), frame[3])
        assertEquals(160.toByte(), frame[4])
        assertEquals(0, frame[5].toInt())
        assertEquals(1, frame[6].toInt())
        assertEquals(1, frame[7].toInt() and 0xFF)
        assertEquals(7, frame[8].toInt())
        // checksum over bytes [4..8]
        var sum = 0
        for (i in 4..8) sum += frame[i].toInt()
        assertEquals(sum and 0xFF, frame[9].toInt() and 0xFF)
    }

    @Test
    fun encodeSimple_roundTripsThroughRead() {
        val frame = YunmoFrame.encodeSimple(176, byteArrayOf(7))
        val parsed = YunmoFrame.readSimple(ByteArrayInputStream(frame))
        assertNotNull(parsed)
        assertEquals(176, parsed!!.cmd)
        assertArrayEquals(byteArrayOf(7), parsed.payload)
    }

    @Test
    fun encodeH264Ex_padsAndSetsCmd29() {
        val h264 = ByteArray(50) { it.toByte() }
        val wire = YunmoFrame.encodeH264Ex(h264, width = 800, height = 480, frameId = 42)
        assertEquals(29, wire[4].toInt() and 0xFF)
        assertEquals(0xFE.toByte(), wire[0])
        // padded 50 → 64; total = 64 + 40 = 104
        assertEquals(104, wire.size)
        // block count = (64+32)/32 = 3
        assertEquals(0, wire[5].toInt())
        assertEquals(3, wire[6].toInt())
        assertEquals(3, wire[7].toInt() and 0xFF)
        // length LE at 8, checksum LE at 12
        assertEquals(50, wire[8].toInt() and 0xFF)
        assertEquals(0, wire[9].toInt())
        // template flags at 14/15 (mediaType default = legacy 2)
        assertEquals(0, wire[14].toInt())
        assertEquals(YunmoFrame.MEDIA_TYPE_LEGACY, wire[15].toInt())
        // OEM map header: [6]=0 [7]=2, [8..31] stay zero (aside from Trans_Ins_Ex [0..5]).
        val mapWire = YunmoFrame.encodeH264Ex(
            h264, 800, 480, frameId = 99, oemMapHeader = true,
        )
        assertEquals(0, mapWire[14].toInt())
        assertEquals(2, mapWire[15].toInt())
        assertTrue(mapWire.sliceArray(16 until 40).all { it == 0.toByte() })
        val idr = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x65, 0x00)
        assertEquals(YunmoFrame.MEDIA_TYPE_IDR, YunmoFrame.mediaTypeFor(idr))
        // frameId at 16
        assertEquals(42, wire[16].toInt() and 0xFF)
        // width/height at 20/22
        assertEquals(800 and 0xFF, wire[20].toInt() and 0xFF)
        assertEquals(800 shr 8, wire[21].toInt() and 0xFF)
        assertEquals(480 and 0xFF, wire[22].toInt() and 0xFF)
        // payload starts at 40
        assertEquals(0, wire[40].toInt())
        assertEquals(49, wire[40 + 49].toInt())
        assertTrue(wire.sliceArray(40 + 50 until wire.size).all { it == 0.toByte() })
    }

    @Test
    fun encodeJpegEx_mediaType0AndFrameId() {
        val jpeg = ByteArray(40) { (it + 3).toByte() }
        val wire = YunmoFrame.encodeJpegEx(jpeg, frameId = 7)
        assertEquals(29, wire[4].toInt() and 0xFF)
        assertEquals(0, wire[14].toInt())
        assertEquals(YunmoFrame.MEDIA_TYPE_JPEG, wire[15].toInt())
        assertEquals(7, wire[16].toInt() and 0xFF)
        assertEquals(40, wire[8].toInt() and 0xFF)
        assertEquals(3, wire[40].toInt())
    }

    @Test
    fun defaultEndpoint_isSoftAp8200() {
        assertEquals("192.168.4.1", YunmoFrame.DEFAULT_HOST)
        assertEquals(8200, YunmoFrame.DEFAULT_PORT)
    }

    @Test
    fun annexBNalType_detectsSpsAndIdr() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x00, 0x01)
        val shortSc = byteArrayOf(0, 0, 1, 0x41, 0x00)
        assertEquals(7, YunmoFrame.annexBNalType(sps))
        assertEquals(5, YunmoFrame.annexBNalType(idr))
        assertEquals(1, YunmoFrame.annexBNalType(shortSc))
        assertEquals("SPS", YunmoFrame.nalTypeName(7))
        assertEquals("IDR", YunmoFrame.nalTypeName(5))
    }

    @Test
    fun sendWindow_matchesOemBurstLimit() {
        assertEquals(3, YunmoFrame.SEND_WINDOW)
    }

    @Test
    fun parseOkDimension_xCape1200Payload() {
        // From djcacho vc41: cmd 0x32 len=8 → 00 00 00 00 04 00 01 d0
        val payload = byteArrayOf(0, 0, 0, 0, 0x04, 0x00, 0x01, 0xd0.toByte())
        val dim = YunmoFrame.parseOkDimension(payload)!!
        assertEquals(1024, dim.reportedW)
        assertEquals(464, dim.reportedH)
        assertEquals(2048, dim.mapsW)
        assertEquals(928, dim.mapsH)
        val (ew, eh) = YunmoFrame.encodeSizeFrom(dim, 800, 480)
        // Live canvas is the reported size (OEM NaviVirtualDisplay), not reported×2.
        assertEquals(1024, ew)
        assertEquals(464, eh)
    }

    @Test
    fun splitAnnexB_extractsSpsPpsAndStripsFromIdr() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xce.toByte())
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte())
        val cfg = sps + pps
        val (es, ep) = YunmoFrame.extractSpsPps(cfg)
        assertArrayEquals(sps, es)
        assertArrayEquals(pps, ep)
        val combined = sps + pps + idr
        assertArrayEquals(idr, YunmoFrame.stripLeadingSpsPps(combined))
        assertEquals(3, YunmoFrame.splitAnnexB(combined).size)
    }
}

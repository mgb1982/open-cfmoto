// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.io.InputStream
import java.io.OutputStream

/**
 * Yunmo / Ligo SoftAP framing used by MOTOMORINI (X-Cape 1200) instead of EasyConn.
 *
 * Reverse-engineered from `com.yunmo.comunication.wifi.CommunicationService`:
 * - Simple cmds: [FE×4][cmd][len_be u16][len_ck][payload…][checksum]
 * - Extended H264 (cmd [CMD_H264_EX]): 8-byte outer header + 32-byte subheader + padded payload
 *   (no trailing checksum). Len field is **32-byte block count** of (subheader+payload).
 */
object YunmoFrame {
    const val SYNC: Byte = 0xFE.toByte()
    const val CMD_H264_EX: Int = 29          // tu.G — MediaCodec live / mirror
    const val CMD_DISPLAY: Int = 160         // 0xA0 — display mode + bike frame ACK
    const val CMD_DISPLAY_ALT: Int = 176     // 0xB0 — mirror start companion
    const val CMD_OK_A: Int = 50             // 0x32
    const val CMD_OK_B: Int = 51             // 0x33
    const val CMD_ERR: Int = 52              // 0x34

    const val DEFAULT_HOST = "192.168.4.1"
    const val DEFAULT_PORT = 8200            // MapService.initDeviceConfig WifiSocketConfig

    /** Display sub-cmds (payload byte0) seen in MOTOMORINI live threads. */
    const val DISP_SIMPLE_NAVI: Int = 5     // compact turn arrows in hub
    const val DISP_MAP_NAVI: Int = 6        // full-screen map navigation (Ride MO MapNaviType)
    const val DISP_START_MIRROR: Int = 7   // phone/full-screen mirror projection
    const val DISP_EXIT_A: Int = 3
    const val DISP_EXIT_B: Int = 5

    /** OEM ImageReader path keeps at most ~2 unacked frames (`curr - handled < 3`). */
    const val SEND_WINDOW: Int = 3

    data class Parsed(val cmd: Int, val payload: ByteArray)

    /** First NAL unit type in an Annex-B access unit (1=P, 5=IDR, 7=SPS, 8=PPS), or -1. */
    fun annexBNalType(au: ByteArray): Int {
        var i = 0
        while (i + 3 < au.size) {
            if (au[i] == 0.toByte() && au[i + 1] == 0.toByte()) {
                val sc = when {
                    au[i + 2] == 1.toByte() -> 3
                    au[i + 2] == 0.toByte() && i + 4 < au.size && au[i + 3] == 1.toByte() -> 4
                    else -> 0
                }
                if (sc > 0) {
                    val nal = au[i + sc].toInt() and 0x1F
                    if (nal != 0) return nal
                    i += sc
                    continue
                }
            }
            i++
        }
        return -1
    }

    fun nalTypeName(nal: Int): String = when (nal) {
        1 -> "P"
        5 -> "IDR"
        7 -> "SPS"
        8 -> "PPS"
        else -> "NAL$nal"
    }

    /** Ride MO map path: SPS/PPS → media type 15; IDR → 5; P → 1. Legacy mirror used 2. */
    const val MEDIA_TYPE_JPEG: Int = 0
    const val MEDIA_TYPE_P: Int = 1
    const val MEDIA_TYPE_IDR: Int = 5
    const val MEDIA_TYPE_CODEC_CONFIG: Int = 15
    const val MEDIA_TYPE_LEGACY: Int = 2

    /** True if any NAL in the Annex-B AU has type [want]. */
    fun annexBContainsNal(au: ByteArray, want: Int): Boolean {
        var i = 0
        while (i + 3 < au.size) {
            if (au[i] == 0.toByte() && au[i + 1] == 0.toByte()) {
                val sc = when {
                    au[i + 2] == 1.toByte() -> 3
                    au[i + 2] == 0.toByte() && i + 4 < au.size && au[i + 3] == 1.toByte() -> 4
                    else -> 0
                }
                if (sc > 0) {
                    val nal = au[i + sc].toInt() and 0x1F
                    if (nal == want) return true
                    i += sc + 1
                    continue
                }
            }
            i++
        }
        return false
    }

    /**
     * Yunmo subheader media type for an access unit (Ride MO map-nav classification).
     * Codec-config-only → 15; any IDR → 5; else P/legacy.
     */
    fun mediaTypeFor(au: ByteArray): Int {
        if (annexBContainsNal(au, 5)) return MEDIA_TYPE_IDR
        val first = annexBNalType(au)
        if (first == 7 || first == 8) return MEDIA_TYPE_CODEC_CONFIG
        if (first == 1) return MEDIA_TYPE_P
        return MEDIA_TYPE_LEGACY
    }

    /** One Annex-B NAL including its start code. */
    data class AnnexBNal(val type: Int, val bytes: ByteArray)

    /** Split an Annex-B access unit into individual NALs (start code preserved). */
    fun splitAnnexB(au: ByteArray): List<AnnexBNal> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < au.size) {
            if (au[i] == 0.toByte() && au[i + 1] == 0.toByte()) {
                val sc = when {
                    au[i + 2] == 1.toByte() -> 3
                    au[i + 2] == 0.toByte() && i + 4 < au.size && au[i + 3] == 1.toByte() -> 4
                    else -> 0
                }
                if (sc > 0) {
                    starts.add(i)
                    i += sc
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return emptyList()
        val out = ArrayList<AnnexBNal>(starts.size)
        for (idx in starts.indices) {
            val start = starts[idx]
            val end = if (idx + 1 < starts.size) starts[idx + 1] else au.size
            val sc = if (start + 3 < au.size && au[start + 2] == 0.toByte()) 4 else 3
            if (start + sc >= end) continue
            val type = au[start + sc].toInt() and 0x1F
            out.add(AnnexBNal(type, au.copyOfRange(start, end)))
        }
        return out
    }

    /** SPS and PPS buffers (each with start code) from a codec-config AU. */
    fun extractSpsPps(cfg: ByteArray): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in splitAnnexB(cfg)) {
            when (nal.type) {
                7 -> if (sps == null) sps = nal.bytes
                8 -> if (pps == null) pps = nal.bytes
            }
        }
        return sps to pps
    }

    /** Drop leading SPS/PPS NALs; return remaining AU (IDR/P) or empty. */
    fun stripLeadingSpsPps(au: ByteArray): ByteArray {
        val nals = splitAnnexB(au)
        if (nals.isEmpty()) return au
        val kept = nals.filter { it.type != 7 && it.type != 8 }
        if (kept.isEmpty()) return ByteArray(0)
        if (kept.size == nals.size) return au
        val size = kept.sumOf { it.bytes.size }
        val out = ByteArray(size)
        var o = 0
        for (n in kept) {
            System.arraycopy(n.bytes, 0, out, o, n.bytes.size)
            o += n.bytes.size
        }
        return out
    }

    /**
     * Ride MO `MediaCodecH264LiveThread`: combined SPS+PPS, then prepend that pair to every IDR.
     * Strips any SPS/PPS already on [au] so we don't double-send.
     */
    fun prependSpsPps(sps: ByteArray?, pps: ByteArray?, au: ByteArray): ByteArray {
        val bare = stripLeadingSpsPps(au)
        val body = if (bare.isNotEmpty()) bare else au
        val prefix = (sps?.size ?: 0) + (pps?.size ?: 0)
        if (prefix == 0) return body
        val out = ByteArray(prefix + body.size)
        var o = 0
        if (sps != null) {
            System.arraycopy(sps, 0, out, o, sps.size)
            o += sps.size
        }
        if (pps != null) {
            System.arraycopy(pps, 0, out, o, pps.size)
            o += pps.size
        }
        System.arraycopy(body, 0, out, o, body.size)
        return out
    }

    /**
     * Ride MO `acquireDimensionByDevice` response to cmd 176 (OK = [CMD_OK_A] / 0x32).
     * Bytes [4..5]/[6..7] are big-endian half-size; Ride MO multiplies by 2 for map render.
     */
    data class DimensionReport(val reportedW: Int, val reportedH: Int) {
        val mapsW: Int get() = reportedW * 2
        val mapsH: Int get() = reportedH * 2
    }

    /** Payload Ride MO sends with cmd 176 to query canvas size. */
    val DIM_QUERY_PAYLOAD: ByteArray = byteArrayOf(1, 0, 1)

    fun parseOkDimension(payload: ByteArray): DimensionReport? {
        if (payload.size < 8) return null
        val w = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        val h = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        if (w < 16 || h < 16 || w > 4096 || h > 4096) return null
        return DimensionReport(w, h)
    }

    /**
     * Ride MO `calculateMapMaxSize`: after reported×2, limit the longest side to [maxSide]
     * while preserving aspect. X‑Cape: 2048×928 → **1904×862** (djcacho vc57 RE).
     */
    fun calculateMapMaxSize(width: Int, height: Int, maxSide: Int = 1904): Pair<Int, Int> {
        val w = width.coerceAtLeast(16)
        val h = height.coerceAtLeast(16)
        val longSide = maxOf(w, h)
        if (longSide <= maxSide) return evenDim(w) to evenDim(h)
        val scale = maxSide.toDouble() / longSide.toDouble()
        return evenDim((w * scale).toInt().coerceAtLeast(16)) to
            evenDim((h * scale).toInt().coerceAtLeast(16))
    }

    private fun evenDim(v: Int): Int = if (v and 1 == 0) v else (v - 1).coerceAtLeast(16)

    /**
     * Pick encoder / VirtualDisplay canvas from a dash [DimensionReport].
     *
     * vc60 (OEM runtime dumpsys): Ride MO `NaviVirtualDisplay` is exactly the reported
     * size (**1024×464** @187 dpi) — not reported×2 / [calculateMapMaxSize] (1904×862).
     * That max-size helper is kept for RE notes but is not the live display path.
     */
    fun encodeSizeFrom(report: DimensionReport?, fallbackW: Int, fallbackH: Int): Pair<Int, Int> {
        if (report == null) return evenDim(fallbackW) to evenDim(fallbackH)
        return evenDim(report.reportedW) to evenDim(report.reportedH)
    }

    fun encodeSimple(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = payload.size
        val out = ByteArray(9 + len)
        out[0] = SYNC; out[1] = SYNC; out[2] = SYNC; out[3] = SYNC
        out[4] = cmd.toByte()
        out[5] = (len shr 8).toByte()
        out[6] = len.toByte()
        out[7] = ((out[5].toInt() and 0xFF) + (out[6].toInt() and 0xFF)).toByte()
        if (len > 0) System.arraycopy(payload, 0, out, 8, len)
        // Match CommunicationService.Trans_Ins: signed accumulate of bytes [4 .. 7+len].
        var sum = 0
        for (i in 4 until 8 + len) sum += out[i].toInt()
        out[8 + len] = (sum and 0xFF).toByte()
        return out
    }

    /**
     * Extended H264 frame matching [CommunicationService.Trans_Ins_Ex] + media header layout.
     *
     * Subheader (32 bytes at offset 8):
     * - Trans_Ins_Ex overwrites [0..5] with length (LE u32) + payload checksum (LE u16).
     * - [6]=0, [7]=2 always (Ride MO).
     * - Mirror / projection path may fill frameId + width/height at [8..15].
     * - OEM **map** path ([MediaCodecH264SplitLiveThread]) leaves [8..31] **zero** — frameId /
     *   width / height are NOT written. Local NAL labels 15/5 are log-only, not on the wire.
     */
    fun encodeH264Ex(
        h264: ByteArray,
        width: Int,
        height: Int,
        frameId: Int,
        mediaType: Int = MEDIA_TYPE_LEGACY,
        /** When true, match OEM map: only [6]=0 [7]=2; no frameId/geometry in [8..31]. */
        oemMapHeader: Boolean = false,
    ): ByteArray {
        val padded = ((h264.size + 31) / 32) * 32
        val total = padded + 40
        val out = ByteArray(total)
        System.arraycopy(h264, 0, out, 40, h264.size)

        out[0] = SYNC; out[1] = SYNC; out[2] = SYNC; out[3] = SYNC
        out[4] = CMD_H264_EX.toByte()
        val blocks = (padded + 32) / 32
        out[5] = (blocks shr 8).toByte()
        out[6] = blocks.toByte()
        out[7] = ((out[5].toInt() and 0xFF) + (out[6].toInt() and 0xFF)).toByte()

        // 32-byte subheader at offset 8 — starts zeroed.
        out[8 + 6] = 0
        out[8 + 7] = mediaType.toByte()
        if (!oemMapHeader) {
            putLe(out, 8 + 8, frameId, 4)
            putLe(out, 8 + 12, width, 2)
            putLe(out, 8 + 14, height, 2)
            out[8 + 16] = 0
        }

        // Trans_Ins_Ex overwrites subheader[0..5] with length + payload checksum.
        putLe(out, 8, h264.size, 4)
        var sum = 0
        for (b in h264) sum += b.toInt() and 0xFF
        putLe(out, 12, sum and 0xFFFF, 2)

        return out
    }

    /**
     * JPEG still on the same cmd-29 / Trans_Ins_Ex wrapper. Media type 0 + frameId at
     * subheader[8]; no width/height. X-Cape 1200 paints these and ignores H.264.
     */
    fun encodeJpegEx(jpeg: ByteArray, frameId: Int): ByteArray {
        val padded = ((jpeg.size + 31) / 32) * 32
        val total = padded + 40
        val out = ByteArray(total)
        System.arraycopy(jpeg, 0, out, 40, jpeg.size)

        out[0] = SYNC; out[1] = SYNC; out[2] = SYNC; out[3] = SYNC
        out[4] = CMD_H264_EX.toByte()
        val blocks = (padded + 32) / 32
        out[5] = (blocks shr 8).toByte()
        out[6] = blocks.toByte()
        out[7] = ((out[5].toInt() and 0xFF) + (out[6].toInt() and 0xFF)).toByte()

        out[8 + 6] = 0
        out[8 + 7] = MEDIA_TYPE_JPEG.toByte()
        putLe(out, 8 + 8, frameId, 4)

        putLe(out, 8, jpeg.size, 4)
        var sum = 0
        for (b in jpeg) sum += b.toInt() and 0xFF
        putLe(out, 12, sum and 0xFFFF, 2)
        return out
    }

    fun write(out: OutputStream, frame: ByteArray) {
        synchronized(out) {
            out.write(frame)
            out.flush()
        }
    }

    /**
     * Read one simple Yunmo frame (phone←bike). Returns null on EOF / short read.
     * Extended outbound frames are not expected on this path.
     */
    fun readSimple(input: InputStream): Parsed? {
        val hdr = ByteArray(8)
        if (!readFully(input, hdr)) return null
        // Resync on FE FE FE FE with cmd != FE.
        var guard = 0
        while (!(hdr[0] == SYNC && hdr[1] == SYNC && hdr[2] == SYNC && hdr[3] == SYNC && hdr[4] != SYNC)) {
            System.arraycopy(hdr, 1, hdr, 0, 7)
            val n = input.read()
            if (n < 0) return null
            hdr[7] = n.toByte()
            if (++guard > 4096) return null
        }
        val len = ((hdr[5].toInt() and 0xFF) shl 8) or (hdr[6].toInt() and 0xFF)
        if (((hdr[5].toInt() and 0xFF) + (hdr[6].toInt() and 0xFF) and 0xFF) != (hdr[7].toInt() and 0xFF)) {
            return null
        }
        if (len < 0 || len > 10240) return null
        val payload = ByteArray(len)
        if (len > 0 && !readFully(input, payload)) return null
        val ck = input.read()
        if (ck < 0) return null
        var sum = 0
        for (i in 4 until 8) sum += hdr[i].toInt() and 0xFF
        for (b in payload) sum += b.toInt() and 0xFF
        if ((sum and 0xFF) != (ck and 0xFF)) return null
        return Parsed(hdr[4].toInt() and 0xFF, payload)
    }

    private fun putLe(buf: ByteArray, offset: Int, value: Int, bytes: Int) {
        var v = value
        for (i in 0 until bytes) {
            buf[offset + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }
}

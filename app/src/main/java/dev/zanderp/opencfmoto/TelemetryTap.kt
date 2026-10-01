// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Listen-only probe for dash telemetry (odometer, trips, range, tyre pressure…). Experimental.
 *
 * The Carbit SDK inside Zontes Smart has a "motorcycle instrument" service (libEPSDK.so:
 * MIServer / C2PMInstService / P2CMInstService, decoders EPMInst::parseMXCBaseInfo and
 * parseTyrePressInfo, 216-field EPMInstDataEnum with ODO, TRIP1/2, RANGE_LEFT, TYREPRESS_*…).
 * MIServer::makePXCService maps the PXC channel-select codes:
 *   0x1070000 → C2PMInstService (car → phone instrument data)
 *   0x1060000 → P2CMInstService (phone → car)
 * on the same :10922 server as CAR_CTRL (0x10000) / CAR_DATA (0x20000). There is also a plain
 * control message ECP_C2P_CARINFO_VEHICLEINFO (0x102b0) that the app forwards to its "tbox".
 *
 * None of these has ever shown up from the ZT125T-X in our logs. This tap only makes sure that if
 * the dash ever opens/sends them, we accept the channel, keep the socket alive and hex-dump what
 * arrives, so the payload format can be worked out. It changes nothing else.
 */
object TelemetryTap {
    const val ENABLED = true

    const val CH_MINST_C2P = 0x1070000
    const val CH_MINST_P2C = 0x1060000
    const val CMD_VEHICLEINFO = 0x102b0

    /** Full hex for the first frames of each cmd, then one sample every [SAMPLE_EVERY]. */
    private const val FULL_DUMPS_PER_CMD = 5
    private const val SAMPLE_EVERY = 50
    private const val MAX_HEX_BYTES = 512

    private val instrumentSockets: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())
    private val seen = ConcurrentHashMap<Int, Int>()

    /**
     * Called first for every inbound :10922 frame. Returns true when the frame belonged to the
     * telemetry probe (it has been answered/logged and must not be dispatched further).
     */
    fun handle(
        tag: String,
        frame: PxcFrame,
        socket: Socket,
        log: (String) -> Unit,
        onChannel: ((Socket, String) -> Unit)?,
    ): Boolean {
        if (!ENABLED) return false
        val out = socket.getOutputStream()
        when {
            frame.cmd == CH_MINST_C2P || frame.cmd == CH_MINST_P2C -> {
                val name = if (frame.cmd == CH_MINST_C2P) "MINST_C2P" else "MINST_P2C"
                instrumentSockets.removeAll { it.isClosed }
                instrumentSockets.add(socket)
                log("[TELEMETRY] *** bike opened instrument channel $name (0x${hex32(frame.cmd)}) " +
                    "from ${socket.inetAddress?.hostAddress}:${socket.port} → ack 0x${hex32(frame.cmd + 1)} " +
                    "len=${frame.payload.size} ${dump(frame.payload)}")
                PxcFrame(frame.cmd + 1, ByteArray(0)).write(out)
                onChannel?.invoke(socket, name)   // proactive heartbeat like CAR_CTRL / CAR_DATA
                return true
            }
            frame.cmd == CMD_VEHICLEINFO -> {
                record(tag, "VEHICLEINFO (0x102b0)", frame, log)
                PxcFrame(CMD_VEHICLEINFO + 1, ByteArray(0)).write(out)
                return true
            }
            socket in instrumentSockets -> {
                if (frame.cmd == PxcFrame.CMD_HEARTBEAT) {
                    PxcFrame(PxcFrame.CMD_HEARTBEAT_ACK, ByteArray(0)).write(out)
                    return true
                }
                if (frame.cmd == PxcFrame.CMD_HEARTBEAT_ACK) return true
                record(tag, "instrument frame", frame, log)
                // Carbit pattern: every request gets cmd+1. Empty ack keeps the dash from retrying
                // or dropping the channel; logged so a bad guess is visible.
                PxcFrame(frame.cmd + 1, ByteArray(0)).write(out)
                return true
            }
        }
        return false
    }

    /** Hex-dump unknown control cmds too (the normal log prints them as text only). */
    fun logUnknown(tag: String, frame: PxcFrame, log: (String) -> Unit) {
        if (!ENABLED) return
        record(tag, "unknown ctrl cmd", frame, log)
    }

    private fun record(tag: String, what: String, frame: PxcFrame, log: (String) -> Unit) {
        val n = seen.merge(frame.cmd, 1, Int::plus) ?: 1
        if (n <= FULL_DUMPS_PER_CMD || n % SAMPLE_EVERY == 0) {
            log("[TELEMETRY] [$tag] $what cmd=0x${hex32(frame.cmd)} #$n len=${frame.payload.size} ${dump(frame.payload)}")
        }
    }

    private fun dump(b: ByteArray): String {
        if (b.isEmpty()) return "(empty)"
        val shown = b.copyOf(minOf(b.size, MAX_HEX_BYTES))
        val hex = shown.joinToString("") { "%02x".format(it) } + if (b.size > MAX_HEX_BYTES) "…" else ""
        val ascii = shown.map { val c = it.toInt() and 0xff; if (c in 0x20..0x7e) c.toChar() else '.' }
            .joinToString("")
        return "hex=$hex ascii=\"$ascii\""
    }

    private fun hex32(v: Int) = v.toUInt().toString(16)
}

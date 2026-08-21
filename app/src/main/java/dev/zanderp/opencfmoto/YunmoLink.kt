// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.net.Network
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Phone→bike Yunmo TCP client for SoftAP dashes that speak MOTOMORINI / Yunmo
 * (`192.168.4.1:8200`) instead of EasyConn `:10930`.
 *
 * Connect → query/negotiate canvas (cmd 176 → OK 0x32) → display mode → stream H.264.
 *
 * vc55–58 (Ride MO NaviActivity RE / djcacho):
 * - Wi‑Fi startup: local **MapNaviType** only — **no** initial TX 160/[6] / 0x33 gate.
 *   ResetFrameID → combined LiveThread → VirtualDisplay → H.264.
 * - 160/[5|6] + pending 0x33 is the *later* mode-exchange path only.
 * - Canvas: OEM VirtualDisplay = reported size (X‑Cape **1024×464** @187 dpi).
 * - Combined LiveThread packaging (SPS+PPS + prepend on IDR).
 */
class YunmoLink(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var sock: Socket? = null
    @Volatile private var video: VideoPipeline? = null
    @Volatile private var ownsVideo = false
    @Volatile private var jpegCapturer: YunmoJpegCapturer? = null
    @Volatile private var jpegStills = false
    @Volatile var framesSent: Int = 0
        private set
    @Volatile var lastFrameAt: Long = 0L
        private set
    @Volatile var streamWidth: Int = 0
        private set
    @Volatile var streamHeight: Int = 0
        private set
    @Volatile private var lastAckedId: Int = -1
    /** Sends since last ACK — used when OEM map header zeros frameIds (all ACKs are 0). */
    @Volatile private var unackedSinceAck: Int = 0
    @Volatile private var lastSyncRequestAt: Long = 0L
    /** True once we stream with zeroed map media header (vc47+). */
    @Volatile private var oemMapMode: Boolean = true
    /** FREE_RIDE / owned path wants full-screen map-nav (160/[6]). */
    @Volatile private var mapNavMode: Boolean = false
    /**
     * True when we may send full-map H.264. Wi‑Fi OEM path starts true (already MapNaviType).
     * Cleared only on dash SimpleNavi (160/[5]).
     */
    @Volatile private var mapNavConfirmed: Boolean = false
    /**
     * OEM `handleMapNaviType=Map` after TX 160/[6]. Next generic **0x33** commits state 6 +
     * ResetFrameID (does not open the gate by itself when pending is clear).
     */
    @Volatile private var pendingMapExchange: Boolean = false
    /** Dash selected compact arrows (160/[5]) — pause full-map H.264 until [6]. */
    @Volatile private var simpleNaviActive: Boolean = false
    private val ackLock = Object()
    private val dimLock = Object()
    private val mapNavLock = Object()
    private val pendingDim = AtomicReference<YunmoFrame.DimensionReport?>(null)
    private val frameId = AtomicInteger(0)
    private val rawRecvLogged = AtomicInteger(0)
    private var sendThread: Thread? = null
    private var recvThread: Thread? = null
    var onFrameSent: (() -> Unit)? = null

    /** Ride MO map-nav: request-sync every 1s. */
    private val mapSyncIntervalMs = 1_000L

    val isConnected: Boolean get() = running && sock?.isConnected == true && sock?.isClosed == false

    fun connectAndStream(
        host: Inet4Address,
        network: Network?,
        width: Int,
        height: Int,
        port: Int = YunmoFrame.DEFAULT_PORT,
    ): Boolean {
        stop()
        running = true
        framesSent = 0
        lastAckedId = -1
        unackedSinceAck = 0
        jpegStills = BikeProfileHolder.active.yunmoJpegStills
        oemMapMode = !jpegStills
        mapNavMode = false
        mapNavConfirmed = false
        pendingMapExchange = false
        simpleNaviActive = false
        pendingDim.set(null)
        frameId.set(0)
        rawRecvLogged.set(0)
        streamWidth = 0
        streamHeight = 0
        BikeWifi.rebindProcessToBike(context)

        val s = openSocket(network) ?: run {
            log("[YUNMO] no socket"); running = false; return false
        }
        try {
            log("[YUNMO] connect → ${host.hostAddress}:$port")
            s.connect(InetSocketAddress(host, port), 5_000)
            s.tcpNoDelay = true
            s.soTimeout = 0
            sock = s
        } catch (e: Exception) {
            log("[YUNMO] connect failed: ${e.javaClass.simpleName}: ${e.message}")
            try { s.close() } catch (_: Exception) {}
            running = false
            return false
        }

        val out = BufferedOutputStream(s.getOutputStream())
        val inp = BufferedInputStream(s.getInputStream())

        // Recv first — dim OK, dash 160/[5|6], and ACKs must not race start cmds.
        recvThread = thread(name = "yunmo-recv", isDaemon = true) {
            recvLoop(inp)
        }

        // Ride MO acquireDimensionByDevice: Trans_Ins(176, [1,0,1]) → OK 0x32 with BE size.
        try {
            YunmoFrame.write(
                out,
                YunmoFrame.encodeSimple(YunmoFrame.CMD_DISPLAY_ALT, YunmoFrame.DIM_QUERY_PAYLOAD),
            )
            log("[YUNMO] sent dim-query (176 payload=01 00 01)")
        } catch (e: Exception) {
            log("[YUNMO] dim-query failed: ${e.message}")
            stop()
            return false
        }

        // vc43: 0x32 sometimes arrived ~24ms after a 2s wait — bump so AA doesn't race.
        val dim = awaitDimension(timeoutMs = 5_000)
        if (dim != null) {
            log(
                "[YUNMO] dash canvas reported ${dim.reportedW}x${dim.reportedH} " +
                    "(OEM VirtualDisplay = reported; not ×2/maxSide1904)",
            )
        } else {
            log("[YUNMO] no dim response — using fallback ${width}x${height}")
        }
        val (encW, encH) = YunmoFrame.encodeSizeFrom(dim, width, height)
        val metaW = dim?.reportedW?.coerceAtLeast(16) ?: encW
        val metaH = dim?.reportedH?.coerceAtLeast(16) ?: encH
        streamWidth = encW
        streamHeight = encH
        val dimNote =
            if (dim != null) {
                " (OEM NaviVirtualDisplay = reported ${dim.reportedW}x${dim.reportedH})"
            } else {
                ""
            }
        log(
            "[YUNMO] encode canvas ${encW}x${encH}$dimNote; " +
                if (jpegStills) "JPEG stills (media type 0 + frameId)"
                else "OEM map header (no frameId/w/h in media hdr)",
        )

        // FREE_RIDE / no AA → Wi‑Fi OEM MapNaviType start; AA shared → mirror state 7.
        val preferMapNav = jpegStills || GpxSession.active || AaVideoBridge.pipeline == null
        mapNavMode = preferMapNav
        try {
            if (jpegStills) {
                mapNavConfirmed = true
                pendingMapExchange = false
                frameId.set(0)
                unackedSinceAck = 0
                for (attempt in 1..3) {
                    if (!running) break
                    YunmoFrame.write(
                        out,
                        YunmoFrame.encodeSimple(
                            YunmoFrame.CMD_DISPLAY,
                            byteArrayOf(YunmoFrame.DISP_MAP_NAVI.toByte()),
                        ),
                    )
                    log("[YUNMO] map-nav requested (A0 cmd=6), attempt $attempt/3")
                    try { Thread.sleep(400) } catch (_: InterruptedException) {}
                }
            } else if (preferMapNav) {
                // Ride MO Wi‑Fi startup (vc58): MapNaviType is chosen locally — no initial 160/[6].
                // Bind-equivalent: ResetFrameID → start combined LiveThread / VirtualDisplay.
                // 160/[5|6]+pending 0x33 is only for later mode exchange.
                mapNavConfirmed = true
                pendingMapExchange = false
                frameId.set(0)
                unackedSinceAck = 0
                log(
                    "[YUNMO] Wi‑Fi MapNaviType start (no initial 160/[6]) — ResetFrameID; " +
                        "combined H.264 + VirtualDisplay next (OEM startup)",
                )
            } else {
                YunmoFrame.write(
                    out,
                    YunmoFrame.encodeSimple(
                        YunmoFrame.CMD_DISPLAY_ALT,
                        byteArrayOf(YunmoFrame.DISP_START_MIRROR.toByte()),
                    ),
                )
                YunmoFrame.write(
                    out,
                    YunmoFrame.encodeSimple(
                        YunmoFrame.CMD_DISPLAY,
                        byteArrayOf(YunmoFrame.DISP_START_MIRROR.toByte()),
                    ),
                )
                log("[YUNMO] sent start-mirror (176/160 cmd=7)")
            }
        } catch (e: Exception) {
            log("[YUNMO] start cmds failed: ${e.message}")
            stop()
            return false
        }

        attachVideo(encW, encH)
        try { Thread.sleep(if (preferMapNav) 500 else 150) } catch (_: InterruptedException) {}
        if (!jpegStills) {
            video?.requestKeyframe(
                if (preferMapNav) "yunmo-after-map-navi-type-start" else "yunmo-after-start-mirror",
            )
        }
        ConnectionState.set(
            Phase.PXC_CONNECTING,
            "Yunmo :$port ${encW}x${encH}" + when {
                jpegStills -> " JPEG stills"
                preferMapNav -> " map-nav"
                else -> " mirror"
            },
        )

        sendThread = thread(name = "yunmo-send", isDaemon = true) {
            if (jpegStills) jpegSendLoop(out, encW, encH, port)
            else sendLoop(out, encW, encH, metaW, metaH, port)
        }
        return true
    }

    private fun awaitDimension(timeoutMs: Long): YunmoFrame.DimensionReport? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (running) {
            pendingDim.get()?.let { return it }
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) return pendingDim.get()
            synchronized(dimLock) {
                try {
                    dimLock.wait(remain.coerceAtMost(100L))
                } catch (_: InterruptedException) {
                    return pendingDim.get()
                }
            }
        }
        return pendingDim.get()
    }

    private fun onDashDisplayMode(mode: Int) {
        when (mode) {
            YunmoFrame.DISP_MAP_NAVI -> {
                simpleNaviActive = false
                mapNavMode = true
                mapNavConfirmed = true
                pendingMapExchange = false
                frameId.set(0)
                unackedSinceAck = 0
                synchronized(mapNavLock) { mapNavLock.notifyAll() }
                log("[YUNMO] ← display state 6 (direct MapNaviType) — ResetFrameID")
                video?.requestKeyframe("yunmo-dash-state-6")
            }
            YunmoFrame.DISP_SIMPLE_NAVI -> {
                simpleNaviActive = true
                mapNavConfirmed = false
                pendingMapExchange = false
                log(
                    "[YUNMO] ← display state 5 (SimpleNaviType / compact arrows) — " +
                        "pausing full-map H.264 until MapNaviType",
                )
            }
            else -> {
                log("[YUNMO] ← display cmd=$mode")
            }
        }
    }

    private fun canSendMapH264(): Boolean = mapNavConfirmed

    private fun commitPendingMapExchange(reason: String) {
        if (!pendingMapExchange) return
        pendingMapExchange = false
        simpleNaviActive = false
        mapNavMode = true
        mapNavConfirmed = true
        frameId.set(0)
        unackedSinceAck = 0
        synchronized(mapNavLock) { mapNavLock.notifyAll() }
        log("[YUNMO] $reason — committed pending MapNaviType; ResetFrameID")
        video?.requestKeyframe("yunmo-pending-0x33-commit")
    }

    private fun logRawInbound(frame: YunmoFrame.Parsed) {
        val n = rawRecvLogged.incrementAndGet()
        // First 48 frames fully; then only non-ACK / interesting cmds (avoid spam).
        val mode = if (frame.payload.isNotEmpty()) frame.payload[0].toInt() and 0xFF else -1
        val isAck = frame.cmd == YunmoFrame.CMD_DISPLAY && mode == 0 && frame.payload.size >= 5
        if (n > 48 && isAck) return
        if (n > 48 && (frame.cmd == YunmoFrame.CMD_OK_A || frame.cmd == YunmoFrame.CMD_OK_B) &&
            YunmoFrame.parseOkDimension(frame.payload) == null
        ) {
            // keep occasional 0x33 samples
            if (n % 20 != 0) return
        }
        val hex = frame.payload.take(24).joinToString(" ") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        log(
            "[YUNMO] RAW ← #${n} cmd=0x${frame.cmd.toString(16)} " +
                "(${frame.cmd}) len=${frame.payload.size} hex=[$hex] t=${System.currentTimeMillis()}",
        )
    }

    private fun recvLoop(inp: InputStream) {
        while (running) {
            val frame = try {
                YunmoFrame.readSimple(inp)
            } catch (_: Exception) {
                null
            }
            if (frame == null) {
                if (running) log("[YUNMO] recv ended")
                break
            }
            logRawInbound(frame)
            when {
                frame.cmd == YunmoFrame.CMD_OK_A || frame.cmd == YunmoFrame.CMD_OK_B -> {
                    val dim = YunmoFrame.parseOkDimension(frame.payload)
                    if (dim != null && pendingDim.get() == null) {
                        pendingDim.set(dim)
                        synchronized(dimLock) { dimLock.notifyAll() }
                        log(
                            "[YUNMO] ← cmd=0x${frame.cmd.toString(16)} dim " +
                                "${dim.reportedW}x${dim.reportedH}",
                        )
                    } else {
                        val hex = frame.payload.take(12).joinToString(" ") {
                            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
                        }
                        log("[YUNMO] ← cmd=0x${frame.cmd.toString(16)} len=${frame.payload.size} $hex")
                        // OEM: 0x33 commits pending handleMapNaviType=Map (not a naked state-6).
                        if (frame.cmd == YunmoFrame.CMD_OK_B) {
                            if (pendingMapExchange) {
                                commitPendingMapExchange("0x33 with pendingMapExchange")
                            } else {
                                log("[YUNMO] 0x33 with no pending exchange — ignore (media/OK only)")
                            }
                        }
                    }
                }
                frame.cmd == YunmoFrame.CMD_DISPLAY && frame.payload.isNotEmpty() -> {
                    val mode = frame.payload[0].toInt() and 0xFF
                    // ACK frames: payload[0]==0 and carry frameId at [1..4].
                    if (mode == 0 && frame.payload.size >= 5) {
                        val id = (frame.payload[1].toInt() and 0xFF) or
                            ((frame.payload[2].toInt() and 0xFF) shl 8) or
                            ((frame.payload[3].toInt() and 0xFF) shl 16) or
                            ((frame.payload[4].toInt() and 0xFF) shl 24)
                        val firstAck = lastAckedId < 0
                        lastAckedId = id
                        unackedSinceAck = 0
                        synchronized(ackLock) { ackLock.notifyAll() }
                        if (firstAck || (!oemMapMode && id % 60 == 0) ||
                            (oemMapMode && (framesSent <= 8 || framesSent % 60 == 0))
                        ) {
                            log("[YUNMO] ack frameId=$id" + if (oemMapMode) " (map-liveness)" else "")
                        }
                        if (firstAck) video?.requestKeyframe("yunmo-first-ack")
                    } else {
                        onDashDisplayMode(mode)
                    }
                }
                framesSent < 8 -> {
                    val hex = frame.payload.take(12).joinToString(" ") {
                        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
                    }
                    log("[YUNMO] ← cmd=0x${frame.cmd.toString(16)} len=${frame.payload.size} $hex")
                }
            }
        }
    }

    private fun jpegSendLoop(out: OutputStream, encW: Int, encH: Int, port: Int) {
        val cap = jpegCapturer
        if (cap == null) {
            log("[YUNMO] JPEG capturer missing")
            return
        }
        val periodMs = (1000L / BikeProfileHolder.active.videoFrameRate.coerceIn(4, 15)).coerceAtLeast(1L)
        log("[YUNMO] JPEG stills send loop ${encW}x${encH} tick=${periodMs}ms")
        while (running) {
            val tickStart = System.currentTimeMillis()
            val jpeg = cap.latest()
            val accepted = if (jpeg != null && stillsWindowHasRoom()) {
                val id = frameId.get()
                try {
                    YunmoFrame.write(out, YunmoFrame.encodeJpegEx(jpeg, id))
                    frameId.incrementAndGet()
                    framesSent++
                    lastFrameAt = System.currentTimeMillis()
                    if (framesSent == 1) {
                        ConnectionState.set(Phase.STREAMING, "Yunmo :$port ${encW}x${encH} JPEG")
                        log("[YUNMO] first JPEG still out (${jpeg.size}b, id=$id)")
                    }
                    onFrameSent?.invoke()
                    true
                } catch (e: Exception) {
                    log("[YUNMO] JPEG send failed: ${e.message}")
                    break
                }
            } else {
                false
            }
            if (jpeg != null) cap.noteOffer(accepted, if (accepted) jpeg.size else 0)
            val remain = periodMs - (System.currentTimeMillis() - tickStart)
            if (remain > 0) {
                try { Thread.sleep(remain) } catch (_: InterruptedException) {}
            }
        }
        if (running) ConnectionState.set(Phase.ERROR, "Yunmo stills link dropped")
    }

    /**
     * Stills window is frameId vs last ack — not "unacked since last ack".
     * This HU acks about every second still; counting since-ack looked full when it was not.
     */
    private fun stillsWindowHasRoom(): Boolean {
        val next = frameId.get()
        val inFlight = if (lastAckedId < 0) next else next - lastAckedId.coerceAtLeast(0)
        return inFlight < YunmoFrame.SEND_WINDOW
    }

    private fun sendLoop(
        out: OutputStream,
        encW: Int,
        encH: Int,
        metaW: Int,
        metaH: Int,
        port: Int,
    ) {
        var idlePolls = 0
        var sentCodecConfig = false
        var storedSps: ByteArray? = null
        var storedPps: ByteArray? = null
        var gatedLogged = false
        // Ride MO NaviActivity: MediaCodecH264LiveThread — combined SPS/PPS + prepend on IDR.
        val combinedLive = mapNavMode && oemMapMode
        if (combinedLive) {
            log("[YUNMO] OEM combined LiveThread path: SPS+PPS config + prepend on every IDR")
        }
        lastSyncRequestAt = System.currentTimeMillis()
        while (running) {
            // Pause full-map H.264 only in SimpleNavi (160/[5]); Wi‑Fi MapNaviType starts open.
            if (mapNavMode && (!canSendMapH264() || simpleNaviActive)) {
                if (!gatedLogged) {
                    gatedLogged = true
                    log(
                        "[YUNMO] H.264 gated — mapNavConfirmed=$mapNavConfirmed " +
                            "simpleNavi=$simpleNaviActive pendingEx=$pendingMapExchange",
                    )
                }
                synchronized(mapNavLock) {
                    try { mapNavLock.wait(200L) } catch (_: InterruptedException) {}
                }
                if (canSendMapH264() && !simpleNaviActive) {
                    gatedLogged = false
                    sentCodecConfig = false
                    frameId.set(0)
                    unackedSinceAck = 0
                    video?.requestKeyframe("yunmo-ungate-map-nav")
                    log("[YUNMO] H.264 ungated — combined LiveThread")
                }
                continue
            }

            val now = System.currentTimeMillis()
            if (now - lastSyncRequestAt >= mapSyncIntervalMs) {
                lastSyncRequestAt = now
                video?.requestKeyframe("yunmo-map-sync")
            }

            if (!sentCodecConfig) {
                val cfg = video?.codecConfigBytes()
                if (cfg != null && cfg.isNotEmpty()) {
                    val (sps, pps) = YunmoFrame.extractSpsPps(cfg)
                    storedSps = sps
                    storedPps = pps
                    if (!sendAccessUnit(out, cfg, encW, encH, metaW, metaH, port, force = true)) break
                    log(
                        "[YUNMO] primed combined SPS/PPS (${cfg.size}b, " +
                            "sps=${sps?.size ?: 0}b pps=${pps?.size ?: 0}b)",
                    )
                    sentCodecConfig = true
                    video?.requestKeyframe("yunmo-after-sps")
                    continue
                }
            }

            val au = video?.pollFrame(1500)
            if (au == null) {
                idlePolls++
                if (idlePolls == 1 || idlePolls % 4 == 0) {
                    log("[YUNMO] waiting for encoder frames (poll#$idlePolls)…")
                }
                continue
            }
            idlePolls = 0
            if (!running) break

            val toSend = if (combinedLive && YunmoFrame.annexBContainsNal(au, 5)) {
                val packed = YunmoFrame.prependSpsPps(storedSps, storedPps, au)
                if (framesSent <= 12 || framesSent % 60 == 0) {
                    log(
                        "[YUNMO] combined IDR prepend sps=${storedSps?.size ?: 0}b " +
                            "pps=${storedPps?.size ?: 0}b total=${packed.size}b",
                    )
                }
                packed
            } else if (combinedLive) {
                // P-frames: strip accidental leading SPS/PPS.
                val stripped = YunmoFrame.stripLeadingSpsPps(au)
                if (stripped.isNotEmpty()) stripped else au
            } else {
                au
            }
            if (!sendAccessUnit(out, toSend, encW, encH, metaW, metaH, port, force = false)) break
        }
        if (running) {
            ConnectionState.set(Phase.ERROR, "Yunmo link dropped")
        }
    }

    private fun sendAccessUnit(
        out: OutputStream,
        au: ByteArray,
        encW: Int,
        encH: Int,
        metaW: Int,
        metaH: Int,
        port: Int,
        force: Boolean,
    ): Boolean {
        if (!waitForSendWindow(force)) return running
        if (!running) return false
        val id = frameId.getAndIncrement()
        val wire = YunmoFrame.encodeH264Ex(
            au, metaW, metaH, id,
            mediaType = YunmoFrame.MEDIA_TYPE_LEGACY,
            oemMapHeader = true,
        )
        return try {
            YunmoFrame.write(out, wire)
            framesSent++
            if (oemMapMode) unackedSinceAck++
            lastFrameAt = System.currentTimeMillis()
            if (framesSent == 1) {
                ConnectionState.set(Phase.STREAMING, "Yunmo :$port ${encW}x${encH}")
            }
            onFrameSent?.invoke()
            val nal = YunmoFrame.annexBNalType(au)
            // vc59: byte-level dump of first config / IDR / P for OEM compare (djcacho).
            if (framesSent <= 3 ||
                (framesSent <= 12 && (nal == 7 || nal == 5 || nal == 1))
            ) {
                logWireDump(framesSent, id, nal, au, wire, encW, encH, metaW, metaH)
            } else if (framesSent % 60 == 0) {
                log(
                    "[YUNMO] sent #$framesSent id=$id mapHdr " +
                        "${YunmoFrame.nalTypeName(nal)} (${au.size}b → ${wire.size}b) " +
                        "enc=${encW}x${encH} unacked=$unackedSinceAck",
                )
            }
            true
        } catch (e: Exception) {
            log("[YUNMO] send failed: ${e.message}")
            false
        }
    }

    private fun logWireDump(
        n: Int,
        id: Int,
        nal: Int,
        au: ByteArray,
        wire: ByteArray,
        encW: Int,
        encH: Int,
        metaW: Int,
        metaH: Int,
    ) {
        fun hex(buf: ByteArray, max: Int = buf.size): String =
            buf.take(max).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val outer = wire.copyOfRange(0, minOf(8, wire.size))
        val hdr32 = if (wire.size >= 40) wire.copyOfRange(8, 40) else ByteArray(0)
        val payloadHead = au.take(48).toByteArray()
        log(
            "[YUNMO] WIRE #$n id=$id nal=${YunmoFrame.nalTypeName(nal)} " +
                "au=${au.size}b wire=${wire.size}b enc=${encW}x${encH} meta=${metaW}x${metaH}",
        )
        log("[YUNMO] WIRE #$n outer8=[${hex(outer)}]")
        log("[YUNMO] WIRE #$n mediaHdr32=[${hex(hdr32)}]")
        log("[YUNMO] WIRE #$n auHead48=[${hex(payloadHead)}]")
    }

    private fun waitForSendWindow(force: Boolean): Boolean {
        if (force) return true
        val deadline = System.currentTimeMillis() + 2_000L
        while (running) {
            val inFlight = if (oemMapMode) {
                unackedSinceAck
            } else {
                val next = frameId.get()
                if (lastAckedId < 0) next else next - lastAckedId.coerceAtLeast(0)
            }
            if (inFlight < YunmoFrame.SEND_WINDOW) return true
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) {
                if (framesSent < 10 || framesSent % 60 == 0) {
                    log(
                        "[YUNMO] send-window timeout (inFlight=$inFlight acked=$lastAckedId) — sending anyway",
                    )
                }
                return true
            }
            synchronized(ackLock) {
                try {
                    ackLock.wait(remain.coerceAtMost(200L))
                } catch (_: InterruptedException) {
                    return running
                }
            }
        }
        return false
    }

    fun stop() {
        val was = running
        running = false
        synchronized(ackLock) { ackLock.notifyAll() }
        synchronized(dimLock) { dimLock.notifyAll() }
        synchronized(mapNavLock) { mapNavLock.notifyAll() }
        if (was) {
            try {
                val s = sock
                val out = s?.getOutputStream()
                if (out != null) {
                    YunmoFrame.write(
                        out,
                        YunmoFrame.encodeSimple(
                            YunmoFrame.CMD_DISPLAY,
                            byteArrayOf(YunmoFrame.DISP_EXIT_A.toByte()),
                        ),
                    )
                    YunmoFrame.write(
                        out,
                        YunmoFrame.encodeSimple(
                            YunmoFrame.CMD_DISPLAY,
                            byteArrayOf(YunmoFrame.DISP_EXIT_B.toByte()),
                        ),
                    )
                }
            } catch (_: Exception) {}
        }
        try { sock?.close() } catch (_: Exception) {}
        sock = null
        sendThread = null
        recvThread = null
        try { jpegCapturer?.stop() } catch (_: Exception) {}
        jpegCapturer = null
        if (ownsVideo) {
            try { video?.stop(abandonNavigation = false) } catch (_: Exception) {}
        }
        video = null
        ownsVideo = false
    }

    private fun attachVideo(width: Int, height: Int) {
        val shared = AaVideoBridge.pipeline
        if (jpegStills && shared != null) {
            video = shared
            ownsVideo = false
            val fps = BikeProfileHolder.active.videoFrameRate.coerceIn(4, 15)
            val cap = YunmoJpegCapturer(width, height, fps, log)
            cap.start()
            val surf = cap.surface
            if (surf == null) {
                log("[YUNMO] JPEG ImageReader surface missing")
                cap.stop()
                return
            }
            jpegCapturer = cap
            try {
                shared.configureStillsOutput(surf, width, height)
                shared.setFrameCap(fps)
                log("[YUNMO] AA compositor → JPEG stills ${width}x${height} @${fps}fps")
            } catch (e: Exception) {
                log("[YUNMO] configureStillsOutput failed: ${e.message}")
            }
            return
        }
        if (shared != null) {
            video = shared
            ownsVideo = false
            log("[YUNMO] using shared AA video pipeline")
            try {
                shared.configureBikeCanvas(width, height, force = true)
                val fps = BikeProfileHolder.active.videoFrameRate.coerceIn(5, 30)
                shared.setFrameCap(fps)
                log("[YUNMO] configured AA bike canvas ${width}x${height} @${fps}fps")
            } catch (e: Exception) {
                log("[YUNMO] configureBikeCanvas failed: ${e.message}")
            }
        } else {
            // Ride MO NaviVirtualDisplay: 1024×464 @187 dpi, PRIVATE|NEVER_BLANK|
            // PRESENTATION|OWN_CONTENT_ONLY (dumpsys 2026-08-07).
            val vp = VideoPipeline(
                context, width, height, log,
                ownDisplayDensityDpi = 187,
            )
            vp.start()
            video = vp
            ownsVideo = true
            log("[YUNMO] started owned VideoPipeline ${width}x${height} @187dpi (OEM NaviVirtualDisplay)")
        }
        video?.onBikeDataStart()
    }

    private fun openSocket(network: Network?): Socket? {
        if (network != null) {
            try {
                return network.socketFactory.createSocket()
            } catch (e: Exception) {
                log("[YUNMO] socketFactory: ${e.message}")
            }
            try {
                val s = Socket()
                network.bindSocket(s)
                return s
            } catch (e: Exception) {
                log("[YUNMO] bindSocket: ${e.message}")
            }
        }
        return try {
            Socket()
        } catch (e: Exception) {
            log("[YUNMO] Socket(): ${e.message}")
            null
        }
    }
}

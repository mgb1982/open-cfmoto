package dev.zanderp.opencfmoto

import org.json.JSONObject
import java.net.Socket
import java.util.UUID

/**
 * Server-side PXC control dispatcher. The phone is the SERVER: after we send the
 * ECP_PXC_MDNS_RESPOND probe to the bike, the bike connects back to our listening
 * ports and drives the handshake. We reply per cmd.
 *
 * Verified against cfmoto-tcp-v5.log (official app, standard Car PXC, JSON CLIENT_INFO):
 *   bike 0x10000 (CAR_CTRL select)  -> we 0x10001
 *   bike 0x20000 (CAR_DATA select)  -> we 0x20001
 *   bike 0x10010 CLIENT_INFO (JSON) -> we 0x10011 (our info + RSA pubkey + signed HUID)
 *   bike 0x10690 {usbSpeed,wifiSpeed} -> we 0x10691
 *   bike 0x103e0 {client_set,sn}    -> we 0x103e1, then 0x201c0 CHECK_SN_RESULT {isOk:true}
 *                                      (Zontes 21340: the result goes out on CAR_DATA, see onCheckSn)
 *   bike 0x70000000 heartbeat        -> we 0x70000001
 */
class PxcHandshake(
    private val log: (String) -> Unit,
) {
    private val phoneUuid: String = UUID.randomUUID().toString()
    @Volatile var carHuid: String? = null
        private set
    @Volatile var lastClientInfo: JSONObject? = null
        private set
    /** Dashboard-variant strategy, selected from the bike's CLIENT_INFO. Read by the media plane too
     *  (via EasyConnProber.handshake.profile). Written once on the control thread, before any media
     *  socket exists. */
    @Volatile var profile: BikeProfile = BikeProfiles.legacy
        private set
    /** Rate-limit HU_TIME_SYNC log lines (bike sends ~every 2s). */
    @Volatile private var lastHuTimeSyncLogAt: Long = 0L
    @Volatile private var huTimeSyncCount: Int = 0
    @Volatile private var clockLabBannerLogged: Boolean = false
    /** APPSTATUS acks seen from the bike (logged once each). */
    private val appStatusAcksSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    /** The CAR_DATA (P2C, phone→car) and CAR_CTRL (C2P) sockets of the current session. */
    @Volatile private var carDataSocket: Socket? = null
    @Volatile private var carCtrlSocket: Socket? = null
    /** True once the bike sent CHECK_SN in the current session (= first session of this dash boot). */
    @Volatile private var checkSnThisSession = false
    /** When the bike picked CAR_CTRL in this session — the reference for how late QUERY_TIME comes. */
    @Volatile private var ctrlSelectedAt = 0L
    /** Resyncs done in the current dash boot (reset by the CHECK_SN of a new boot). */
    @Volatile private var resyncsThisBoot = 0

    /** Asks the prober to drop every bike socket so its reconnect path re-probes (clock resync). */
    @Volatile var onResyncRequested: ((String) -> Unit)? = null

    /**
     * Called when the bike selects a PXC channel on a :10922 socket (CAR_CTRL or CAR_DATA).
     * [EasyConnProber] starts a proactive 0x70000000 heartbeat on **each** of those sockets —
     * the 800NK (sdk 0.9.23.x) tears the whole session down after ~7s of silence on either one.
     */
    @Volatile var onPxcChannelSelected: ((Socket, String) -> Unit)? = null

    /** Dispatch one inbound frame on a given socket (ctrl or media). */
    fun handle(tag: String, frame: PxcFrame, socket: Socket) {
        val out = socket.getOutputStream()
        when (frame.cmd) {
            PxcFrame.CMD_CHANNEL_CAR_CTRL -> {
                log("[$tag] bike selected CAR_CTRL (0x10000) → ack 0x10001 ${sockId(socket)}")
                carCtrlSocket = socket
                checkSnThisSession = false
                ctrlSelectedAt = System.currentTimeMillis()
                PxcFrame(PxcFrame.CMD_CHANNEL_CAR_CTRL + 1, ByteArray(0)).write(out)
                onPxcChannelSelected?.invoke(socket, "CAR_CTRL")
            }
            PxcFrame.CMD_CHANNEL_CAR_DATA -> {
                log("[$tag] bike selected CAR_DATA (0x20000) → ack 0x20001 ${sockId(socket)}")
                carDataSocket = socket
                PxcFrame(PxcFrame.CMD_CHANNEL_CAR_DATA + 1, ByteArray(0)).write(out)
                onPxcChannelSelected?.invoke(socket, "CAR_DATA")
                startAppStatusLoop(socket)
            }
            PxcFrame.CMD_CLIENT_INFO -> onClientInfo(tag, frame, out)
            PxcFrame.CMD_QUERY_SPEED -> {
                log("[$tag] QUERY_SPEED ${frame.payload.asText()} → reply 0x10691")
                PxcFrame(PxcFrame.CMD_QUERY_SPEED_RLY, ByteArray(0)).write(out)
            }
            PxcFrame.CMD_CHECK_SN -> onCheckSn(tag, frame, socket)
            PxcFrame.CMD_HEARTBEAT -> {
                PxcFrame(PxcFrame.CMD_HEARTBEAT_ACK, ByteArray(0)).write(out)
            }
            // Bike acks to our APPSTATUS_* notifications (0x20021/31/41/51) — log once, never re-ack.
            APPSTATUS_FOREGROUND + 1,
            APPSTATUS_BACKGROUND + 1,
            APPSTATUS_SCREEN_LOCKED + 1,
            APPSTATUS_SCREEN_UNLOCKED + 1 -> {
                if (appStatusAcksSeen.add(frame.cmd)) {
                    log("[APPSTATUS] bike acked 0x${frame.cmd.toUInt().toString(16)} len=${frame.payload.size} " +
                        "${frame.payload.asText()} (firmware understands APPSTATUS)")
                }
            }
            PxcFrame.CMD_CHECK_SN_RESULT + 1 -> {
                log("[$tag] bike acked CHECK_SN_RESULT (0x201c1) len=${frame.payload.size} on ${chanOf(socket)} ${sockId(socket)}")
            }
            PxcFrame.CMD_HEARTBEAT_ACK -> {
                // ack from the bike — nothing to do
            }
            // Clock lab: knobs choose 0x10600 echo vs phone and 0x10451
            // empty / Carbit / Zontes / no-ack. Defaults match Latest 2.0.13.
            PxcFrame.CMD_HU_TIME_SYNC -> onHuTimeSync(tag, frame, out)
            PxcFrame.CMD_HU_QUERY_TIME -> onHuQueryTime(tag, frame, out)
            else -> {
                if (!profile.handleUnknownControl(tag, frame, out, log)) {
                    log("[$tag] cmd=0x${frame.cmd.toUInt().toString(16)} (${PxcFrame.nameOf(frame.cmd)}) " +
                        "len=${frame.payload.size} ${frame.payload.asText()}")
                }
            }
        }
    }

    /**
     * Zontes clock fix, part 1/2 (see also [onCheckSn]).
     *
     * The official Zontes Smart app (Carbit SDK, tayo.com.ZontesIntelligence 1.12) sends
     * ECP_P2C_APPSTATUS_FOREGROUND (0x20020) on the CAR_DATA (P2C) socket ~130 ms after the bike
     * opens it — before CHECK_SN and before the bike's 0x10450 QUERY_TIME — and then about once per
     * second for the whole session. The dash acks it with 0x20021.
     *
     * Field-tested on a ZT125T-X (channel 21340, flavor 65561, V0.0.1): APPSTATUS alone and the
     * CAR_DATA CHECK_SN_RESULT alone both still leave the cluster clock at 00:00; with BOTH, the dash
     * takes the phone's time on every connect, including cold starts. Gated to channel 21340.
     */
    private fun startAppStatusLoop(socket: Socket) {
        if (!APPSTATUS_ENABLED) return
        Thread({
            try {
                // CLIENT_INFO arrives on the CAR_CTRL socket before CAR_DATA opens; wait briefly just in case.
                var waited = 0
                while (lastClientInfo == null && waited < 3000 && !socket.isClosed) {
                    Thread.sleep(100); waited += 100
                }
                val channel = lastClientInfo?.optString("channel")?.trim().orEmpty()
                if (channel != ZONTES_CHANNEL) {
                    log("[APPSTATUS] not sending (channel=${channel.ifEmpty { "-" }}, only Zontes $ZONTES_CHANNEL)")
                    return@Thread
                }
                // The official app reports the phone's real portrait screen size here.
                val dm = android.content.res.Resources.getSystem().displayMetrics
                val payload = JSONObject().apply {
                    put("displayRotation", 0)
                    put("width", minOf(dm.widthPixels, dm.heightPixels))
                    put("height", maxOf(dm.widthPixels, dm.heightPixels))
                    put("enableAccessibility", false)
                    put("enableAOAHid", true)
                    put("mode", 2)
                }.toString().toByteArray(Charsets.UTF_8)
                Thread.sleep(130)
                val out = socket.getOutputStream()
                var n = 0
                var lastLogAt = 0L
                while (!socket.isClosed) {
                    PxcFrame(APPSTATUS_FOREGROUND, payload).write(out)
                    n++
                    val now = System.currentTimeMillis()
                    if (n <= 3 || now - lastLogAt >= 30_000L) {
                        lastLogAt = now
                        log("[APPSTATUS] → 0x20020 FOREGROUND #$n ${String(payload, Charsets.UTF_8)}")
                    }
                    Thread.sleep(1000)
                }
                log("[APPSTATUS] CAR_DATA socket closed after $n frame(s)")
            } catch (e: Exception) {
                log("[APPSTATUS] loop ended: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "pxc-appstatus").apply { isDaemon = true }.start()
    }

    private fun logClockLabBanner() {
        if (clockLabBannerLogged) return
        clockLabBannerLogged = true
        val channel = lastClientInfo?.optString("channel")?.trim().orEmpty()
        log(ClockLab.banner(channel.ifEmpty { null }))
    }

    private fun onHuTimeSync(tag: String, frame: PxcFrame, out: java.io.OutputStream) {
        logClockLabBanner()
        val forcePhone = ClockLab.timeSync == ClockTimeSyncMode.PHONE
        val ack = HuTimeSync.ack(frame.payload, forcePhone = forcePhone)
        PxcFrame(PxcFrame.CMD_HU_TIME_SYNC_ACK, ack.payload).write(out)
        val n = ++huTimeSyncCount
        val now = System.currentTimeMillis()
        if (n <= 3 || now - lastHuTimeSyncLogAt >= 30_000L) {
            lastHuTimeSyncLogAt = now
            log("[CLOCK-LAB] HU_TIME_SYNC → ${ClockLab.timeSync.id}")
            log("[$tag] HU_TIME_SYNC #$n len=${frame.payload.size} → ack 0x10601 mode=${ack.mode} time=${ack.stamp}")
        }
    }

    private fun onHuQueryTime(tag: String, frame: PxcFrame, out: java.io.OutputStream) {
        logClockLabBanner()
        val channel = lastClientInfo?.optString("channel")?.trim().orEmpty()
        // Zontes (21340): once APPSTATUS + CHECK_SN_RESULT-on-P2C make the session valid, the dash
        // DOES apply the 0x10451 body. An empty ack sets the cluster to 13:49, and a later good
        // reply in the same dash boot does not recover it. So the default (EMPTY) answers with the
        // OEM JSON on this channel; explicit Clock-lab choices (Carbit / no-ack) are respected.
        val autoZontes = ClockLab.query == ClockQueryMode.EMPTY && channel == ZONTES_CHANNEL
        val mode = if (autoZontes) ClockQueryMode.ZONTES else ClockLab.query
        if (autoZontes) log("[CLOCK-LAB] channel $ZONTES_CHANNEL: default empty 0x10451 → OEM Zontes JSON")
        val reply = when (mode) {
            ClockQueryMode.EMPTY -> "empty"
            ClockQueryMode.CARBIT, ClockQueryMode.ZONTES -> "json"
            ClockQueryMode.NO_ACK -> "no-ack"
        }
        log("[CLOCK-LAB] HU_QUERY_TIME len=${frame.payload.size} → 0x10451 $reply")
        when (mode) {
            ClockQueryMode.NO_ACK -> { /* tester: leave 0x10450 unanswered */ }
            ClockQueryMode.EMPTY -> {
                PxcFrame(PxcFrame.CMD_HU_QUERY_TIME_ACK, ByteArray(0)).write(out)
                log("[$tag] HU_QUERY_TIME (0x10450) len=${frame.payload.size} " +
                    "channel=${channel.ifEmpty { "-" }} → 0x10451 empty")
            }
            ClockQueryMode.CARBIT -> {
                val ack = HuQueryTime.carbit()
                PxcFrame(PxcFrame.CMD_HU_QUERY_TIME_ACK, ack.payload).write(out)
                log("[$tag] HU_QUERY_TIME (0x10450) len=${frame.payload.size} channel=${channel.ifEmpty { "-" }} " +
                    "→ 0x10451 carbit dateTime=${ack.dateTime}")
            }
            ClockQueryMode.ZONTES -> {
                val ack = HuQueryTime.zontesOem()
                PxcFrame(PxcFrame.CMD_HU_QUERY_TIME_ACK, ack.payload).write(out)
                log("[$tag] HU_QUERY_TIME (0x10450) len=${frame.payload.size} channel=${channel.ifEmpty { "-" }} " +
                    "→ 0x10451 zontes dateTime=${ack.dateTime} currentTime=${ack.currentTime} zone=${ack.timeZone}")
            }
        }
        maybeScheduleResync(channel)
    }

    /**
     * Zontes clock fix, part 3/3 ([ClockLab.resyncMode], SMART by default). With APPSTATUS +
     * CHECK_SN_RESULT on P2C the dash takes our time on most sessions, but some still end at 00:00 /
     * 13:49 with a wire-identical session. Field data (24 sessions, ZT125T-X): every failure was a
     * session where the dash sent QUERY_TIME late (≥ ~2.0 s after picking CAR_CTRL); every session
     * that asked before ~1.95 s set the clock. A same-boot reconnect (the dash skips CHECK_SN and
     * asks again) gets a fresh chance. So SMART drops the link only after a late ask, up to
     * [MAX_RESYNCS_PER_BOOT] times per dash boot; ONCE keeps the zontes-1 behaviour (always once,
     * first session of a boot). We answer QUERY_TIME within ~2 ms either way — the lateness is the
     * dash's; why it then ignores our time is not known.
     */
    private fun maybeScheduleResync(channel: String) {
        val mode = ClockLab.resyncMode
        if (mode == ClockResyncMode.OFF || channel != ZONTES_CHANNEL) return
        val askedAfterMs = if (ctrlSelectedAt > 0) System.currentTimeMillis() - ctrlSelectedAt else -1L
        val slow = askedAfterMs >= SLOW_QUERY_TIME_MS
        val reason: String = when (mode) {
            ClockResyncMode.ONCE -> {
                if (!checkSnThisSession) {
                    log("[RESYNC] once: not needed — dash skipped CHECK_SN (same-boot reconnect), " +
                        "QUERY_TIME after ${askedAfterMs}ms")
                    return
                }
                "once: first session of this dash boot (QUERY_TIME after ${askedAfterMs}ms)"
            }
            else -> {
                if (!slow) {
                    log("[RESYNC] smart: QUERY_TIME after ${askedAfterMs}ms (< ${SLOW_QUERY_TIME_MS}ms) — " +
                        "looks good, no resync")
                    return
                }
                if (resyncsThisBoot >= MAX_RESYNCS_PER_BOOT) {
                    log("[RESYNC] smart: QUERY_TIME after ${askedAfterMs}ms but already resynced " +
                        "$resyncsThisBoot× this dash boot — giving up (Stop + Connect by hand)")
                    return
                }
                "smart: QUERY_TIME after ${askedAfterMs}ms (≥ ${SLOW_QUERY_TIME_MS}ms) — " +
                    "resync ${resyncsThisBoot + 1}/$MAX_RESYNCS_PER_BOOT"
            }
        }
        if (mode == ClockResyncMode.ONCE && resyncsThisBoot >= 1) return
        resyncsThisBoot++
        log("[RESYNC] $reason — dropping the link in ${RESYNC_DELAY_MS / 1000}s")
        Thread({
            try { Thread.sleep(RESYNC_DELAY_MS) } catch (_: InterruptedException) { return@Thread }
            val cb = onResyncRequested
            if (cb == null) log("[RESYNC] no prober hook — skipped") else cb("clock resync")
        }, "pxc-resync").apply { isDaemon = true }.start()
    }

    private fun onClientInfo(tag: String, frame: PxcFrame, out: java.io.OutputStream) {
        val text = frame.payload.asText()
        log("[$tag] *** CLIENT_INFO from bike *** $text")
        val json = try { JSONObject(text) } catch (e: Exception) {
            log("[$tag] CLIENT_INFO parse failed: $e"); return
        }
        lastClientInfo = json
        carHuid = json.optString("HUID").ifEmpty { json.optString("huid") }.ifEmpty { null }
        log("[$tag] carHuid=$carHuid HUName=${json.optString("HUName")} channel=${json.optString("channel")}")
        logClockLabBanner()

        profile = BikeProfiles.select(json, log)
        val early = BikeProfileHolder.active
        val startedSpec = BikeProfileHolder.aaVideo  // what Android Auto actually started / negotiated with
        // Keep a measured landscape panel (e.g. 800MT 1280×576) when CLIENT_INFO scoring would
        // flip to the near-square 800NK Advanced profile — that mis-route resized touch/margins
        // mid-session while AA stayed on the landscape stream (connect/drop flaps in field logs).
        val earlyPanel = early.panelSize
        val earlyIsWide = earlyPanel != null && earlyPanel.first >= earlyPanel.second * 3 / 2
        if (earlyIsWide && profile === Cfdl26NkTouchProfile) {
            log("[$tag] keeping landscape QR/measured profile '${early.name}' " +
                "(CLIENT_INFO preferred '${profile.name}' but panel ${earlyPanel!!.first}x${earlyPanel.second} is wide)")
            profile = early
        }
        if (early !== profile) {
            log("[$tag] profile refined from QR guess '${early.name}' → CLIENT_INFO '${profile.name}' " +
                "(AA already started at the QR-guess resolution)")
        }
        BikeProfileHolder.active = profile  // authoritative; QR modelId was only the early hint
        // Android Auto's video surface + decoder buffer were sized when AA started (from the QR guess)
        // and CANNOT be resized mid-session. If the refined profile wants a different resolution and no
        // explicit override is in effect, pin the spec to what AA is really running: otherwise the
        // compositor scales the live source (e.g. 800x480) as if it were the profile's (e.g. 720x1280),
        // producing a wildly wrong draw rect and a picture the dash rejects — which drops the link every
        // few seconds (the connect/drop flap). Known/consistent bikes hit no-op here.
        if (BikeProfileHolder.aaVideoOverride == null && BikeProfileHolder.aaVideo != startedSpec) {
            BikeProfileHolder.aaVideoOverride = startedSpec
            log("[$tag] pinned AA video to ${startedSpec.width}x${startedSpec.height} " +
                "(can't resize mid-session; refined profile wanted ${profile.aaVideo.width}x${profile.aaVideo.height})")
        }
        log("[$tag] *** BikeProfile selected: ${profile.name} ***")

        val reply = profile.buildClientInfoReply(json, carHuid, phoneUuid)
        log("[$tag] → CLIENT_INFO reply ${reply.toString().take(180)}…")
        PxcFrame(PxcFrame.CMD_CLIENT_INFO_RLY, reply.toString().toByteArray(Charsets.UTF_8)).write(out)
    }

    companion object {
        /** Zontes Smart / CFDL16 dash channel (CLIENT_INFO "channel"). */
        const val ZONTES_CHANNEL = "21340"
        /** Master switch for the Zontes APPSTATUS loop. */
        const val APPSTATUS_ENABLED = true
        const val APPSTATUS_FOREGROUND = 0x20020      // ECP_P2C_APPSTATUS_FOREGROUND
        const val APPSTATUS_BACKGROUND = 0x20030      // ECP_P2C_APPSTATUS_BACKGROUND
        const val APPSTATUS_SCREEN_LOCKED = 0x20040   // ECP_P2C_APPSTATUS_SCREEN_LOCKED
        const val APPSTATUS_SCREEN_UNLOCKED = 0x20050 // ECP_P2C_APPSTATUS_SCREEN_UNLOCKED
        /**
         * Zontes clock fix, part 2/2: the official app acks CHECK_SN (0x103e1) on the socket it came
         * in on (C2P / CAR_CTRL) but writes 0x201C0 CHECK_SN_RESULT on the P2C (CAR_DATA) socket —
         * "process >>>> 0x000201C0" is logged by its PXCForCar-P2C thread. The dash then acks with
         * 0x201c1 on CAR_DATA. Gated to channel 21340; other bikes keep the old path.
         */
        const val CHECK_SN_RESULT_ON_CAR_DATA = true
        /** Clock resync: wait after QUERY_TIME before dropping the link. */
        const val RESYNC_DELAY_MS = 10_000L
        /** SMART resync: a QUERY_TIME this long after CAR_CTRL marks a session likely to have failed. */
        const val SLOW_QUERY_TIME_MS = 1_950L
        const val MAX_RESYNCS_PER_BOOT = 3
    }

    private fun sockId(s: Socket?): String =
        if (s == null) "sock=null" else "sock=${s.inetAddress?.hostAddress}:${s.port}→:${s.localPort}"

    private fun chanOf(s: Socket): String = when (s) {
        carDataSocket -> "CAR_DATA"
        carCtrlSocket -> "CAR_CTRL"
        else -> "other"
    }

    private fun onCheckSn(tag: String, frame: PxcFrame, socket: Socket) {
        val out = socket.getOutputStream()
        checkSnThisSession = true
        resyncsThisBoot = 0
        val text = frame.payload.asText()
        log("[$tag] CHECK_SN from bike on ${chanOf(socket)} ${sockId(socket)}: $text")
        val sn = try { JSONObject(text).optString("sn") } catch (e: Exception) { "" }
        // ack the request frame on the socket it arrived on
        PxcFrame(PxcFrame.CMD_CHECK_SN_ACK, ByteArray(0)).write(out)
        // send the result
        val result = JSONObject().apply {
            put("isOk", true)
            put("errCode", 0)
            put("errMsg", "")
            put("id", sn)
            put("client_set", "easy_conn")
        }
        val body = result.toString().toByteArray(Charsets.UTF_8)
        val zontes = lastClientInfo?.optString("channel")?.trim() == ZONTES_CHANNEL
        val p2c = carDataSocket
        if (CHECK_SN_RESULT_ON_CAR_DATA && zontes && p2c != null && !p2c.isClosed) {
            val where = if (p2c === socket) "CAR_DATA (same socket)" else "CAR_DATA (P2C, like official)"
            // Log sn= (not the JSON "id") so LogRedactor masks the dash serial like everywhere else.
            log("[$tag] → CHECK_SN_RESULT via $where ${sockId(p2c)} isOk=true sn=$sn")
            try {
                PxcFrame(PxcFrame.CMD_CHECK_SN_RESULT, body).write(p2c.getOutputStream())
                return
            } catch (e: Exception) {
                log("[$tag] CAR_DATA write failed (${e.javaClass.simpleName}: ${e.message}) — falling back to incoming socket")
            }
        } else if (CHECK_SN_RESULT_ON_CAR_DATA && zontes) {
            log("[$tag] no open CAR_DATA socket yet — CHECK_SN_RESULT on incoming socket")
        }
        log("[$tag] → CHECK_SN_RESULT isOk=true sn=$sn")
        PxcFrame(PxcFrame.CMD_CHECK_SN_RESULT, body).write(out)
    }
}

private fun ByteArray.asText(): String =
    if (isEmpty()) "" else try { String(this, Charsets.UTF_8) } catch (e: Exception) { "<${size}b>" }

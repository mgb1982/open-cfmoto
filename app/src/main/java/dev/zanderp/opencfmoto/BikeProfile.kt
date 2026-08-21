package dev.zanderp.opencfmoto

import android.os.Build
import org.json.JSONObject
import java.io.OutputStream

/**
 * Strategy for a specific CFMoto dashboard generation ("bike variant").
 *
 * The PXC/EasyConn protocol is broadly the same across dashboards, but newer head units add
 * steps and fields the older ones don't. Rather than branch on version literals all over the
 * handshake, each dashboard gets a [BikeProfile] selected from the bike's CLIENT_INFO
 * ([BikeProfiles.select]) and consulted at the points where behavior diverges.
 *
 * Selected in [PxcHandshake.onClientInfo] and stored on the shared [PxcHandshake] instance;
 * the media plane reaches it via `handshake.profile`. The bike opens the media ports only AFTER
 * the control handshake, so the profile is always chosen before the media plane needs it.
 */
enum class AaResolution(val w: Int, val h: Int) {
    LANDSCAPE_800x480(800, 480),
    LANDSCAPE_1280x720(1280, 720),
    PORTRAIT_720x1280(720, 1280),
    PORTRAIT_1080x1920(1080, 1920),
}

/** The Android Auto video config a dash should request: orientation/size + panel density. */
data class AaVideoSpec(val resolution: AaResolution, val dpi: Int) {
    val width: Int get() = resolution.w
    val height: Int get() = resolution.h
}

/**
 * Unused pixels of the coded AA frame, advertised via the AAP VideoConfiguration
 * `marginWidth`/`marginHeight` fields. Android Auto then renders its UI into the remaining
 * `(width - marginW) x (height - marginH)` area — the standard way to make AA draw at a panel's
 * aspect ratio when none of the fixed coded sizes matches it. The compositor samples only that
 * usable sub-rect (see [AaCompositor]).
 */
data class AaMargins(val marginW: Int, val marginH: Int) {
    val any: Boolean get() = marginW > 0 || marginH > 0

    companion object {
        val NONE = AaMargins(0, 0)

        /**
         * Margins that shrink [coded] so the usable area matches [targetW]:[targetH], trimming only
         * the axis that is too long (keeping one axis at full coded size to lose the least detail).
         */
        fun forAspect(coded: AaVideoSpec, targetW: Int, targetH: Int): AaMargins {
            if (targetW <= 0 || targetH <= 0) return NONE
            val cw = coded.width
            val ch = coded.height
            val codedAspect = cw.toDouble() / ch
            val targetAspect = targetW.toDouble() / targetH
            return if (codedAspect < targetAspect) {
                // coded is too tall/narrow for the target → trim height.
                val usableH = Math.round(cw * targetH.toDouble() / targetW).toInt().coerceIn(16, ch)
                AaMargins(0, (ch - usableH).coerceAtLeast(0))
            } else {
                // coded is too wide for the target → trim width.
                val usableW = Math.round(ch * targetW.toDouble() / targetH).toInt().coerceIn(16, cw)
                AaMargins((cw - usableW).coerceAtLeast(0), 0)
            }
        }
    }
}

interface BikeProfile {
    /** Human-readable label — appears in bike-test logs so a capture is self-describing. */
    val name: String

    /** How strongly this profile claims a given CLIENT_INFO. Highest positive score wins; 0 = no claim. */
    fun score(info: JSONObject): Int

    /**
     * Coarse match from the QR `modelId` — the only bike identity available BEFORE connecting.
     * Used to pick [aaVideo] up front (Android Auto's resolution must be chosen before AA starts,
     * which is before the CLIENT_INFO handshake). CLIENT_INFO scoring refines it later.
     */
    fun matchesModelId(modelId: String): Boolean = false

    /** The Android Auto video config this dash should request (orientation + size + density). */
    val aaVideo: AaVideoSpec

    // ---- capability flags ----
    /** Bike advertises enableSockServerAuth — likely needs an auth exchange before media (see Cfdl26). */
    val requiresSockServerAuth: Boolean
    val supportsScreenTouch: Boolean
    /** Value we advertise in our own CLIENT_INFO reply's `supportFunction`. */
    val advertisedSupportFunction: Int

    /** Build the phone's CLIENT_INFO reply (cmd 0x10011). `phoneUuid` is owned by [PxcHandshake]. */
    fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject

    /**
     * Handle a control-plane cmd not covered by [PxcHandshake]'s fixed switch. Return true if the
     * profile replied/consumed it; false to fall back to the legacy "log only, no reply" behavior.
     */
    fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean

    // ---- media-plane hooks (behavior-preserving defaults; only rounding is wired this pass) ----
    /** Round and fit requested capture dimensions. Returns Pair(width, height) normalized for this profile. */
    fun roundCaptureDimensions(w: Int, h: Int): Pair<Int, Int> = (w and 0xFFF0) to (h and 0xFFF0)

    /** Whether to force the H.264 encoder to Baseline Profile @ Level 3.1. */
    val forceBaseline: Boolean get() = true

    // ---- encoder tuning (defaults reproduce the proven values; override per dash to tune) ----
    /** Target H.264 bitrate in bits/sec. */
    val videoBitrate: Int get() = 2_500_000
    /** Target encoder frame rate (fps). */
    val videoFrameRate: Int get() = 30
    /** Keyframe (IDR) interval in seconds. Frequent keyframes let late/dropped joiners recover. */
    val videoIFrameIntervalSec: Int get() = 1

    /** Media-plane GET_VERSION reply (version, subVersion). */
    fun versionReply(): Pair<Int, Int> = 3 to 1

    /**
     * Optional measured panel (w,h) for letterbox / AA margin math. Null = unknown.
     * Used with [DashMemory] / learned geometry on the next connect.
     */
    val panelSize: Pair<Int, Int>? get() = null

    /**
     * Default screen insets [top, bottom, left, right] in dash pixels until the rider customises
     * [ScreenMargins]. 800NK Advanced blanks the MotoPlay pull-down with a top inset.
     */
    val defaultMargins: IntArray get() = intArrayOf(0, 0, 0, 0)

    /**
     * Yunmo SoftAP: send JPEG stills (cmd 29, media type 0) instead of H.264.
     * Opt-in only via Setup ▸ X-Cape 1200 — Auto never selects this.
     */
    val yunmoJpegStills: Boolean get() = false
}

/** Registry + selection. Never returns null — falls back to the legacy (BIKE A) profile. */
object BikeProfiles {
    val legacy: BikeProfile = LegacyCfdl16Profile

    // Order matters: [maxByOrNull] keeps the FIRST profile on a score tie, so this list is the
    // tie-break priority. The US 800NK / CRCP family ([Nk800Profile]) is FIRST because it scores
    // decisively (and returns 0 for non-CRCP units, so it never misroutes a 675/1000MT-X). LANDSCAPE
    // is next — the vast majority of CFMoto dashes are landscape (800x480), and some firmwares match
    // no strong CLIENT_INFO signal and tie every profile at 1. Defaulting an ambiguous tie to Portrait
    // sized the Android Auto video for a tall screen it doesn't have, which the dash rejected → a ~7s
    // connect/drop flap. A genuine portrait unit (1000 MT-X) still wins outright because it scores far
    // higher (CFDL26 version_name + its package), so order only decides ties.
    private val all: List<BikeProfile> = listOf(
        Nk800Profile,
        Cfdl26NkTouchProfile,
        MoriniMlSoftApProfile,
        Cfdl16MotoPlayLandscapeProfile,
        ClC450Profile,
        Cfdl26LandscapeProfile,
        Cfdl26PortraitProfile,
        LegacyCfdl16Profile,
    )

    /** Authoritative selection from CLIENT_INFO (during the PXC handshake). Ties resolve to the
     *  first profile in [all] (Nk800 / landscape-first) — see the note there. Honour Setup's
     *  [BikeProfileHolder.profileOverride] when set. */
    fun select(info: JSONObject, log: (String) -> Unit): BikeProfile {
        BikeProfileHolder.profileOverride.resolve()?.let {
            log("[profile] manual override → ${it.name}")
            return it
        }
        val scored = all.map { it to it.score(info) }
        log("[profile] scores=" + scored.joinToString { "${it.first.name}=${it.second}" })
        return scored.filter { it.second > 0 }.maxByOrNull { it.second }?.first ?: legacy
    }

    /**
     * Early selection from the QR code data, before we connect. Falls back to legacy.
     * When [context] is set, a previously learned panel size can override the guess (2nd connect).
     */
    fun selectByQr(qr: QrData?, context: android.content.Context? = null): BikeProfile {
        BikeProfileHolder.profileOverride.resolve()?.let { return it }
        val base = selectByQrGuess(qr)
        if (context == null || qr == null) return base
        val panel = DashMemory.get(context, qr.ssid) ?: return base
        if (panel == base.panelSize) return base
        val res = DashMemory.bestFit(panel.first, panel.second) ?: return base
        LogBus.log(
            "[panel] using measured ${panel.first}x${panel.second} → AA ${res.w}x${res.h} " +
                "(was ${base.name})",
        )
        return LearnedGeometryProfile(base, panel, res)
    }

    private fun selectByQrGuess(qr: QrData?): BikeProfile {
        if (qr == null) return legacy
        // Morini SoftAP / Alltrhike family — SSID MLN_* / ML*_ before generic modelId ties.
        if (qr.ssid.startsWith("MLN_", ignoreCase = true) ||
            qr.ssid.startsWith("ML", ignoreCase = true) && qr.modelId in setOf("21333", "00297")
        ) {
            return MoriniMlSoftApProfile
        }
        val matches = all.filter { it.matchesModelId(qr.modelId ?: "") }
        if (matches.isEmpty()) return legacy
        if (matches.size == 1) return matches[0]

        // modelId 37426 is shared by 1000 MT-X (P2P/portrait), 800MT (AP/landscape), and
        // 800NK Advanced (AP/near-square touch). P2P → portrait; AP → landscape 800MT as the
        // safer default — CLIENT_INFO scoring / learned panel upgrades Advanced on reconnect.
        val isP2p = qr.supportsP2p || qr.ssid.startsWith("DIRECT-")
        return if (isP2p) Cfdl26PortraitProfile else Cfdl26LandscapeProfile
    }

    /** Legacy helper for backward compatibility / tests. */
    fun selectByModelId(modelId: String?): BikeProfile =
        modelId?.let { id -> all.firstOrNull { it.matchesModelId(id) } } ?: legacy
}

/**
 * Process-wide active bike profile. Set early from the QR code data ([BikeProfiles.selectByQr])
 * so the Android Auto stack ([ServiceDiscoveryResponse]) can request the right resolution before AA
 * starts, then confirmed authoritatively from CLIENT_INFO in [PxcHandshake]. Read across the
 * activity + the Android Auto foreground service, so it lives here as a process global (like
 * [ProjectionHolder] / [AaVideoBridge]).
 */
object BikeProfileHolder {
    @Volatile var active: BikeProfile = BikeProfiles.legacy

    /**
     * Optional user override for the Android Auto video resolution (see [VideoPrefs.ResolutionMode]).
     * When null the active profile's own [BikeProfile.aaVideo] is used. Set from the QR path in
     * [MainActivity] before AA starts, so the whole AA stack ([ServiceDiscoveryResponse], the
     * compositor, the decoder fallback dims) reads one consistent spec via [aaVideo].
     */
    @Volatile var aaVideoOverride: AaVideoSpec? = null

    /**
     * Margins advertised to Android Auto so it renders at the dash panel's aspect ratio (see
     * [AaMargins]). Set before AA starts in [MainActivity]; read by [dev.zanderp.opencfmoto.aa.ServiceDiscoveryResponse]
     * (advertise) and the compositor (crop the source to the usable area). [AaMargins.NONE] = off.
     */
    @Volatile var aaContentMargins: AaMargins = AaMargins.NONE

    /**
     * Setup ▸ "Disable touchscreen" — when true, never advertise a touchscreen to Android Auto
     * (focus/knob UI) even if the active profile claims touch. Synced from [AppSettings.forceNonTouch].
     * Mutually exclusive with [forceTouch].
     */
    @Volatile var forceNonTouch: Boolean = false

    /**
     * Setup ▸ "Force touchscreen" — when true, always advertise a touchscreen to Android Auto
     * (touch UI) even if the active profile is non-touch (e.g. 1000 MT‑X). Synced from
     * [AppSettings.forceTouch]. Mutually exclusive with [forceNonTouch].
     */
    @Volatile var forceTouch: Boolean = false

    /**
     * Setup ▸ bike profile override. Synced from [ProfilePrefs]; [ProfileOverride.AUTO] means
     * score from QR / CLIENT_INFO as usual.
     */
    @Volatile var profileOverride: ProfileOverride = ProfileOverride.AUTO

    /** The effective Android Auto video spec: the user override if set, else the active profile's. */
    val aaVideo: AaVideoSpec get() = aaVideoOverride ?: active.aaVideo

    /** Usable AA content size (coded frame minus [aaContentMargins]) — the aspect-correct area the
     *  compositor and the in-app Dash view actually show. Equals the coded size when margins are off. */
    val aaUsableWidth: Int get() = (aaVideo.width - aaContentMargins.marginW).coerceIn(1, aaVideo.width)
    val aaUsableHeight: Int get() = (aaVideo.height - aaContentMargins.marginH).coerceIn(1, aaVideo.height)

    /** Whether AA / CLIENT_INFO should advertise a touchscreen.
     *  [forceNonTouch] wins over [forceTouch]; otherwise profile default. */
    val advertisesScreenTouch: Boolean
        get() = when {
            forceNonTouch -> false
            forceTouch -> true
            else -> active.supportsScreenTouch
        }
}

/** Shared base CLIENT_INFO reply. Keys/order match the original PxcHandshake.buildClientInfoReply so
 *  that LegacyCfdl16Profile (supportFunction=0) produces byte-identical output. */
private fun basePhoneClientInfo(huid: String?, phoneUuid: String, supportFunction: Int): JSONObject =
    JSONObject().apply {
        put("pxcVersion", "1.0.2")
        put("phoneUUID", phoneUuid)
        put("phoneBrand", Build.BRAND)
        put("phoneModel", Build.MODEL)
        put("phoneOsVersion", Build.VERSION.SDK_INT.toString())
        put("phoneOs", "Android")
        put("package", EasyConnProber.SPOOFED_PACKAGE)
        put("versionCode", 126)
        put("token", 0)
        put("pubkey", RsaKeys.publicKeyBase64)
        put("encryptedHUID", huid?.let { RsaKeys.signHuid(it) } ?: "")
        put("bluetoothName", "OpenCfMoto")
        put("supportH264IFrame", true)
        put("supportFunction", supportFunction)
        // Do NOT advertise supportSyncCorrectTime. Claiming it made some firmwares apply our
        // 0x10601 aggressively (Zontes/Voge → 00:00) even when the cluster clock was already fine.
        // We still body-ack every inbound 0x10600 (see HuTimeSync) so Morini/QJ never see empty→1970.
        put("supportSyncCorrectTime", false)
        put("appVersionFingerPrint", "opencfmoto-poc")
    }

/**
 * Build a 0x10601 ack body for [PxcFrame.CMD_HU_TIME_SYNC].
 *
 * Live bike payload (45 bytes): little-endian header
 *   i32@0 flags (−2), i32@4 channel/modelId, i32@8 seq, i32@12 0
 * followed by 29 ASCII chars `yyyy-MM-dd HH:mm:ss.SSS000000`.
 *
 * Empty 0x10601 → epoch/1970 on Morini/Voge. Rewriting with phone time (2.0.7–2.0.9) still
 * jumped **hours** on Voge/QJ/X-Cape (minutes often stayed — TZ / format apply).
 * Strategy (2.0.10):
 *  - if the request already carries any non-epoch stamp bytes → **echo the whole payload**
 *  - only synthesize phone local time when the request is empty / 1970 / all-zero
 * Never empty.
 */
internal object HuTimeSync {
    private const val PAYLOAD_LEN = 45
    private const val TIME_OFF = 16
    private const val TIME_LEN = 29
    private val stampRe = Regex("""^(\d{4})-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}""")
    private val timeFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getDefault()
    }

    data class Ack(val payload: ByteArray, val mode: String, val stamp: String)

    fun ackPayload(request: ByteArray): ByteArray = ack(request).payload

    fun ack(request: ByteArray): Ack {
        val len = maxOf(PAYLOAD_LEN, request.size)
        val out = ByteArray(len)
        if (request.isNotEmpty()) {
            System.arraycopy(request, 0, out, 0, minOf(request.size, len))
        }
        if (request.size < TIME_OFF) {
            val bb = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.putInt(-2)
            bb.putInt(0)
            bb.putInt(1)
            bb.putInt(0)
        }
        val bikeStamp = extractStamp(request)
        val stamp: String
        val mode: String
        if (shouldEcho(bikeStamp, request)) {
            stamp = bikeStamp.ifBlank { extractStamp(out) }
            mode = "echo"
        } else {
            stamp = phoneStamp()
            mode = "phone"
            val ascii = stamp.toByteArray(Charsets.US_ASCII)
            System.arraycopy(ascii, 0, out, TIME_OFF, minOf(TIME_LEN, ascii.size))
        }
        return Ack(out, mode, stamp)
    }

    internal fun extractStamp(buf: ByteArray): String {
        if (buf.size < TIME_OFF) return ""
        val n = minOf(TIME_LEN, buf.size - TIME_OFF)
        return String(buf, TIME_OFF, n, Charsets.US_ASCII).trimEnd('\u0000', ' ')
    }

    /** Echo unless the bike sent epoch / blank (those still need a phone wall-clock). */
    internal fun shouldEcho(stamp: String, request: ByteArray): Boolean {
        if (request.size < TIME_OFF) return false
        if (stamp.isBlank() || stamp.all { it == '\u0000' || it == ' ' }) return false
        val year = stampRe.find(stamp)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (year != null && year in 1969..1971) return false
        return true
    }

    internal fun isSaneStamp(stamp: String): Boolean {
        val m = stampRe.find(stamp) ?: return false
        val year = m.groupValues[1].toIntOrNull() ?: return false
        return year in 2020..2099
    }

    private fun phoneStamp(): String {
        val ms = System.currentTimeMillis()
        return synchronized(timeFmt) {
            timeFmt.timeZone = java.util.TimeZone.getDefault()
            String.format(
                java.util.Locale.US,
                "%s.%03d000000",
                timeFmt.format(java.util.Date(ms)),
                (ms % 1000L).toInt(),
            )
        }
    }
}

/**
 * BIKE A — the CFDL16 head unit (sdkVersion 0.9.29.1) the app was reverse-engineered against and
 * confirmed working end-to-end. This profile reproduces the current behavior EXACTLY (byte-identical
 * CLIENT_INFO reply, no reply to unknown cmds) and is the safe default for any unrecognized bike.
 */
object LegacyCfdl16Profile : BikeProfile {
    override val name = "CFDL16 / legacy (BIKE A)"
    override val requiresSockServerAuth = false
    override val supportsScreenTouch = false
    override val advertisedSupportFunction = 0

    /** The 675's 5" panel is landscape ~800x386. */
    override val aaVideo = AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)

    /** Known 675 QR modelId. Legacy is also the fallback, so this is just for a positive early match. */
    override fun matchesModelId(modelId: String): Boolean = modelId.trim() == "37416"

    /** Floor of 1 for unknown bikes, plus strong signals for genuine CFDL16 / 0.9.29 units so they
     *  are never beaten by a bare CFDL26 `supportFunction=128` tie. */
    override fun score(info: JSONObject): Int {
        var s = 1
        if (info.optString("HUName").startsWith("CFDL16")) s += 4
        if (info.optString("sdkVersion").startsWith("0.9.29")) s += 3
        if (info.optString("channel").trim() == "37416") s += 2
        return s
    }

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction)

    /**
     * Newer CFDL16-family units (e.g. sdk 0.9.25.1 / `CFDL06.6.10` / package `net.easyconn`,
     * CFMOTO9805) send the same post-handshake notify burst as CFDL26 — notably
     * [PxcFrame.CMD_OTA_FTP_INFO] (0x103a0) — and will **not** open the media ports until each is
     * acked cmd+1. The old "log only" path left 0x103a0 hanging → media never starts. Same empty
     * cmd+1 ack as CFDL26.
     */
    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean = Cfdl26PortraitProfile.handleUnknownControl(tag, frame, out, log)
}

/**
 * BIKE C — the US-market CFMoto 800NK family. Head unit HUID prefix "CRCP", package_name
 * "linux_no_package", **sdkVersion 0.9.23.x** — an OLDER CFDL16-family dialect, NOT the newer
 * CFDL26 (1.1.x) 1000 MT-X / 800 MT. Wi-Fi Direct, non-touch panel. Seen as landscape 800×400
 * (sdk 0.9.23.4, e.g. CFMOTO-1381DA) and a newer portrait ~460×750 variant (sdk 0.9.23.9,
 * version_name V1.0.18, e.g. CFMOTO-1E9714).
 *
 * Two things this family needs that the CFDL26 (1000MT-X/800MT) profiles get wrong:
 *   1. It sends NO heartbeats of its own → an idle :10922 channel socket (CAR_CTRL **or**
 *      CAR_DATA) tears the whole session down every ~7s. The phone must send 0x70000000 on
 *      **both** channel sockets (OpenMoto / 0.9.23.4 confirmed; V1.0.18 / 0.9.23.9 flaps the same
 *      way if only CAR_CTRL is heartbeated — CAR_DATA carries CHECK_SN / 0x104a0 and goes silent).
 *   2. It is a NON-TOUCH panel and does NOT do sock-server auth. So — unlike the CFDL26 profiles —
 *      we must NOT advertise `supportScreenTouch:true`.
 * Its extra notify frames (0x10450 / 0x10470 voice grammar / 0x104a0 OTA-sock info) are acked cmd+1,
 * same as the CFDL26 units.
 */
object Nk800Profile : BikeProfile {
    override val name = "CFMoto 800NK (CRCP / sdk 0.9.23.x)"
    override val requiresSockServerAuth = false
    override val supportsScreenTouch = false
    override val advertisedSupportFunction = 128

    /** Default to landscape 800x480 (the confirmed 800NK panel). Portrait CRCP variants
     *  (sdk 0.9.23.9) auto-correct orientation from the capture request via DashMemory on reconnect. */
    override val aaVideo = AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)

    /** Known US 800NK QR modelIds (landscape 66660703/66660721, portrait 66660732). */
    override fun matchesModelId(modelId: String): Boolean =
        modelId.trim() in setOf("66660703", "66660721", "66660732")

    /**
     * Claim only genuine CRCP / 0.9.23.x-linux units. Returns 0 for everything else so it can never
     * misroute the 675 (CFDL16 0.9.29.1) or the 1000 MT-X (CFDL26 1.1.x) even though it's ordered
     * first in [BikeProfiles.all].
     */
    override fun score(info: JSONObject): Int {
        val crcp = info.optString("HUID").startsWith("CRCP")
        val sdk0923 = info.optString("sdkVersion").startsWith("0.9.23")
        val linuxPkg = info.optString("package_name") == "linux_no_package"
        if (!crcp && !(sdk0923 && linuxPkg)) return 0   // not this family
        var s = 0
        if (crcp) s += 4
        if (sdk0923) s += 3
        if (linuxPkg) s += 2
        return s
    }

    /** Plain reply — deliberately NO supportScreenTouch (this panel is non-touch). */
    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction)

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean = Cfdl26PortraitProfile.handleUnknownControl(tag, frame, out, log)
}

/**
 * BIKE B — the CFDL26 / MotoPlay head unit on the 1000 MT-X (sdkVersion 1.1.4,
 * package com.cfmoto.cfdashmotoplay, enableSockServerAuth=true, WiFi-Direct P2P).
 *
 * The bike's CLIENT_INFO often claims `supportScreenTouch:true`, but the stock TFT keeps touch
 * **locked by default** and MotoPlay/CarPlay are meant to be driven with the handlebar
 * previous/next/confirm buttons (see the 1000 MT-X owner's manual). Advertising a touchscreen to
 * Android Auto puts it in touch UI with **no focus cursor**, so those buttons do nothing — the
 * classic "no touch but touch enabled" trap. We therefore treat this profile as non-touch for AA
 * ([supportsScreenTouch]=false → focus/knob UI) by default. Riders who unlock the panel and want
 * touch AA can turn on Setup ▸ **Force touchscreen**. Dash touch events are still forwarded when
 * the panel sends them; the in-app Dash view / on-screen pad also work. The landscape 800MT
 * profile stays touch=true.
 */
object Cfdl26PortraitProfile : BikeProfile {
    override val name = "CFDL26 / MotoPlay Portrait (1000 MT-X)"
    override val requiresSockServerAuth = true
    override val supportsScreenTouch = false
    override val advertisedSupportFunction = 128

    /** The 1000 MT-X's 8" panel is a tall PORTRAIT screen (requests ~800x951). Ask AA for portrait
     *  720x1280 at the panel's advertised 240 dpi; match-aspect margins reflow AA into this panel. */
    override val panelSize = 800 to 951
    override val aaVideo = AaVideoSpec(AaResolution.PORTRAIT_720x1280, dpi = 240)

    /** Known CFDL26 / 1000 MT-X QR modelId (from the bike's pairing QR). */
    override fun matchesModelId(modelId: String): Boolean = modelId.trim() == "37426"

    override fun score(info: JSONObject): Int {
        var s = 0
        if (info.optString("HUID").startsWith("CARB", ignoreCase = true)) return 0
        val versionName = info.optString("version_name")
        val pkg = info.optString("package_name")
        val looksCfmotoCfdl26 =
            versionName.startsWith("CFDL26") ||
                pkg.contains("cfmoto", ignoreCase = true) ||
                pkg.contains("cfdash", ignoreCase = true)
        if (versionName.startsWith("CFDL26")) s += 4
        val sdk = info.optString("sdkVersion")
        // Only credit sdk 1.x when this already looks like a CFMoto CFDL26 unit.
        if (looksCfmotoCfdl26 && sdk.isNotEmpty() && !sdk.startsWith("0.")) s += 2
        if (info.optBoolean("enableSockServerAuth", false)) s += 2
        if (info.optString("package_name") == "com.cfmoto.cfdashmotoplay") s += 3
        // supportFunction=128 alone is NOT a CFDL26 signal — many CFDL16 units advertise it too and
        // used to tie Landscape/Portrait/Legacy at 1 (Landscape won → touch AA on a non-touch dash).
        if (s > 0 && info.optInt("supportFunction", 0) == 128) s += 1
        return s
    }

    /** Claim touch only when Setup ▸ Force touchscreen (or a future profile default) advertises it. */
    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction).apply {
            if (BikeProfileHolder.advertisesScreenTouch) put("supportScreenTouch", true)
        }

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean {
        // Bike wall-clock sync (supportSyncCorrectTime). Must carry phone time in the ack body —
        // empty 0x10601 → epoch/1970 on Morini / Voge and unsynced clocks elsewhere.
        if (frame.cmd == PxcFrame.CMD_HU_TIME_SYNC) {
            val ack = HuTimeSync.ack(frame.payload)
            log("[$tag] HU_TIME_SYNC len=${frame.payload.size} → ack 0x10601 mode=${ack.mode} time=${ack.stamp}")
            PxcFrame(PxcFrame.CMD_HU_TIME_SYNC_ACK, ack.payload).write(out)
            return true
        }
        // After CHECK_SN the CFDL26 unit sends a burst of JSON notify frames the older CFDL16 never
        // did — 0x10780 (log), 0x103a0 (OTA FTP creds), 0x10020 (media-feature flags), and possibly
        // more — and will NOT connect to the media ports until each is acked. The whole PXC protocol
        // acks with reply = cmd+1 (empty), so ack every otherwise-unhandled control frame that way.
        val body = if (frame.payload.isEmpty()) "" else String(frame.payload, Charsets.UTF_8)
        val hex = if (frame.payload.isEmpty()) "" else
            " hex=" + BleProtocol.bytesToHex(frame.payload.copyOf(minOf(48, frame.payload.size)))
        val tag2 = if (frame.cmd == PxcFrame.CMD_SCREEN_TOUCH) " *** SCREEN_TOUCH ***" else ""
        val ack = frame.cmd + 1
        log("[$tag] CFDL26 ctrl ${frame.cmdHex()} (${PxcFrame.nameOf(frame.cmd)})$tag2 len=${frame.payload.size} " +
            "$body$hex → ack 0x${ack.toUInt().toString(16)} (empty)")
        PxcFrame(ack, ByteArray(0)).write(out)
        return true
    }
}

/**
 * BIKE C — the CFDL26 / MotoPlay head unit on the 800MT (sdkVersion 1.1.2,
 * package com.cfmoto.easyconnect, enableSockServerAuth=true, AP mode).
 */
/**
 * CFDL26 800NK Advanced — near-square touch panel 720×712 (measured). Portrait AA 720×1280 with
 * width matched 1:1; default top margin blanks MotoPlay's pull-down handle.
 */
object Cfdl26NkTouchProfile : BikeProfile {
    override val name = "CFDL26 / 800NK Advanced (touch, 720×712)"
    override val requiresSockServerAuth = true
    override val supportsScreenTouch = true
    override val advertisedSupportFunction = 128
    override val panelSize = 720 to 712
    override val aaVideo = AaVideoSpec(AaResolution.PORTRAIT_720x1280, dpi = 160)
    override val defaultMargins = intArrayOf(22, 0, 0, 0)

    override fun matchesModelId(modelId: String): Boolean = modelId.trim() == "37426"

    override fun score(info: JSONObject): Int {
        var s = 0
        if (info.optString("version_name").startsWith("CFDL26")) s += 4
        if (info.optString("package_name") == "com.cfmoto.easyconnect") s += 3
        if (info.optBoolean("enableSockServerAuth", false)) s += 2
        val sdk = info.optString("sdkVersion")
        if (sdk.isNotEmpty() && !sdk.startsWith("0.")) s += 2
        if (s > 0 && info.optInt("supportFunction", 0) == 128) s += 1
        // Tie-breakers vs 800MT landscape (same package / CFDL26 / often same version_name).
        // Do NOT bonus a shared firmware string like CFDL26.2.3.0.5 — that mis-routed real 800MT
        // units (HUID 6WWV, landscape 1280×576) to this near-square profile (logs 2026-07-24).
        if (info.optString("HUID").startsWith("6KWV")) s += 4
        // Near-square Advanced is not landscape-adaptive; 800MT AP dashes advertise that flag.
        if (info.optBoolean("supportLandscapeAdaptive", false)) s -= 4
        if (info.optBoolean("supportMirrorOverlayTouch", false)) s += 1
        if (info.optBoolean("supportScreenTouch", false)) s += 1
        return s
    }

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction).apply {
            if (BikeProfileHolder.advertisesScreenTouch) put("supportScreenTouch", true)
        }

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean = Cfdl26PortraitProfile.handleUnknownControl(tag, frame, out, log)
}

/** CFDL16-class MotoPlay / 450SR-style on modelId 66660742 (Wi‑Fi Direct, non-touch, needs HB). */
object Cfdl16MotoPlayLandscapeProfile : BikeProfile {
    override val name = "CFDL16 / MotoPlay Landscape (modelId 66660742)"
    override val requiresSockServerAuth = false
    override val supportsScreenTouch = false
    override val advertisedSupportFunction = 128
    override val panelSize = 800 to 400
    override val aaVideo = AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)

    override fun matchesModelId(modelId: String): Boolean = modelId.trim() == "66660742"

    override fun score(info: JSONObject): Int {
        var s = 0
        if (info.optString("channel") == "66660742") s += 10
        if (info.optString("sdkVersion").startsWith("0.9")) s += 2
        if (info.optBoolean("supportLandscapeAdaptive", false)) s += 1
        if (!info.optBoolean("supportScreenTouch", false)) s += 1
        return s
    }

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction)

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean = Cfdl26PortraitProfile.handleUnknownControl(tag, frame, out, log)
}

/** Near-square CL‑C450 panel 544×512 — needs AA 1280×720 containing viewport (eugen0309). */
object ClC450Profile : BikeProfile {
    override val name = "CL‑C450 (544×512)"
    override val requiresSockServerAuth = false
    override val supportsScreenTouch = false
    override val advertisedSupportFunction = 0
    override val panelSize = 544 to 512
    override val aaVideo = AaVideoSpec(AaResolution.LANDSCAPE_1280x720, dpi = 160)

    override fun matchesModelId(modelId: String): Boolean =
        modelId.trim() in setOf("66660736", "CLC450")

    override fun score(info: JSONObject): Int {
        var s = 0
        if (info.optString("channel") == "66660736") s += 8
        if (info.optString("HUName").contains("48FB4C", ignoreCase = true)) s += 4
        if (info.optString("sdkVersion").startsWith("0.9.23")) s += 1
        return s
    }

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction)

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean = Cfdl26PortraitProfile.handleUnknownControl(tag, frame, out, log)
}

/** Wraps a base profile with a measured panel + AA resolution for the next connect. */
private class LearnedGeometryProfile(
    private val base: BikeProfile,
    panel: Pair<Int, Int>,
    resolution: AaResolution,
) : BikeProfile by base {
    override val name = "${base.name} + measured ${panel.first}x${panel.second}"
    override val aaVideo = AaVideoSpec(resolution, dpi = base.aaVideo.dpi)
    override val panelSize = panel
}

/**
 * Moto Morini SoftAP / Alltrhike / MLN_* Carbit units (modelId/channel 21333, flavor 51).
 * EasyConn links and frames flow, but mis-scoring as CFDL26 800MT was common — keep a dedicated
 * Baseline landscape profile so CLIENT_INFO / canvas stay on the Morini SoftAP shape.
 */
object MoriniMlSoftApProfile : BikeProfile {
    override val name = "Morini SoftAP / Alltrhike (MLN)"
    override val requiresSockServerAuth = false
    override val supportsScreenTouch = false
    override val advertisedSupportFunction = 128
    /**
     * X‑Cape 1200 Yunmo: OEM runtime `NaviVirtualDisplay` is **1024×464** @187 dpi
     * (Ride MO 1.0.23 dumpsys — not calculateMapMaxSize 1904×862).
     * AA still requests 800×480; Yunmo re-encodes onto the reported dash canvas.
     */
    override val panelSize = 1024 to 464
    override val aaVideo = AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)
    /** Ride MO does not force Baseline — leave profile to the encoder. */
    override val forceBaseline: Boolean get() = false
    /**
     * Ride MO map-nav path (vc45): 2 Mbit/s, **10 fps**, GOP 2s + request-sync every 1s.
     * Full-screen OEM is 30 fps / GOP 1s — 10 fps is the path that also uses type-15 SPS.
     */
    override val videoBitrate: Int get() = 2_000_000
    override val videoFrameRate: Int get() = 10
    override val videoIFrameIntervalSec: Int get() = 2

    override fun matchesModelId(modelId: String): Boolean =
        modelId.trim() in setOf("21333", "21322")

    override fun score(info: JSONObject): Int {
        var s = 0
        val channel = info.optString("channel")
        if (channel == "21333" || channel == "21322") s += 12
        val huid = info.optString("HUID")
        if (huid.startsWith("CARB", ignoreCase = true)) s += 8
        if (info.optInt("flavor", 0) == 51) s += 6
        val pkg = info.optString("package_name")
        if (pkg.isEmpty() && s > 0) s += 1
        val sdk = info.optString("sdkVersion")
        if (sdk.startsWith("1.0.13")) s += 2
        if (!info.optBoolean("supportScreenTouch", false)) s += 1
        return s
    }

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction)

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean = Cfdl26PortraitProfile.handleUnknownControl(tag, frame, out, log)
}

/**
 * X-Cape 1200 Yunmo stills — **Setup override only**. The HU paints JPEG stills, not H.264.
 * Never scored from CLIENT_INFO / QR so Auto and every other bike stay on the existing path.
 */
object Xcape1200StillsProfile : BikeProfile {
    override val name = "X-Cape 1200 (JPEG stills)"
    override val requiresSockServerAuth = false
    override val supportsScreenTouch = false
    override val advertisedSupportFunction = 128
    override val panelSize = 1024 to 464
    override val aaVideo = AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)
    override val forceBaseline: Boolean get() = false
    override val videoBitrate: Int get() = 2_000_000
    override val videoFrameRate: Int get() = 10
    override val videoIFrameIntervalSec: Int get() = 2
    override val yunmoJpegStills: Boolean get() = true

    override fun matchesModelId(modelId: String): Boolean = false
    override fun score(info: JSONObject): Int = 0

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction)

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean = Cfdl26PortraitProfile.handleUnknownControl(tag, frame, out, log)
}

object Cfdl26LandscapeProfile : BikeProfile {
    override val name = "CFDL26 / MotoPlay Landscape (800MT)"
    override val requiresSockServerAuth = true
    override val supportsScreenTouch = true
    override val advertisedSupportFunction = 128
    /** Typical 800MT / Explore AP panel; measured sizes (e.g. 1280×576) override via DashMemory. */
    override val panelSize = 1280 to 576

    /** The 800MT has a landscape screen. Ask AA for landscape 800x480 — the resolution proven to
     *  stream end-to-end on this dash (see docs/05-DEBUG-KNOWLEDGE.md). The compositor letterboxes it
     *  into the bike's ~1280x576 canvas. 1280x720 is sharper but unverified end-to-end; revisit once
     *  AA is confirmed working. */
    override val aaVideo = AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)

    override fun matchesModelId(modelId: String): Boolean = modelId.trim() == "37426"

    override fun score(info: JSONObject): Int {
        var s = 0
        val versionName = info.optString("version_name")
        val pkg = info.optString("package_name")
        val looksCfmotoCfdl26 =
            versionName.startsWith("CFDL26") ||
                pkg.contains("cfmoto", ignoreCase = true) ||
                pkg.contains("cfdash", ignoreCase = true)
        if (versionName.startsWith("CFDL26")) s += 4
        // Do NOT treat every sdk 1.x Carbit SoftAP (Morini Alltrhike 1.0.13.1) as CFDL26.
        val sdk = info.optString("sdkVersion")
        if (looksCfmotoCfdl26 && sdk.isNotEmpty() && !sdk.startsWith("0.")) s += 2
        if (info.optBoolean("enableSockServerAuth", false)) s += 2
        if (pkg == "com.cfmoto.easyconnect") s += 3
        if (s > 0 && info.optInt("supportFunction", 0) == 128) s += 1
        // Prefer landscape when the unit is clearly landscape / not the Advanced near-square path.
        if (looksCfmotoCfdl26 && info.optBoolean("supportLandscapeAdaptive", false)) s += 4
        // 800MT Explore / MT-X AP family often uses 6WWV… HUID (vs 6KWV… on NK Advanced).
        if (info.optString("HUID").startsWith("6WWV")) s += 3
        if (!info.optBoolean("supportScreenTouch", false)) s -= 1
        // Generic Carbit HUID must not beat Morini SoftAP / Legacy.
        if (info.optString("HUID").startsWith("CARB", ignoreCase = true)) s = 0
        return s
    }

    override fun buildClientInfoReply(info: JSONObject, huid: String?, phoneUuid: String): JSONObject =
        basePhoneClientInfo(huid, phoneUuid, advertisedSupportFunction).apply {
            // Honour Setup ▸ Disable touchscreen so mis-routed non-touch dashes can still get
            // focus/knob AA without claiming a panel they don't have.
            if (BikeProfileHolder.advertisesScreenTouch) put("supportScreenTouch", true)
        }

    // Baseline@3.1 (the interface default). This USED to be false (Main/High), which is the prime
    // suspect for the Android Auto black screen: the dash accepts and continuously pulls our Main/High
    // frames but renders nothing (embedded decoders often only handle Baseline — no CABAC/B-frame
    // reordering, which the PTS-less bike wire format can't support anyway). Baseline is a strict
    // subset, so it cannot regress the working mirror path, and createEncoder falls back to default
    // if a device can't configure it. See docs/05-DEBUG-KNOWLEDGE.md §2.
    // override val forceBaseline defaults to true.

    override fun handleUnknownControl(
        tag: String, frame: PxcFrame, out: OutputStream, log: (String) -> Unit,
    ): Boolean {
        return Cfdl26PortraitProfile.handleUnknownControl(tag, frame, out, log)
    }
}

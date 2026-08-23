package dev.zanderp.opencfmoto

import android.content.Context
import androidx.annotation.StringRes

/**
 * User-selectable video quality. Applied as a multiplier on top of each [BikeProfile]'s tuned
 * bitrate (see [BikeProfile.videoBitrate]), so per-dash tuning and the user's preference compose:
 * a rider on a weak link can pick [SMOOTH] to cut data/latency, or [SHARP] for a crisper map.
 *
 * Only the bitrate is scaled — resolution, H.264 profile and frame rate are left to the bike
 * profile, since those are the values documented as fragile end-to-end (black-screen risk).
 *
 * [label] stays English for logs; [labelRes] is what Setup shows.
 */
enum class VideoQuality(val multiplier: Float, val label: String, @StringRes val labelRes: Int) {
    SMOOTH(0.7f, "Smoother — less data/latency", R.string.pref_vq_smooth),
    BALANCED(1.0f, "Balanced (recommended)", R.string.pref_vq_balanced),
    SHARP(1.6f, "Sharper — more data", R.string.pref_vq_sharp),
}

/**
 * How the Android Auto video (a fixed 16:9-ish landscape resolution) is placed into the bike's
 * differently-shaped mirror canvas by [AaCompositor]. AA can't render at the dash's aspect ratio, so
 * the rider picks the trade-off:
 *  - [FILL]    zoom to cover the whole screen; the AA image's edges are cropped (no black bars).
 *  - [FIT]     letterbox: whole AA image visible, black bars fill the leftover space.
 *  - [STRETCH] scale to the exact canvas; fills completely but distorts the aspect ratio.
 */
enum class ScreenFit(val label: String, @StringRes val labelRes: Int) {
    FILL("Fill — edge to edge (crops a little)", R.string.pref_fit_fill),
    FIT("Fit — no cropping (black bars)", R.string.pref_fit_fit),
    STRETCH("Stretch — fills, slight distortion", R.string.pref_fit_stretch),
}

/**
 * Caps how many frames per second the [AaCompositor] pushes into the H.264 encoder. Every capped
 * frame is one fewer GPU composite + hardware encode + Wi-Fi transmit, so lowering it directly cuts
 * heat and battery drain. Below the cap the map still looks fluid; the trade is a touch less
 * smoothness on fast pans. Independent of the idle keep-alive (which drops far lower still).
 *
 * [AUTO] hands frame rate AND bitrate to [AdaptiveVideoController]: it starts smooth and steps both
 * down as the phone heats up (thermal status) or the bike Wi-Fi link congests (dropped frames),
 * then recovers as conditions ease — so the dash stays connected and smooth-at-a-lower-rate instead
 * of stuttering or dropping. [fps] here is the *starting* cap the controller adapts from. The fixed
 * modes disable the controller entirely and pin the rate, exactly as before.
 */
enum class PowerMode(val fps: Int, val label: String, @StringRes val labelRes: Int) {
    AUTO(30, "Auto — adapts to heat & signal", R.string.pref_power_auto),
    SMOOTH(30, "Smooth — 30 fps (most battery)", R.string.pref_power_smooth),
    BALANCED(24, "Balanced — 24 fps (recommended)", R.string.pref_power_balanced),
    SAVER(20, "Battery saver — 20 fps (coolest)", R.string.pref_power_saver),
}

/**
 * Android Auto video resolution + orientation.
 *
 * [AUTO] uses the resolution/orientation the bike profile proved works — correct for recognized
 * CFMoto dashes (matched from the QR/CLIENT_INFO). But an unrecognized dash falls back to the legacy
 * landscape 800×480, which is wrong for a tall/portrait screen. The explicit options let the rider
 * force the shape + size for any bike (e.g. a portrait display we don't yet have a profile for). AA
 * only supports these fixed sizes; [MatchAspectMode] + AAP margins reflow AA into odd panel
 * aspects, otherwise the compositor letterboxes/crops per [ScreenFit]. HD sizes are crisper but
 * heavier and can black-screen on some embedded decoders — drop back to a smaller size or AUTO if
 * a bike rejects them.
 */
enum class ResolutionMode(val label: String, @StringRes val labelRes: Int, val spec: AaVideoSpec?) {
    AUTO("Auto — match your bike (recommended)", R.string.pref_res_auto, null),
    LANDSCAPE_SD("Landscape · 800×480", R.string.pref_res_land_sd, AaVideoSpec(AaResolution.LANDSCAPE_800x480, dpi = 160)),
    LANDSCAPE_HD("Landscape · 1280×720 (HD)", R.string.pref_res_land_hd, AaVideoSpec(AaResolution.LANDSCAPE_1280x720, dpi = 160)),
    PORTRAIT_SD("Portrait · 720×1280", R.string.pref_res_port_sd, AaVideoSpec(AaResolution.PORTRAIT_720x1280, dpi = 240)),
    PORTRAIT_HD("Portrait · 1080×1920 (HD)", R.string.pref_res_port_hd, AaVideoSpec(AaResolution.PORTRAIT_1080x1920, dpi = 240)),
}

/**
 * How [AaMargins] are chosen so Android Auto can reflow to the dash panel's real aspect ratio.
 *
 * - [AUTO] (default) — use the panel size learned from the bike (`REQ_CONFIG_CAPTURE` / [DashMemory])
 *   or the active profile's [BikeProfile.panelSize]. Margins are 0 when the AA coded size already
 *   matches, so normal landscape dashes stay unchanged.
 * - [OFF] — never advertise margins (old letterbox/crop behaviour).
 * - [MANUAL] — use the rider-entered panel W×H.
 */
enum class MatchAspectMode(val label: String) {
    AUTO("Auto — from bike screen"),
    OFF("Off"),
    MANUAL("Manual size"),
}

/**
 * Phone rotation while **mirroring** (MediaProjection). A portrait phone on a landscape dash
 * (typical 800×384) letterboxes to a ~170px-wide strip. Locking landscape fills the TFT.
 *
 * [MATCH_DASH] (default) uses the last measured / profile panel: wide → landscape, tall → portrait.
 * [FOLLOW] leaves the phone as the rider holds it.
 */
enum class MirrorOrientation(val label: String, @StringRes val labelRes: Int) {
    MATCH_DASH("Match dash (recommended)", R.string.pref_mirror_orient_dash),
    FOLLOW("Follow phone", R.string.pref_mirror_orient_follow),
    LANDSCAPE("Landscape", R.string.pref_mirror_orient_land),
    PORTRAIT("Portrait", R.string.pref_mirror_orient_port),
}

/** Target shape for the mirror VirtualDisplay (null = use current display metrics). */
enum class MirrorShape { LANDSCAPE, PORTRAIT }

/**
 * Video/projection preferences. Each setting is **per bike** (scoped via [BikeScope] to the selected
 * bike in the garage): a portrait 1000 MT-X can keep Fit + portrait HD while a landscape 800MT keeps
 * Fill + SD, and switching the active bike switches its settings. When no bike is selected — or a bike
 * has never been customized — the previous single, global value is used as the default.
 */
object VideoPrefs {
    private const val PREFS = "opencfmoto_bike"
    private const val KEY_QUALITY = "video_quality"
    private const val KEY_FIT = "screen_fit"
    private const val KEY_POWER = "power_mode"
    private const val KEY_RESOLUTION = "resolution_mode"
    private const val KEY_MIRROR_ORIENT = "mirror_orientation"

    // Match panel aspect (AA margins): Auto uses DashMemory / profile panel size for every bike.
    private const val KEY_MATCH_MODE = "match_aspect_mode"
    /** Legacy boolean from PR #5 — migrated once into [KEY_MATCH_MODE]. */
    private const val KEY_MATCH_ASPECT = "match_aspect_on"
    private const val KEY_ASPECT_W = "match_aspect_w"
    private const val KEY_ASPECT_H = "match_aspect_h"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context): VideoQuality {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_QUALITY, VideoQuality.BALANCED.name)
        return runCatching { VideoQuality.valueOf(name!!) }.getOrDefault(VideoQuality.BALANCED)
    }

    fun set(ctx: Context, quality: VideoQuality) {
        BikeScope.putString(prefs(ctx), ctx, KEY_QUALITY, quality.name)
    }

    /** Effective bitrate for [profile] under the current quality preference. */
    fun bitrateFor(ctx: Context, profile: BikeProfile): Int =
        (profile.videoBitrate * get(ctx).multiplier).toInt()

    fun fit(ctx: Context): ScreenFit {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_FIT, ScreenFit.FILL.name)
        return runCatching { ScreenFit.valueOf(name!!) }.getOrDefault(ScreenFit.FILL)
    }

    fun setFit(ctx: Context, fit: ScreenFit) {
        BikeScope.putString(prefs(ctx), ctx, KEY_FIT, fit.name)
    }

    fun power(ctx: Context): PowerMode {
        // Default stays BALANCED until AUTO has more on-bike soak; riders can opt into Auto in Setup.
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_POWER, PowerMode.BALANCED.name)
        return runCatching { PowerMode.valueOf(name!!) }.getOrDefault(PowerMode.BALANCED)
    }

    fun setPower(ctx: Context, mode: PowerMode) {
        BikeScope.putString(prefs(ctx), ctx, KEY_POWER, mode.name)
    }

    fun resolution(ctx: Context): ResolutionMode {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_RESOLUTION, ResolutionMode.AUTO.name)
        return runCatching { ResolutionMode.valueOf(name!!) }.getOrDefault(ResolutionMode.AUTO)
    }

    fun setResolution(ctx: Context, mode: ResolutionMode) {
        BikeScope.putString(prefs(ctx), ctx, KEY_RESOLUTION, mode.name)
    }

    fun mirrorOrientation(ctx: Context): MirrorOrientation {
        val name = BikeScope.getString(prefs(ctx), ctx, KEY_MIRROR_ORIENT, MirrorOrientation.MATCH_DASH.name)
        return runCatching { MirrorOrientation.valueOf(name!!) }.getOrDefault(MirrorOrientation.MATCH_DASH)
    }

    fun setMirrorOrientation(ctx: Context, mode: MirrorOrientation) {
        BikeScope.putString(prefs(ctx), ctx, KEY_MIRROR_ORIENT, mode.name)
    }

    /**
     * Shape to lock the phone / capture buffer to, or null to follow the current display.
     * [canvasW]/[canvasH] are the bike encoder size when known (mirror pipeline).
     */
    fun mirrorShape(ctx: Context, canvasW: Int = 0, canvasH: Int = 0): MirrorShape? {
        return when (mirrorOrientation(ctx)) {
            MirrorOrientation.FOLLOW -> null
            MirrorOrientation.LANDSCAPE -> MirrorShape.LANDSCAPE
            MirrorOrientation.PORTRAIT -> MirrorShape.PORTRAIT
            MirrorOrientation.MATCH_DASH -> {
                val panel = detectedPanelSize(ctx)
                val w = if (canvasW > 0) canvasW else panel?.first ?: 800
                val h = if (canvasH > 0) canvasH else panel?.second ?: 480
                if (w >= h) MirrorShape.LANDSCAPE else MirrorShape.PORTRAIT
            }
        }
    }

    fun matchAspectMode(ctx: Context): MatchAspectMode {
        val p = prefs(ctx)
        val named = BikeScope.getString(p, ctx, KEY_MATCH_MODE, null)
        if (named != null) {
            return runCatching { MatchAspectMode.valueOf(named) }.getOrDefault(MatchAspectMode.AUTO)
        }
        // Migrate PR #5 boolean: explicit On → Manual (kept their typed size); else Auto.
        return if (BikeScope.getBoolean(p, ctx, KEY_MATCH_ASPECT, false)) {
            MatchAspectMode.MANUAL
        } else {
            MatchAspectMode.AUTO
        }
    }

    /** Manual target W×H when mode is [MatchAspectMode.MANUAL]. */
    fun aspectTarget(ctx: Context): Pair<Int, Int> {
        val detected = detectedPanelSize(ctx)
        // Prefer a known panel; else keep the common MT-X-sized default (not landscape 800×480).
        val fallbackW = detected?.first ?: 800
        val fallbackH = detected?.second ?: 951
        return Pair(
            BikeScope.getInt(prefs(ctx), ctx, KEY_ASPECT_W, fallbackW),
            BikeScope.getInt(prefs(ctx), ctx, KEY_ASPECT_H, fallbackH),
        )
    }

    /**
     * Panel size for Auto match-aspect: last measured canvas for [ssid], else the active /
     * QR-selected profile's [BikeProfile.panelSize]. Null only when we truly don't know the panel.
     */
    fun detectedPanelSize(ctx: Context, ssid: String? = null): Pair<Int, Int>? {
        val qr = BikeMemory.lastQr(ctx)
        val id = ssid ?: qr?.ssid
        DashMemory.get(ctx, id)?.let { return it }
        BikeProfileHolder.active.panelSize?.let { return it }
        return BikeProfiles.selectByQr(qr, ctx).panelSize
    }

    fun setMatchAspect(ctx: Context, mode: MatchAspectMode, w: Int, h: Int) {
        BikeScope.putString(prefs(ctx), ctx, KEY_MATCH_MODE, mode.name)
        BikeScope.putInt(prefs(ctx), ctx, KEY_ASPECT_W, w.coerceIn(16, 8192))
        BikeScope.putInt(prefs(ctx), ctx, KEY_ASPECT_H, h.coerceIn(16, 8192))
    }

    /** Margins to advertise for the current AA [spec], or [AaMargins.NONE]. */
    fun aaMarginsFor(ctx: Context, spec: AaVideoSpec, ssid: String? = null): AaMargins {
        val mode = matchAspectMode(ctx)
        val panel = when (mode) {
            MatchAspectMode.OFF -> {
                LogBus.log("[match-aspect] OFF — margins 0")
                return AaMargins.NONE
            }
            MatchAspectMode.MANUAL -> aspectTarget(ctx)
            MatchAspectMode.AUTO -> {
                val detected = detectedPanelSize(ctx, ssid)
                if (detected == null) {
                    LogBus.log("[match-aspect] AUTO — no panel size yet (no DashMemory / profile.panelSize); margins 0 until learned")
                    return AaMargins.NONE
                }
                detected
            }
        }
        val margins = AaMargins.forAspect(spec, panel.first, panel.second)
        LogBus.log(
            "[match-aspect] $mode panel ${panel.first}x${panel.second} → " +
                "AA ${spec.width}x${spec.height} margins ${margins.marginW}x${margins.marginH}",
        )
        return margins
    }

    /**
     * The Android Auto video override for [profile] under the current [ResolutionMode], or null to
     * use the profile's own proven resolution ([ResolutionMode.AUTO], or when the explicit choice
     * already equals the profile's spec).
     */
    fun resolutionOverride(ctx: Context, profile: BikeProfile): AaVideoSpec? {
        val spec = resolution(ctx).spec ?: return null
        val base = profile.aaVideo
        if (spec.width == base.width && spec.height == base.height && spec.dpi == base.dpi) return null
        return spec
    }
}

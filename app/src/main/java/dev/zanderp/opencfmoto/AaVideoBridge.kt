package dev.zanderp.opencfmoto

import android.os.SystemClock

/**
 * Shared hand-off between the Android Auto receiver (which owns the H.264 encoder/[VideoPipeline]
 * fed by AA's decoded video) and [EasyConnProber] (the bike PXC pipeline that pulls encoded
 * frames). When [pipeline] is non-null, the prober uses it as the video source instead of
 * creating its own Presentation/mirror pipeline — this is how Android Auto reaches the dash.
 */
object AaVideoBridge {
    @Volatile var pipeline: VideoPipeline? = null

    /**
     * True while an AAP session is up on :5288 (handshake started / video may be flowing).
     * Used to avoid re-firing self-mode broadcasts that would kill a healthy AA session.
     */
    @Volatile var aaSessionLive: Boolean = false

    /** True if an AAP session came up at least once this Connect (even if it later dropped). */
    @Volatile var aaSessionSeen: Boolean = false

    /**
     * v2.3: Android Auto's head unit server (:5277) took the connection but never answered the
     * version request. Field logs (125M, 16 Oct) show it stays like that until the rider restarts it
     * in Android Auto's settings, and then works first time; so we say exactly that instead of the
     * generic "attached then dropped".
     */
    @Volatile var husWedged: Boolean = false

    /** True once we have decoded at least one AA video fps sample this session. */
    @Volatile var aaDecoding: Boolean = false

    /**
     * Until this elapsedRealtime, AA is starting guidance / media sink audio. The button bridge
     * still holds AVRCP (no yield to music apps); this flag is informational for logs / timing.
     */
    @Volatile private var aaAudioHoldUntilElapsed = 0L

    /** Call when AA requests / starts phone audio (guidance, media sink). */
    fun noteAaAudioActive(holdMs: Long = 5_000L) {
        val until = SystemClock.elapsedRealtime() + holdMs
        if (until > aaAudioHoldUntilElapsed) aaAudioHoldUntilElapsed = until
        MediaButtonBridge.instance?.yieldForAaAudio(holdMs)
    }

    fun isAaAudioHoldActive(): Boolean =
        SystemClock.elapsedRealtime() < aaAudioHoldUntilElapsed

    /**
     * Fired once (by the AA receiver) when decoded Android Auto video reaches a steady frame
     * rate — MainActivity uses this to auto-open the bike QR scanner so the AA→bike hand-off
     * doesn't depend on the user remembering to scan after starting the receiver.
     */
    @Volatile var onSteadyVideo: (() -> Unit)? = null

    /**
     * Bike-touchscreen → Android Auto input bridge. [EasyConnProber] decodes the dash's touch frames
     * (PXC media cmdType 32) and calls this with the raw bike-canvas coordinates, the finger index
     * ([pointerId], 0/1 — the CFDL26 dash reports two-finger multi-touch), and a normalised action
     * (0=DOWN, 1=UP, 2=MOVE). The live AA session ([AaReceiver]) installs a sink that letterbox-maps
     * the point into AA's video space and sends it over the AAP INPUT channel (as multi-touch, so
     * pinch-to-zoom works). Null when no AA session is active (touches are then dropped).
     */
    @Volatile var touchSink: ((action: Int, pointerId: Int, canvasX: Int, canvasY: Int) -> Unit)? = null

    /**
     * In-app phone preview ([HudViewActivity]) → Android Auto input bridge. Unlike [touchSink] (which
     * takes bike-canvas coordinates and letterbox-maps them), the preview activity already knows the AA
     * source size, so it passes coordinates **directly in AA video space** ([sourceX]/[sourceY]) and
     * this forwards them over the AAP INPUT channel as multi-touch. Only effective when the live AA
     * session advertises a touchscreen (touch dashes); null when no AA session is active.
     */
    @Volatile var previewTouchSink: ((action: Int, pointerId: Int, sourceX: Int, sourceY: Int) -> Unit)? = null

    /**
     * Phone/handlebar D-pad → Android Auto input bridge. The on-screen buttons ([ControlsActivity])
     * and the bike's handlebar buttons ([MediaButtonBridge]) call this with an Android keycode (see
     * [dev.zanderp.opencfmoto.aa.AaInput] KEY_* constants); the live AA session ([AaReceiver]) installs
     * a sink that forwards it over the AAP INPUT channel so Maps/Waze can be navigated without a
     * touchscreen. Null when no AA session is active (presses are dropped with a log line).
     */
    @Volatile var keySink: ((keycode: Int) -> Unit)? = null

    /**
     * Rotary-knob → Android Auto bridge. AA treats the head unit as rotary (see AaInput.KEY_SCROLL_WHEEL),
     * where the KNOB (not the D-pad) steps focus through list items. delta -1 = rotate back, +1 =
     * forward. Wired to the app's ⟲/⟳ buttons and the bike's short volume presses. Null when no AA
     * session is active.
     */
    @Volatile var scrollSink: ((delta: Int) -> Unit)? = null

    /**
     * Map day/night → Android Auto bridge. The UI (Dash view / Controls) calls this with the desired
     * night state; the live AA session ([AaReceiver]) forwards it as the head unit's NIGHT sensor so
     * Maps/Waze switch their map theme. Null when no AA session is active.
     */
    @Volatile var nightSink: ((isNight: Boolean) -> Unit)? = null
}

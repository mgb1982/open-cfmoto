// OpenCfMoto (uses AGPLv3 code/protocol ported from headunit-revived). Injects touch input into the
// Android Auto session: the CFMoto dash is a touchscreen and reports touches over PXC (see
// EasyConnProber cmdType 32); this forwards them to Gearhead over the AAP INPUT channel so Maps/Waze
// can be driven from the bike.
package dev.zanderp.opencfmoto.aa

import android.os.SystemClock
import dev.zanderp.opencfmoto.aa.proto.Input

/**
 * Sends touch events to Android Auto over the INPUT channel (declared as a touchscreen in
 * [ServiceDiscoveryResponse], sized to the AA video). Coordinates must already be in AA video space
 * (0..width, 0..height) — the caller letterbox-maps from the bike canvas first.
 *
 * The CFDL26 dash reports two-finger multi-touch (see [dev.zanderp.opencfmoto.EasyConnProber]), so
 * this tracks the set of currently-down pointers and emits the AAP multi-touch protocol: every event
 * carries all active pointers, with [Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_DOWN]/
 * `_POINTER_UP` for the 2nd+ finger and an `actionIndex` naming which pointer changed. That lets
 * Google Maps recognise pinch-to-zoom. Single-finger use is unchanged (plain DOWN/MOVE/UP).
 */
class AaInput(
    private val transport: AapTransport,
    private val log: (String) -> Unit,
) {
    /** Normalised actions from the bike decoder. */
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2

        // Android KeyEvent keycodes Android Auto understands for D-pad / rotary focus navigation.
        // Advertised in ServiceDiscoveryResponse (keycodesSupported) and sent by [sendKey].
        const val KEY_UP = 19     // KEYCODE_DPAD_UP
        const val KEY_DOWN = 20   // KEYCODE_DPAD_DOWN
        const val KEY_LEFT = 21   // KEYCODE_DPAD_LEFT
        const val KEY_RIGHT = 22  // KEYCODE_DPAD_RIGHT
        const val KEY_ENTER = 23  // KEYCODE_DPAD_CENTER (select)
        const val KEY_BACK = 4    // KEYCODE_BACK
        const val KEY_HOME = 3    // KEYCODE_HOME

        /**
         * The AAP "rotary controller" scroll-wheel code. We have no physical knob and never SEND it
         * as a key, but advertising it is what tells Android Auto this is a rotary/non-touch head unit
         * — which makes AA render a focus highlight for the D-pad keys to move. Without it AA accepts
         * the keys (HOME works) but has no focus to navigate, so the arrows do nothing on a non-touch
         * dash. Rotation itself is sent as a RelativeEvent by [sendScroll].
         */
        const val KEY_SCROLL_WHEEL = 65536

        /** KEYCODE_SEARCH — what a head unit sends for its "voice / Assistant" button. */
        const val KEY_ASSISTANT = 84

        /** v2.3 media keys (passenger mode): KEYCODE_MEDIA_PLAY_PAUSE / NEXT / PREVIOUS. */
        const val KEY_PLAY_PAUSE = 85
        const val KEY_NEXT = 87
        const val KEY_PREVIOUS = 88

        /** All keycodes we advertise — declared to AA so it enables rotary/focus navigation. */
        val SUPPORTED_KEYCODES = intArrayOf(
            KEY_SCROLL_WHEEL, KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, KEY_ENTER, KEY_BACK, KEY_HOME,
            KEY_ASSISTANT, KEY_PLAY_PAUSE, KEY_NEXT, KEY_PREVIOUS,
        )
    }

    /** Insertion-ordered set of down pointers → last known AA-space position. */
    private val pointers = LinkedHashMap<Int, Pair<Int, Int>>()

    /**
     * Send one key press (down then up) to Android Auto over the INPUT channel. Used by the phone's
     * on-screen D-pad and by the handlebar buttons ([dev.zanderp.opencfmoto.MediaButtonBridge]) so a
     * non-touch dash can be driven. AA acts on these when the keycodes were advertised in service
     * discovery ([SUPPORTED_KEYCODES]).
     */
    fun sendKey(keycode: Int) {
        try {
            sendKeyReport(keycode, down = true)
            sendKeyReport(keycode, down = false)
            log("[AA] sendKey keycode=$keycode")
        } catch (e: Exception) {
            log("[AA] sendKey failed: $e")
        }
    }

    /**
     * Emulate one click of the rotary knob: [delta] -1 = rotate back (focus to the previous item),
     * +1 = rotate forward (next item). On a rotary head unit — which is what AA treats us as once we
     * advertise [KEY_SCROLL_WHEEL] — the KNOB is the primary navigation and steps focus through list
     * items (the D-pad only jumps coarsely between panes). Sent as a RelativeEvent on INPUT.
     */
    fun sendScroll(delta: Int) {
        try {
            val rel = Input.RelativeEvent.newBuilder()
                .addData(
                    Input.RelativeEvent_Rel.newBuilder()
                        .setKeycode(KEY_SCROLL_WHEEL).setDelta(delta).build()
                )
                .build()
            val report = Input.InputReport.newBuilder()
                .setTimestamp(SystemClock.elapsedRealtimeNanos() / 1000)
                .setRelativeEvent(rel)
                .build()
            transport.send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
            log("[AA] sendScroll delta=$delta")
        } catch (e: Exception) {
            log("[AA] sendScroll failed: $e")
        }
    }

    private fun sendKeyReport(keycode: Int, down: Boolean) {
        val keyEvent = Input.KeyEvent.newBuilder()
            .addKeys(
                Input.Key.newBuilder()
                    .setKeycode(keycode).setDown(down).setMetastate(0).setLongpress(false).build()
            )
            .build()
        val report = Input.InputReport.newBuilder()
            .setTimestamp(SystemClock.elapsedRealtimeNanos() / 1000)
            .setKeyEvent(keyEvent)
            .build()
        transport.send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
    }

    /**
     * @param action    one of [ACTION_DOWN]/[ACTION_UP]/[ACTION_MOVE]
     * @param pointerId finger index reported by the dash (0/1)
     * @param x,y       pointer position in AA video coordinates
     */
    @Synchronized
    fun sendTouch(action: Int, pointerId: Int, x: Int, y: Int) {
        val pointerAction = when (action) {
            ACTION_DOWN -> {
                val first = pointers.isEmpty()
                pointers[pointerId] = x to y
                if (first) Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
                else Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_DOWN
            }
            ACTION_MOVE -> {
                // A MOVE for an untracked pointer promotes it to down (defensive against a lost DOWN).
                val promote = !pointers.containsKey(pointerId)
                pointers[pointerId] = x to y
                if (promote) {
                    if (pointers.size == 1) Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
                    else Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_DOWN
                } else {
                    Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE
                }
            }
            ACTION_UP -> {
                if (!pointers.containsKey(pointerId)) return
                pointers[pointerId] = x to y
                if (pointers.size == 1) Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
                else Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_UP
            }
            else -> return
        }

        val actionIndex = pointers.keys.indexOf(pointerId).coerceAtLeast(0)
        try {
            val touch = Input.TouchEvent.newBuilder()
            for ((id, pos) in pointers) {
                touch.addPointerData(
                    Input.TouchEvent.Pointer.newBuilder()
                        .setX(pos.first).setY(pos.second).setPointerId(id).build()
                )
            }
            touch.setActionIndex(actionIndex).setAction(pointerAction)
            val report = Input.InputReport.newBuilder()
                // AAP input timestamps are a monotonic microsecond clock.
                .setTimestamp(SystemClock.elapsedRealtimeNanos() / 1000)
                .setTouchEvent(touch.build())
                .build()
            transport.send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
        } catch (e: Exception) {
            log("[AA] sendTouch failed: $e")
        } finally {
            // Remove a lifted finger only after it has been reported in this (POINTER_)UP event.
            if (action == ACTION_UP) pointers.remove(pointerId)
        }
    }
}

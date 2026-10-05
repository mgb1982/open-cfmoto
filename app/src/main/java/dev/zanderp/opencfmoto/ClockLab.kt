// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/**
 * Tester knobs for dash-clock experiments. Defaults match Latest **2.0.13**
 * (empty `0x10451`, echo `0x10600`, BLE off). Process-wide copy is applied from
 * [AppSettings] so [PxcHandshake] can read them without a Context.
 */
enum class ClockQueryMode(val id: String) {
    EMPTY("empty"),
    CARBIT("carbit"),
    ZONTES("zontes"),
    NO_ACK("no_ack"),
    ;

    companion object {
        fun byId(id: String?): ClockQueryMode =
            entries.firstOrNull { it.id == id } ?: EMPTY
    }
}

enum class ClockTimeSyncMode(val id: String) {
    ECHO("echo"),
    PHONE("phone"),
    ;

    companion object {
        fun byId(id: String?): ClockTimeSyncMode =
            entries.firstOrNull { it.id == id } ?: ECHO
    }
}

enum class ClockLabPreset {
    LATEST,
    V2012,
    ZONTES,
    PHONE_SYNC,
    BT_LISTEN,
}

object ClockLab {
    @Volatile var query: ClockQueryMode = ClockQueryMode.EMPTY
    @Volatile var timeSync: ClockTimeSyncMode = ClockTimeSyncMode.ECHO
    @Volatile var bluetooth: Boolean = false
    /** Stay associated after Stop — some dashes drop the clock when SoftAP/P2P dies. */
    @Volatile var keepWifi: Boolean = false
    /** Zontes (21340) clock resync policy — see [ClockResyncMode]. */
    @Volatile var resyncMode: ClockResyncMode = ClockResyncMode.SMART

    /** elapsedRealtime deadline while a dash-clock resync is pending or reconnecting (UI hint). */
    @Volatile var resyncBusyUntil: Long = 0L
    fun resyncInProgress(): Boolean = android.os.SystemClock.elapsedRealtime() < resyncBusyUntil

    fun applyFrom(
        query: ClockQueryMode,
        timeSync: ClockTimeSyncMode,
        bluetooth: Boolean,
        keepWifi: Boolean = this.keepWifi,
    ) {
        this.query = query
        this.timeSync = timeSync
        this.bluetooth = bluetooth
        this.keepWifi = keepWifi
    }

    fun applyPreset(preset: ClockLabPreset) {
        when (preset) {
            ClockLabPreset.LATEST -> applyFrom(ClockQueryMode.EMPTY, ClockTimeSyncMode.ECHO, false)
            ClockLabPreset.V2012 -> applyFrom(ClockQueryMode.CARBIT, ClockTimeSyncMode.ECHO, false)
            ClockLabPreset.ZONTES -> applyFrom(ClockQueryMode.ZONTES, ClockTimeSyncMode.ECHO, false)
            ClockLabPreset.PHONE_SYNC -> applyFrom(ClockQueryMode.EMPTY, ClockTimeSyncMode.PHONE, false)
            ClockLabPreset.BT_LISTEN -> applyFrom(ClockQueryMode.EMPTY, ClockTimeSyncMode.ECHO, true)
        }
    }

    fun matchingPreset(): ClockLabPreset? = when {
        query == ClockQueryMode.EMPTY && timeSync == ClockTimeSyncMode.ECHO && !bluetooth ->
            ClockLabPreset.LATEST
        query == ClockQueryMode.CARBIT && timeSync == ClockTimeSyncMode.ECHO && !bluetooth ->
            ClockLabPreset.V2012
        query == ClockQueryMode.ZONTES && timeSync == ClockTimeSyncMode.ECHO && !bluetooth ->
            ClockLabPreset.ZONTES
        query == ClockQueryMode.EMPTY && timeSync == ClockTimeSyncMode.PHONE && !bluetooth ->
            ClockLabPreset.PHONE_SYNC
        query == ClockQueryMode.EMPTY && timeSync == ClockTimeSyncMode.ECHO && bluetooth ->
            ClockLabPreset.BT_LISTEN
        else -> null
    }

    fun banner(channel: String?): String {
        val ch = channel?.trim().orEmpty().ifEmpty { "-" }
        val bt = if (bluetooth) "on" else "off"
        val kw = if (keepWifi) "on" else "off"
        return "[CLOCK-LAB] query=${query.id} timeSync=${timeSync.id} bt=$bt keepWifi=$kw channel=$ch"
    }
}

/**
 * Zontes (channel 21340) clock resync: drop + re-probe the PXC link so the dash asks QUERY_TIME again.
 *  - [SMART]: only when the dash asked for the time late (≥ [PxcHandshake.SLOW_QUERY_TIME_MS] after
 *    picking CAR_CTRL) — in the field every failed sync was a late ask and every early one was fine —
 *    and retry up to [PxcHandshake.MAX_RESYNCS_PER_BOOT] times per dash boot.
 *  - [ONCE]: always once, on the first session of each dash boot.
 *  - [OFF]: never.
 */
enum class ClockResyncMode(val id: String) {
    SMART("smart"),
    ONCE("once"),
    OFF("off"),
    ;

    companion object {
        fun byId(id: String?): ClockResyncMode = entries.firstOrNull { it.id == id } ?: SMART
    }
}

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

    fun applyFrom(query: ClockQueryMode, timeSync: ClockTimeSyncMode, bluetooth: Boolean) {
        this.query = query
        this.timeSync = timeSync
        this.bluetooth = bluetooth
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
        return "[CLOCK-LAB] query=${query.id} timeSync=${timeSync.id} bt=$bt channel=$ch"
    }
}

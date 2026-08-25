// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Body for `0x10451` — reply to bike `ECP_C2P_QUERY_TIME` (`0x10450`).
 *
 * 2.0.11 / 2.0.12 sent Carbit's `time` + `dateTime` to every HU. Griffin / X-Cape / Voge / QJ
 * jumped hours, so 2.0.13 empty-acked again. Official Zontes Smart also sends `currentTime`
 * (epoch UTC ms + local TZ offset) and `currentTimeZone`. Clock lab can force any of those
 * bodies; [ackIfZontes] still gates the 2.0.16-pre default on channel `21340`.
 */
internal object HuQueryTime {
    internal const val ZONTES_125X_CHANNEL = "21340"

    private val dateTimeFmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)

    data class Ack(
        val payload: ByteArray,
        val dateTime: String,
        val timeMillis: Long,
        val currentTime: Long,
        val timeZone: String,
    )

    fun dateTimeString(
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): String = synchronized(dateTimeFmt) {
        dateTimeFmt.timeZone = zone
        String.format(Locale.US, "%s:%03d", dateTimeFmt.format(Date(nowMillis)), (nowMillis % 1000L).toInt())
    }

    /** Carbit / 2.0.12 body — `time` + `dateTime` only. */
    fun carbit(
        nowMillis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): Ack {
        val dateTime = dateTimeString(nowMillis, zone)
        val json = "{\"time\":$nowMillis,\"dateTime\":\"$dateTime\"}"
        return Ack(json.toByteArray(Charsets.UTF_8), dateTime, nowMillis, nowMillis, zone.id)
    }

    /** Zontes Smart extras — not gated on channel. Clock lab uses this when the knob is on. */
    fun zontesOem(
        nowMillis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): Ack {
        val dateTime = dateTimeString(nowMillis, zone)
        val currentTime = nowMillis + zone.getOffset(nowMillis)
        val json = "{\"time\":$nowMillis,\"currentTime\":$currentTime," +
            "\"currentTimeZone\":\"${zone.id}\",\"dateTime\":\"$dateTime\"}"
        return Ack(json.toByteArray(Charsets.UTF_8), dateTime, nowMillis, currentTime, zone.id)
    }

    fun ackIfZontes(
        channel: String?,
        nowMillis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): Ack? {
        if (channel?.trim() != ZONTES_125X_CHANNEL) return null
        return zontesOem(nowMillis, zone)
    }
}

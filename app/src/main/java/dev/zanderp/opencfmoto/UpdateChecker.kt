// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// Adapted from ionutradu252/open-cflink.
package dev.zanderp.opencfmoto

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer APK. Automatic check at most once a day before connect;
 * failures are silent (bike Wi‑Fi has no internet). Manual check from Setup.
 */
object UpdateChecker {

    /**
     * Zontes fork: releases are published as zontes-N on this repo and signed with the fork's key,
     * so an upstream (zanderp) APK can't install over it — never offer one. Fork builds compare the
     * zontes number (versionName "2.0.18-zontesN"); "-dev" builds only see releases on a manual check.
     */
    const val REPO = "mgb1982/open-cfmoto"

    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREF = "updates"
    private const val KEY_LAST_CHECK = "lastCheckMs"
    private const val KEY_SKIPPED = "skippedVersion"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String?,
        val pageUrl: String,
    ) {
        val downloadUrl: String get() = apkUrl ?: pageUrl
    }

    /** Blocking; call off the main thread. Null = nothing newer / offline / rate-limited. */
    fun check(context: Context, manual: Boolean): Release? {
        if (!manual && !dueForCheck(context)) return null
        val release = fetch() ?: return null
        markChecked(context)
        val newer = isNewerFork(release.version, BuildConfig.VERSION_NAME, manual)
            ?: isNewer(release.version, BuildConfig.VERSION_NAME)
        if (!newer) return null
        if (!manual && release.version == skipped(context)) return null
        return release
    }

    private fun fetch(): Release? = try {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OpenCfMoto")
        }
        try {
            if (conn.responseCode != 200) null else {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val assets = json.optJSONArray("assets")
                var apk: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apk = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                Release(
                    version = json.optString("tag_name").ifBlank { json.optString("name") },
                    notes = json.optString("body").trim(),
                    apkUrl = apk,
                    pageUrl = json.optString("html_url", "https://github.com/$REPO/releases"),
                )
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Fork versioning: tag "zontes-N" vs versionName "...-zontesM". Null when the tag isn't a fork
     * release (fall back to [isNewer]). A "-dev" build is never nagged automatically; on a manual
     * check it is offered the latest release.
     */
    internal fun isNewerFork(latestTag: String, currentVersion: String, manual: Boolean): Boolean? {
        val latest = Regex("zontes-?(\\d+)", RegexOption.IGNORE_CASE).find(latestTag)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val current = Regex("zontes(\\d+)", RegexOption.IGNORE_CASE).find(currentVersion)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: return manual
        return latest > current
    }

    internal fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String): List<Int> =
        Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun dueForCheck(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_LAST_CHECK, 0L) > CHECK_INTERVAL_MS

    private fun markChecked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    private fun skipped(context: Context): String? = prefs(context).getString(KEY_SKIPPED, null)

    fun skip(context: Context, version: String) {
        prefs(context).edit().putString(KEY_SKIPPED, version).apply()
    }
}

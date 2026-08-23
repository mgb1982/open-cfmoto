// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Portable **bike-tuning** pack for Discord / friends: profile, resolution, fit, margins,
 * handlebar mapping, transport — the stuff that makes a dash work. Skips personal prefs
 * (map theme, saved places, auto-connect, trip logging, tap timing, …).
 *
 * Never includes QR passwords, serials, or bike photos.
 *
 * Import applies to the **currently selected** garage bike (via [BikeScope]).
 */
object SettingsBackup {
    const val FORMAT = "opencfmoto.settings"
    const val VERSION = 1

    data class Result(val ok: Boolean, val message: String)

    fun exportJson(context: Context): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exportedAt", isoNow())
        root.put("appVersion", BuildConfig.VERSION_NAME)
        // No bike name / SSID — those are identifying. Share the tuning knobs only;
        // profileOverride + model class already describe which dash shape this pack targets.
        root.put(
            "bikeHint",
            JSONObject()
                .put("profile", BikeProfileHolder.active.name)
                .put("profileOverride", ProfilePrefs.get(context).id),
        )

        val s = JSONObject()
        s.put("videoQuality", VideoPrefs.get(context).name)
        s.put("screenFit", VideoPrefs.fit(context).name)
        s.put("mirrorOrientation", VideoPrefs.mirrorOrientation(context).name)
        s.put("powerMode", VideoPrefs.power(context).name)
        s.put("resolutionMode", VideoPrefs.resolution(context).name)
        s.put("profileOverride", ProfilePrefs.get(context).id)
        s.put("controlAa", ButtonMode.isControlAa(context))
        s.put("forceNonTouch", AppSettings.forceNonTouch(context))
        s.put("forceTouch", AppSettings.forceTouch(context))
        s.put("wifiTransport", AppSettings.transport(context).id)

        ScreenMargins.load(context)
        s.put(
            "screenMargins",
            JSONObject()
                .put("customised", ScreenMargins.customised)
                .put("top", if (ScreenMargins.customised) ScreenMargins.top else 0)
                .put("bottom", if (ScreenMargins.customised) ScreenMargins.bottom else 0)
                .put("left", if (ScreenMargins.customised) ScreenMargins.left else 0)
                .put("right", if (ScreenMargins.customised) ScreenMargins.right else 0),
        )

        val buttons = JSONObject()
        for (g in ButtonGesture.entries) {
            buttons.put(g.id, ButtonMap.get(context, g).id)
        }
        s.put("buttons", buttons)

        root.put("settings", s)
        return root.toString(2)
    }

    fun importJson(context: Context, json: String): Result {
        val root = try {
            JSONObject(json.trim().removePrefix("\uFEFF"))
        } catch (e: Exception) {
            return Result(false, "Not valid JSON: ${e.message}")
        }
        val format = root.optString("format", "")
        if (format != FORMAT) {
            return Result(false, "Not an OpenCfMoto settings file (format=$format)")
        }
        val ver = root.optInt("version", 0)
        if (ver < 1 || ver > VERSION) {
            return Result(false, "Unsupported settings version $ver (this app reads 1…$VERSION)")
        }
        val s = root.optJSONObject("settings")
            ?: return Result(false, "Missing settings object")

        try {
            applySettings(context, s)
        } catch (e: Exception) {
            return Result(false, "Import failed: ${e.message}")
        }

        val hint = root.optJSONObject("bikeHint")
        val profile = hint?.optString("profile")?.takeIf { it.isNotBlank() && it != "null" }
            ?: hint?.optString("profileOverride")?.takeIf { it.isNotBlank() && it != "null" }
        val onto = BikeMemory.lastBikeName(context) ?: "selected bike"
        val msg = if (profile != null) "Imported bike tuning ($profile) onto $onto"
        else "Bike tuning imported onto $onto"
        return Result(true, msg)
    }

    /** Filename-friendly slug — profile class only, never the rider’s bike name/SSID. */
    fun suggestedFileName(context: Context): String {
        val ov = ProfilePrefs.get(context)
        val slug = when (ov) {
            ProfileOverride.AUTO -> "auto"
            else -> ov.id
        }
        return "OpenCfMoto-$slug-settings.json"
    }

    private fun applySettings(context: Context, s: JSONObject) {
        s.optString("videoQuality").takeIf { it.isNotBlank() }?.let {
            runCatching { VideoPrefs.set(context, VideoQuality.valueOf(it)) }
        }
        s.optString("screenFit").takeIf { it.isNotBlank() }?.let {
            runCatching { VideoPrefs.setFit(context, ScreenFit.valueOf(it)) }
        }
        s.optString("mirrorOrientation").takeIf { it.isNotBlank() }?.let {
            runCatching { VideoPrefs.setMirrorOrientation(context, MirrorOrientation.valueOf(it)) }
        }
        s.optString("powerMode").takeIf { it.isNotBlank() }?.let {
            runCatching { VideoPrefs.setPower(context, PowerMode.valueOf(it)) }
        }
        s.optString("resolutionMode").takeIf { it.isNotBlank() }?.let {
            runCatching { VideoPrefs.setResolution(context, ResolutionMode.valueOf(it)) }
        }
        if (s.has("profileOverride")) {
            ProfilePrefs.set(context, ProfileOverride.byId(s.optString("profileOverride")))
        }
        if (s.has("controlAa")) ButtonMode.set(context, s.optBoolean("controlAa"))
        if (s.has("forceNonTouch")) AppSettings.setForceNonTouch(context, s.optBoolean("forceNonTouch"))
        if (s.has("forceTouch")) AppSettings.setForceTouch(context, s.optBoolean("forceTouch"))
        if (s.has("wifiTransport")) {
            AppSettings.setTransport(context, WifiTransport.byId(s.optString("wifiTransport")))
        }

        s.optJSONObject("screenMargins")?.let { m ->
            if (m.optBoolean("customised")) {
                ScreenMargins.set(
                    context,
                    m.optInt("top"),
                    m.optInt("bottom"),
                    m.optInt("left"),
                    m.optInt("right"),
                )
            } else {
                ScreenMargins.reset(context)
            }
        }

        s.optJSONObject("buttons")?.let { b ->
            for (g in ButtonGesture.entries) {
                if (!b.has(g.id)) continue
                ButtonAction.byId(b.optString(g.id))?.let { ButtonMap.set(context, g, it) }
            }
        }

        // Older exports may still contain personal prefs — ignore them on purpose.
        AppSettings.applyToHolder(context)
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Anonymous install heartbeat + crash/error upload to the OpenCfMoto Cloudflare Worker.
 *
 * - Random UUID only (not Google account, phone number, bike SSID, or GPS).
 * - Opt-out in Setup → Privacy ([AppSettings.anonymousTelemetry]).
 * - Offline: events sit in a small queue file and flush when [AppHttp] has an internet pin
 *   (cellular while on bike Wi‑Fi, or normal Wi‑Fi at home).
 * - Payloads are truncated and password/serial patterns are stripped.
 */
object AnonymousTelemetry {
    private const val PREFS = "opencfmoto_telemetry"
    private const val KEY_UUID = "anon_uuid"
    private const val KEY_LAST_PING_MS = "last_ping_ms"
    private const val KEY_PING_ENDPOINT = "last_ping_endpoint"
    private const val KEY_CRASH_SENT = "crash_fingerprint_sent"
    private const val QUEUE_FILE = "telemetry_queue.json"
    private const val MAX_QUEUE = 24
    private const val MAX_PAYLOAD = 40_000
    private const val PING_INTERVAL_MS = 20L * 60L * 60L * 1000L // ~daily-ish, 20h
    private const val ERROR_COOLDOWN_MS = 30L * 60L * 1000L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "opencfmoto-telemetry").apply { isDaemon = true }
    }
    private val flushRunning = AtomicBoolean(false)
    @Volatile private var lastErrorEnqueueMs = 0L

    fun onAppStart(ctx: Context) {
        if (!AppSettings.anonymousTelemetry(ctx)) return
        io.execute {
            try {
                ensureUuid(ctx)
                enqueuePendingCrash(ctx)
                maybeEnqueuePing(ctx)
                flush(ctx)
            } catch (_: Exception) {
            }
        }
    }

    /** Queue a non-fatal connection/UI error (rate-limited). No-op when telemetry is off. */
    fun reportError(ctx: Context, detail: String) {
        if (!AppSettings.anonymousTelemetry(ctx)) return
        val now = System.currentTimeMillis()
        if (now - lastErrorEnqueueMs < ERROR_COOLDOWN_MS) return
        lastErrorEnqueueMs = now
        io.execute {
            try {
                enqueue(ctx, "error", redact(detail).take(2_000))
                flush(ctx)
            } catch (_: Exception) {
            }
        }
    }

    fun flushSoon(ctx: Context) {
        if (!AppSettings.anonymousTelemetry(ctx)) return
        io.execute {
            try {
                flush(ctx)
            } catch (_: Exception) {
            }
        }
    }

    /** When the rider turns telemetry off, drop the outbound queue (UUID kept so re-enable is stable). */
    fun onDisabled(ctx: Context) {
        io.execute {
            try {
                queueFile(ctx).delete()
            } catch (_: Exception) {
            }
        }
    }

    fun anonUuidForDisplay(ctx: Context): String {
        return try {
            ensureUuid(ctx)
        } catch (_: Exception) {
            "—"
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensureUuid(ctx: Context): String {
        val p = prefs(ctx)
        val existing = p.getString(KEY_UUID, null)
        if (!existing.isNullOrBlank()) return existing
        val id = UUID.randomUUID().toString()
        p.edit().putString(KEY_UUID, id).apply()
        return id
    }

    private fun endpoint(): String {
        val base = BuildConfig.TELEMETRY_URL.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return "$base/v1/ping"
    }

    private fun maybeEnqueuePing(ctx: Context) {
        val p = prefs(ctx)
        // A new receiver (e.g. v1 → v1.1 moved from the upstream Worker to RideScreen AA's own)
        // hasn't seen this install yet: ping now instead of waiting out the 20 h interval.
        val sameEndpoint = p.getString(KEY_PING_ENDPOINT, null) == endpoint()
        val last = if (sameEndpoint) p.getLong(KEY_LAST_PING_MS, 0L) else 0L
        if (System.currentTimeMillis() - last < PING_INTERVAL_MS) return
        enqueue(ctx, "ping", null)
    }

    private fun enqueuePendingCrash(ctx: Context) {
        val text = CrashGuard.pendingCrashText(ctx) ?: return
        val fp = fingerprint(text)
        if (prefs(ctx).getString(KEY_CRASH_SENT, null) == fp) return
        enqueue(ctx, "crash", redact(text))
        prefs(ctx).edit().putString(KEY_CRASH_SENT, fp).apply()
    }

    private fun fingerprint(text: String): String {
        // Stable-enough without crypto deps — first stack line + length.
        val line = text.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?: text.take(120)
        return "${line.hashCode()}:${text.length}"
    }

    private fun queueFile(ctx: Context) = File(ctx.applicationContext.filesDir, QUEUE_FILE)

    @Synchronized
    private fun enqueue(ctx: Context, type: String, payload: String?) {
        val arr = readQueue(ctx)
        while (arr.length() >= MAX_QUEUE) arr.remove(0)
        val o = JSONObject()
        o.put("type", type)
        o.put("ts", System.currentTimeMillis())
        if (!payload.isNullOrBlank()) {
            o.put("payload", payload.take(MAX_PAYLOAD))
        }
        arr.put(o)
        writeQueue(ctx, arr)
    }

    private fun readQueue(ctx: Context): JSONArray {
        val f = queueFile(ctx)
        if (!f.exists() || f.length() == 0L) return JSONArray()
        return try {
            JSONArray(f.readText())
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun writeQueue(ctx: Context, arr: JSONArray) {
        try {
            queueFile(ctx).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun flush(ctx: Context) {
        if (!flushRunning.compareAndSet(false, true)) return
        try {
            if (!AppSettings.anonymousTelemetry(ctx)) return
            val url = endpoint()
            if (url.isEmpty()) return
            val uuid = ensureUuid(ctx)
            val arr = readQueue(ctx)
            if (arr.length() == 0) return
            // Prefer a validated uplink (cellular while on bike SoftAP). If none, still try the
            // default route (home Wi‑Fi); failures stay queued for the next launch / flush.
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val ok = postOne(ctx, url, uuid, item)
                if (!ok) {
                    kept.put(item)
                    for (j in i + 1 until arr.length()) {
                        arr.optJSONObject(j)?.let { kept.put(it) }
                    }
                    break
                } else if (item.optString("type") == "ping") {
                    prefs(ctx).edit().putLong(KEY_LAST_PING_MS, System.currentTimeMillis())
                        .putString(KEY_PING_ENDPOINT, url).apply()
                }
            }
            writeQueue(ctx, kept)
        } finally {
            flushRunning.set(false)
        }
    }

    private fun postOne(ctx: Context, url: String, uuid: String, item: JSONObject): Boolean {
        return try {
            val body = JSONObject()
            body.put("uuid", uuid)
            body.put("type", item.optString("type", "ping"))
            body.put("version", BuildConfig.VERSION_NAME)
            body.put("versionCode", BuildConfig.VERSION_CODE)
            body.put("androidSdk", Build.VERSION.SDK_INT)
            body.put("locale", Locale.getDefault().toLanguageTag())
            val payload = item.optString("payload", "")
            if (payload.isNotBlank()) body.put("payload", payload)
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            val conn = AppHttp.openUrl(url)
            conn.connectTimeout = 12_000
            conn.readTimeout = 20_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("User-Agent", AppHttp.USER_AGENT)
            conn.setRequestProperty("Content-Length", bytes.size.toString())
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /** Strip secrets even if Share Logs has "include secrets" on. */
    private fun redact(text: String): String {
        var s = text
        // Wi‑Fi / QR style secrets
        s = s.replace(Regex("""(?i)(pwd|password|passphrase)\s*[=:]\s*\S+"""), "$1=***")
        s = s.replace(Regex("""(?i)"pwd"\s*:\s*"[^"]*""""), "\"pwd\":\"***\"")
        s = s.replace(Regex("""(?i)ssid\s*[=:]\s*\S+"""), "ssid=***")
        s = s.replace(Regex("""(?i)\b([A-Z0-9]{10,})\b""")) { m ->
            // Likely serials / long tokens — keep short codes
            if (m.value.length >= 12) "***" else m.value
        }
        return s.take(MAX_PAYLOAD)
    }
}

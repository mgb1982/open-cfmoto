// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Keeps diagnostics alive across fatal crashes:
 *  - installs a process-wide uncaught-exception handler
 *  - writes the stack + [LogBus] snapshot to durable files under [Context.getFilesDir]
 *  - on next launch, reloads those into [LogBus] so Share Logs still works
 *
 * Session restore is consume-once: after hydrate we delete [SESSION_FILE] so the next
 * [persistSession] cannot re-wrap the same blob (nested "restored session log" banners).
 */
object CrashGuard {

    private const val CRASH_FILE = "last_crash.txt"
    private const val SESSION_FILE = "last_session.log"
    private const val RESTORE_BANNER = "--- restored session log (saved before last exit/crash) ---"
    private const val PREV_CRASH_BANNER = "--- previous fatal crash (also in files/last_crash.txt) ---"
    private const val END_CRASH_BANNER = "--- end previous crash — use Share Logs to send this ---"
    /** Cap on-disk session / crash-attached snapshot (tail). */
    private const val MAX_SESSION_BYTES = 256 * 1024

    private val stampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var installed = false
    @Volatile private var hydrated = false

    fun install(appContext: Context) {
        if (installed) return
        installed = true
        val app = appContext.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                recordFatal(app, thread, error)
            } catch (t: Throwable) {
                try {
                    Log.e(LogBus.TAG, "CrashGuard failed while recording", t)
                } catch (_: Throwable) {
                }
            }
            try {
                previous?.uncaughtException(thread, error)
            } catch (_: Throwable) {
                try {
                    android.os.Process.killProcess(android.os.Process.myPid())
                } catch (_: Throwable) {
                }
                exitProcess(10)
            }
        }
    }

    /** Flush the in-memory log so a later kill/crash still has a session file. */
    fun persistSession(appContext: Context) {
        try {
            val text = liveSnapshotForDisk()
            if (text.isBlank()) return
            File(appContext.applicationContext.filesDir, SESSION_FILE).writeText(text)
        } catch (_: Exception) {
        }
    }

    /**
     * On cold start: pull the previous session + crash report into [LogBus] so the on-screen log
     * and Share Logs show what happened before the death. Safe to call once per process.
     * Returns true when a crash report was pending.
     */
    fun hydrateLogBus(appContext: Context): Boolean {
        if (hydrated) return pendingCrashText(appContext) != null
        hydrated = true
        val app = appContext.applicationContext
        val hadCrash = crashFile(app).exists() && crashFile(app).length() > 0L
        try {
            val session = File(app.filesDir, SESSION_FILE)
            if (session.exists() && session.length() > 0L) {
                val body = session.readText().trimEnd()
                archive(app, body, session.lastModified())
                // Consume so the next persist cannot nest another restore banner around this blob.
                try { session.delete() } catch (_: Exception) {}
                if (body.isNotBlank()) {
                    val alreadyRestored = body.lineSequence().firstOrNull { it.isNotBlank() }
                        ?.contains(RESTORE_BANNER) == true
                    val block = if (alreadyRestored) {
                        "$body\n"
                    } else {
                        "$RESTORE_BANNER\n$body\n"
                    }
                    LogBus.restore(block)
                }
            }
            val crash = pendingCrashText(app)
            if (crash != null) {
                LogBus.log(PREV_CRASH_BANNER)
                for (line in crash.lineSequence()) {
                    if (line.isNotEmpty()) LogBus.log(line)
                }
                LogBus.log(END_CRASH_BANNER)
            }
        } catch (e: Exception) {
            try {
                LogBus.log("[crash] hydrate failed: $e")
            } catch (_: Exception) {
            }
        }
        return hadCrash
    }

    /**
     * Keep the previous sessions (a ride in the background whose process Android later killed would
     * otherwise vanish from the on-screen log): last [HISTORY_KEEP] files, attached by Share Logs.
     */
    private const val HISTORY_KEEP = 5

    fun historyDir(appContext: Context): File = File(appContext.applicationContext.filesDir, "log-history")

    private fun archive(app: Context, body: String, at: Long) {
        try {
            if (body.isBlank()) return
            val dir = historyDir(app).apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(at))
            File(dir, "session-$stamp.log").writeText(body)
            dir.listFiles()?.sortedByDescending { it.name }?.drop(HISTORY_KEEP)?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    fun pendingCrashText(appContext: Context): String? {
        val f = crashFile(appContext.applicationContext)
        if (!f.exists() || f.length() == 0L) return null
        return try {
            f.readText()
        } catch (_: Exception) {
            null
        }
    }

    fun crashFile(appContext: Context): File =
        File(appContext.applicationContext.filesDir, CRASH_FILE)

    fun clearCrash(appContext: Context) {
        try {
            crashFile(appContext).delete()
        } catch (_: Exception) {
        }
    }

    /** Clear Logs: drop memory buffer and durable session so the next launch stays clean. */
    fun clearSession(appContext: Context) {
        try {
            File(appContext.applicationContext.filesDir, SESSION_FILE).delete()
        } catch (_: Exception) {
        }
    }

    private fun recordFatal(app: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val stack = sw.toString()
        val header = buildString {
            append("=== OpenCfMoto FATAL ")
            append(stampFmt.format(Date()))
            append(" ===\n")
            append("version=").append(BuildConfig.VERSION_NAME)
            append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("thread=").append(thread.name).append('\n')
            append(stack.trimEnd()).append('\n')
        }
        // Best-effort: also stamp LogBus so the snapshot includes a final line.
        try {
            LogBus.log("[FATAL] ${error.javaClass.name}: ${error.message}")
        } catch (_: Exception) {
        }
        val snapshot = try {
            liveSnapshotForDisk()
        } catch (_: Exception) {
            ""
        }
        val body = header + "\n=== LogBus snapshot ===\n" + snapshot
        File(app.filesDir, CRASH_FILE).writeText(body.take(MAX_SESSION_BYTES * 2))
        if (snapshot.isNotBlank()) {
            File(app.filesDir, SESSION_FILE).writeText(snapshot)
        }
        Log.e(LogBus.TAG, "FATAL on ${thread.name}", error)
    }

    /** Live log only — strip nested restore / previous-crash banners; keep the tail. */
    private fun liveSnapshotForDisk(): String {
        val raw = try {
            LogBus.snapshot()
        } catch (_: Exception) {
            return ""
        }
        if (raw.isBlank()) return ""
        val stripped = stripRestoreBlocks(raw).trimEnd()
        if (stripped.isBlank()) return ""
        return if (stripped.length <= MAX_SESSION_BYTES) {
            stripped + "\n"
        } else {
            stripped.takeLast(MAX_SESSION_BYTES) + "\n"
        }
    }

    internal fun stripRestoreBlocks(text: String): String {
        val lines = text.lineSequence().toList()
        if (lines.isEmpty()) return text
        val out = ArrayList<String>(lines.size)
        var skippingPrevCrash = false
        for (line in lines) {
            when {
                line.contains(RESTORE_BANNER) -> {
                    // Drop the banner; keep following live lines (may still include old restore body —
                    // consume-on-hydrate prevents re-wrapping on the next cycle).
                }
                line.contains(PREV_CRASH_BANNER) -> skippingPrevCrash = true
                line.contains(END_CRASH_BANNER) -> skippingPrevCrash = false
                skippingPrevCrash -> { /* drop prior crash dump from persisted session */ }
                else -> out.add(line)
            }
        }
        return out.joinToString("\n")
    }
}

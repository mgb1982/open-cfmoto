// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Application
import org.maplibre.android.MapLibre

/** Process entry — installs crash capture before any Activity runs. */
class OpenCfMotoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            LogBus.includeSecrets = AppSettings.includeSecretsInLogs(this)
        } catch (_: Exception) {
        }
        CrashGuard.install(this)
        CrashGuard.hydrateLogBus(this)
        try {
            AppSettings.applyToHolder(this)
        } catch (_: Exception) {
        }
        // After hydrate so Share Logs still show prior crash, then stamp this process build.
        LogBus.logSessionBanner()
        AppHttp.init(this)
        try {
            AnonymousTelemetry.onAppStart(this)
        } catch (_: Exception) {
        }
        try {
            MapLibre.getInstance(this)
            // Pin MapLibre style/tile HTTP to cellular while the process is bound to bike Wi‑Fi.
            org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(AppHttp.mapLibreOkHttpClient())
        } catch (e: Exception) {
            android.util.Log.w("OpenCfMoto", "MapLibre init failed: $e")
        }
    }
}

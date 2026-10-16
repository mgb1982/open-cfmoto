// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP helper for the map/nav online services (Photon, Nominatim, OSRM, Overpass).
 *
 * Goals: one identifying User-Agent, sane timeouts, a response-size guard, polite per-host
 * request spacing (so we respect free OSM-based services' usage policies and don't get
 * blocked), and friendly errors so the UI degrades instead of "breaking" on 429/5xx.
 *
 * While projecting, the process is bound to bike Wi‑Fi (no uplink). All map/nav traffic is
 * pinned to a validated internet network — preferably cellular — via [internetNetwork].
 */
object AppHttp {
    /** Identifying UA required by the OSM/Nominatim/Overpass usage policies. */
    val USER_AGENT: String =
        "OpenCfMoto/${BuildConfig.VERSION_NAME} (moto dash map; +https://github.com/zanderp/open-cfmoto)"

    private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024 // guard against runaway responses
    /** Long road routes (steps + full geometry) routinely exceed 8 MB on public demos. */
    const val MAX_ROUTE_BYTES = 24 * 1024 * 1024

    /** Thrown for non-2xx responses; [busy] marks rate-limit / temporary-unavailable states. */
    class HttpError(val code: Int, message: String, val busy: Boolean) : IOException(message)

    private var appContext: Context? = null
    @Volatile private var heldCellular: Network? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var mapLibreClient: OkHttpClient? = null
    @Volatile private var mapLibreReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called once from [OpenCfMotoApp] so we can reach an internet-capable network while riding. */
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        // v2.3 battery fix: no cellular request at app start any more. Holding it keeps the mobile
        // radio up next to home Wi-Fi for as long as the process lives — and the low-power bike
        // watch keeps the process alive for hours after a ride (field report: 36 h → 12 h a charge).
        // It's requested when a session starts (AndroidAutoService / BikeWifi) and dropped on Stop.
    }

    /** Session over: let Android park mobile data again. */
    fun releaseCellularUplink(why: String) {
        mainHandler.removeCallbacks(idleRelease)
        val cb = cellularCallback ?: return
        cellularCallback = null
        heldCellular = null
        try {
            (appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        LogBus.log("[NET] released cellular uplink ($why)")
        applyMapLibreClientAsync()
    }

    /** A one-off request outside a ride (route, ETA…) shouldn't pin mobile data for the rest of the day. */
    private val idleRelease = object : Runnable {
        override fun run() {
            if (AndroidAutoService.isRunning) return   // a session started meanwhile: it releases on Stop
            releaseCellularUplink("no session")
        }
    }

    /**
     * Keep a cellular (or other internet) network requested so Android doesn't park mobile data
     * while the process default route is the bike's captive Wi‑Fi.
     */
    fun ensureCellularUplink() {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        if (!AndroidAutoService.isRunning) {
            mainHandler.removeCallbacks(idleRelease)
            mainHandler.postDelayed(idleRelease, 2 * 60_000L)
        }
        if (cellularCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                heldCellular = network
                LogBus.log("[NET] cellular/internet uplink available for map/routing")
                // ConnectivityThread — do not touch MapLibre here (FATAL on 2.0.17/18).
                applyMapLibreClientAsync()
            }

            override fun onLost(network: Network) {
                if (heldCellular == network) heldCellular = null
                LogBus.log("[NET] cellular/internet uplink lost")
                applyMapLibreClientAsync()
            }
        }
        cellularCallback = cb
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            cm.requestNetwork(req, cb)
            LogBus.log("[NET] requested cellular uplink (map/routing pin)")
        } catch (e: Exception) {
            // Fall back to any internet-capable network (e.g. real Wi‑Fi when parked).
            try {
                val any = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.requestNetwork(any, cb)
                LogBus.log("[NET] requested any-internet uplink (cellular request failed: $e)")
            } catch (e2: Exception) {
                LogBus.log("[NET] uplink request failed: $e2")
                cellularCallback = null
            }
        }
    }

    /**
     * While projecting to the bike the whole process is bound to the bike Wi-Fi, which has NO
     * internet uplink (see [BikeWifi.bindProcessToNetwork]). Map/nav calls must therefore be pinned
     * to a network that actually has internet — the phone's 4G/5G — or they'd fail even
     * though data is available. Returns null when we can't find one (then we use the default route).
     */
    fun internetNetwork(): Network? {
        heldCellular?.let { n ->
            if (networkUsable(n) && !isVpnNetwork(n)) return n
        }
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        var cellUnvalidated: Network? = null
        var otherValidated: Network? = null
        var otherInternet: Network? = null
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            // Prefer the underlying cellular/Wi‑Fi, not a VPN wrapper — Maps/tiles through a
            // half-broken VPN look like "VPN issues" while the bike link is fine.
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            // Skip unvalidated Wi‑Fi (bike captive AP looks like Wi‑Fi without a real uplink).
            if (!cellular && !validated && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            when {
                cellular && validated -> return n
                cellular && cellUnvalidated == null -> cellUnvalidated = n
                validated && otherValidated == null -> otherValidated = n
                otherInternet == null -> otherInternet = n
            }
        }
        return cellUnvalidated ?: otherValidated ?: otherInternet
    }

    private fun networkUsable(n: Network): Boolean {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isVpnNetwork(n: Network): Boolean {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    /** Open the URL over a validated-internet network when one exists, else the process default. */
    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        val net = internetNetwork()
        val conn = net?.openConnection(u) ?: u.openConnection()
        return conn as HttpURLConnection
    }

    /**
     * Public open for callers that need a raw [HttpURLConnection] on the internet-capable network
     * (e.g. osmdroid tile downloads while the process is bound to bike Wi‑Fi).
     */
    fun openUrl(url: String): HttpURLConnection = open(url)

    /** True when [openUrl] / [getText] will pin to a validated internet network (prefer cellular). */
    fun hasInternetPin(): Boolean = internetNetwork() != null

    /** True when the pin is specifically cellular (typical while on bike Wi‑Fi). */
    fun isCellularPin(): Boolean {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = internetNetwork() ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * Call after [org.maplibre.android.MapLibre.getInstance]. Network callbacks must not
     * [org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient] before that — it
     * crashed 2.0.17/18 (`onAvailable` → MapLibre HTTP).
     */
    fun onMapLibreReady() {
        mapLibreReady = true
        applyMapLibreClientAsync()
    }

    /** OkHttp client for MapLibre style/tile fetches, pinned to the same uplink as [openUrl]. */
    fun mapLibreOkHttpClient(): OkHttpClient {
        mapLibreClient?.let { return it }
        return rebuildMapLibreClient()
    }

    private fun applyMapLibreClientAsync() {
        if (!mapLibreReady) return
        mainHandler.post {
            try {
                rebuildMapLibreClient()
            } catch (t: Throwable) {
                LogBus.log("[NET] MapLibre HTTP client apply failed: $t")
            }
        }
    }

    @Synchronized
    private fun rebuildMapLibreClient(): OkHttpClient {
        val net = internetNetwork()
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
        if (net != null) {
            builder.socketFactory(net.socketFactory)
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return try {
                        net.getAllByName(hostname).toList()
                    } catch (_: Exception) {
                        Dns.SYSTEM.lookup(hostname)
                    }
                }
            })
        }
        val client = builder.build()
        mapLibreClient = client
        if (mapLibreReady) {
            try {
                org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(client)
            } catch (t: Throwable) {
                LogBus.log("[NET] MapLibre setOkHttpClient failed: $t")
            }
        }
        return client
    }

    private val lastCallByHost = ConcurrentHashMap<String, Long>()

    /** Block just enough so this host is hit at most once per [minIntervalMs]. */
    fun throttle(host: String, minIntervalMs: Long) {
        if (minIntervalMs <= 0) return
        synchronized(lastCallByHost) {
            val now = System.currentTimeMillis()
            val last = lastCallByHost[host] ?: 0L
            val wait = last + minIntervalMs - now
            if (wait in 1..minIntervalMs) {
                try { Thread.sleep(wait) } catch (_: InterruptedException) {}
            }
            lastCallByHost[host] = System.currentTimeMillis()
        }
    }

    /** GET returning the body text, or throws [HttpError] / IOException. */
    fun getText(
        url: String,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 10_000,
        accept: String = "application/json",
        maxBytes: Int = MAX_RESPONSE_BYTES,
    ): String {
        ensureCellularUplink()
        val conn = open(url).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
        }
        return conn.readOrThrow(maxBytes)
    }

    /** POST an application/x-www-form-urlencoded body (used by Overpass). */
    fun postForm(
        url: String,
        formBody: String,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 25_000,
        maxBytes: Int = MAX_RESPONSE_BYTES,
    ): String {
        ensureCellularUplink()
        val conn = open(url).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }
        conn.outputStream.bufferedWriter().use { it.write(formBody) }
        return conn.readOrThrow(maxBytes)
    }

    /** POST a JSON body (OpenRouteService directions with avoids / alternatives). */
    fun postJson(
        url: String,
        jsonBody: String,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 20_000,
        accept: String = "application/json, application/geo+json",
        maxBytes: Int = MAX_RESPONSE_BYTES,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        ensureCellularUplink()
        val conn = open(url).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            for ((k, v) in extraHeaders) setRequestProperty(k, v)
        }
        conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(jsonBody) }
        return conn.readOrThrow(maxBytes)
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun HttpURLConnection.readOrThrow(maxBytes: Int): String {
        try {
            val code = responseCode
            if (code !in 200..299) {
                // Drain the error stream so the connection can be reused/closed cleanly.
                runCatching { errorStream?.readBoundedText(maxBytes) }
                val busy = code == 429 || code in 500..599
                val msg = when {
                    code == 429 -> "Map service is busy (rate limited) — try again shortly"
                    code in 500..599 -> "Map service temporarily unavailable (HTTP $code)"
                    else -> "Request failed (HTTP $code)"
                }
                throw HttpError(code, msg, busy)
            }
            return inputStream.readBoundedText(maxBytes)
        } finally {
            disconnect()
        }
    }

    private fun java.io.InputStream.readBoundedText(maxBytes: Int): String {
        // HttpURLConnection transparently handles gzip when we don't set Accept-Encoding.
        val out = StringBuilder()
        reader(Charsets.UTF_8).use { r ->
            val buf = CharArray(16 * 1024)
            var total = 0
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IOException("Response too large")
                out.append(buf, 0, n)
            }
        }
        return out.toString()
    }
}

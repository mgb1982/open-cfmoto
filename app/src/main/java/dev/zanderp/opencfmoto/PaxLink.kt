// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import dev.zanderp.opencfmoto.aa.AaInput
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * v2.3 · 👥 Passenger mode. Two RideScreen phones talk directly (Nearby Connections: Bluetooth, no
 * internet, no server). Pairing is done once: both screens show the same 4-digit code and both
 * confirm; a shared secret is exchanged and kept. Afterwards, while the rider is connected to the
 * bike, their phone is visible to the passengers it knows (and only to them: every connection must
 * prove the secret before any command is accepted).
 *
 * The passenger can send a destination (or one of the rider's saved places), move Android Auto
 * (D-pad, knob, OK, back, home), and control the music (play/pause, next, previous, volume).
 * Connections are kept non-disruptive so the rider's Wi‑Fi link to the dash is never touched.
 */
object PaxLink {
    private const val SERVICE_ID = "dev.zanderp.ridescreen.companion"
    private const val PREFS = "companion"
    private const val TAG = "[PAX]"
    private val STRATEGY = Strategy.P2P_POINT_TO_POINT

    data class Peer(val id: String, val name: String, val secret: String)
    data class Found(val endpointId: String, val id: String, val name: String, val pairing: Boolean)

    enum class Mode { IDLE, RIDER_VISIBLE, RIDER_PAIRING, PASSENGER_SEARCHING, PASSENGER_CONNECTED }

    @Volatile var mode = Mode.IDLE
        private set
    /** Rider side: who is connected and verified. Passenger side: the rider we're connected to. */
    @Volatile var peerName: String? = null
        private set
    /** Passenger side: the rider's saved places (slot → name) for one-tap buttons. */
    @Volatile var riderPlaces: List<Pair<Int, String>> = emptyList()
        private set
    val found = LinkedHashMap<String, Found>()

    /** UI hooks (set by [CompanionActivity] while it's open). */
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile var onConfirmCode: ((who: String, code: String, accept: () -> Unit, reject: () -> Unit) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var client: ConnectionsClient? = null
    private var endpoint: String? = null          // the one live connection
    private var pendingPair: Pair<String, String>? = null   // (peer id, name) while pairing
    private var verified = false
    private var nonce: String? = null
    private var connectingTo: Found? = null

    // ── identity and pairings

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun myId(ctx: Context): String = prefs(ctx).getString("id", null) ?: UUID.randomUUID().toString().take(12).also {
        prefs(ctx).edit().putString("id", it).apply()
    }

    fun myName(ctx: Context): String =
        CrashDetector.riderName(ctx).ifBlank { android.os.Build.MODEL ?: "RideScreen" }.take(24).replace("|", " ")

    private fun peers(ctx: Context, key: String): List<Peer> = try {
        val a = JSONArray(prefs(ctx).getString(key, "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Peer(o.getString("id"), o.optString("name"), o.getString("s")) } }
    } catch (_: Exception) { emptyList() }

    private fun savePeers(ctx: Context, key: String, list: List<Peer>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("s", it.secret)) }
        prefs(ctx).edit().putString(key, a.toString()).apply()
    }

    fun passengers(ctx: Context) = peers(ctx, "passengers")
    fun riders(ctx: Context) = peers(ctx, "riders")
    fun forgetPassenger(ctx: Context, id: String) = savePeers(ctx, "passengers", passengers(ctx).filter { it.id != id })
    fun forgetRider(ctx: Context, id: String) = savePeers(ctx, "riders", riders(ctx).filter { it.id != id })

    private fun endpointName(ctx: Context, pairing: Boolean) = "RS1|${myId(ctx)}|${myName(ctx)}" + if (pairing) "|P" else ""

    private fun parseName(endpointId: String, raw: String): Found? {
        val p = raw.split("|")
        if (p.size < 3 || p[0] != "RS1") return null
        return Found(endpointId, p[1], p[2], p.getOrNull(3) == "P")
    }

    private fun ensureClient(ctx: Context): ConnectionsClient {
        app = ctx.applicationContext
        return client ?: Nearby.getConnectionsClient(ctx.applicationContext).also { client = it }
    }

    private fun changed() = main.post { onChange?.invoke() }
    private fun log(s: String) = LogBus.log("$TAG $s")

    // ── rider side

    /** Called from [RideExtras.onSession]: visible to known passengers while connected to the bike. */
    fun onSession(ctx: Context, active: Boolean) {
        if (active && passengers(ctx).isNotEmpty() && mode == Mode.IDLE) startRider(ctx, pairing = false)
        else if (!active && (mode == Mode.RIDER_VISIBLE)) stopAll()
    }

    fun startRider(ctx: Context, pairing: Boolean) {
        val c = ensureClient(ctx)
        c.stopAdvertising(); c.stopDiscovery()
        mode = if (pairing) Mode.RIDER_PAIRING else Mode.RIDER_VISIBLE
        c.startAdvertising(endpointName(ctx, pairing), SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnSuccessListener { log("visible (${if (pairing) "pairing" else "known passengers"})") }
            .addOnFailureListener { e -> log("advertising failed: $e"); mode = Mode.IDLE; changed() }
        changed()
    }

    // ── passenger side

    fun startSearch(ctx: Context) {
        val c = ensureClient(ctx)
        c.stopAdvertising(); c.stopDiscovery()
        found.clear()
        mode = Mode.PASSENGER_SEARCHING
        c.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { e -> log("discovery failed: $e"); mode = Mode.IDLE; changed() }
        changed()
    }

    fun connect(ctx: Context, f: Found) {
        val c = ensureClient(ctx)
        connectingTo = f
        c.requestConnection(endpointName(ctx, f.pairing), f.endpointId, lifecycle,
            ConnectionOptions.Builder().setDisruptiveUpgrade(false).build())
            .addOnFailureListener { e -> log("connect failed: $e"); connectingTo = null; changed() }
    }

    fun stopAll() {
        client?.let { it.stopAdvertising(); it.stopDiscovery(); it.stopAllEndpoints() }
        endpoint = null; verified = false; pendingPair = null; peerName = null; connectingTo = null
        mode = Mode.IDLE
        changed()
    }

    // ── passenger → rider commands

    fun sendDestination(q: String) = send(JSONObject().put("t", "dest").put("q", q))
    fun sendPlace(slot: Int) = send(JSONObject().put("t", "place").put("slot", slot))
    fun sendKey(code: Int) = send(JSONObject().put("t", "key").put("k", code))
    fun sendScroll(delta: Int) = send(JSONObject().put("t", "scroll").put("d", delta))
    fun sendVolume(delta: Int) = send(JSONObject().put("t", "vol").put("d", delta))

    private fun send(o: JSONObject): Boolean {
        val c = client ?: return false
        val ep = endpoint ?: return false
        c.sendPayload(ep, Payload.fromBytes(o.toString().toByteArray()))
        return true
    }

    // ── Nearby callbacks

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val f = parseName(endpointId, info.endpointName) ?: return
            val known = app?.let { a -> riders(a).any { it.id == f.id } } == true
            if (!known && !f.pairing) return     // a stranger's bike: not shown
            found[endpointId] = f
            changed()
        }

        override fun onEndpointLost(endpointId: String) { found.remove(endpointId); changed() }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val ctx = app ?: return
            val c = client ?: return
            val asRider = mode == Mode.RIDER_PAIRING || mode == Mode.RIDER_VISIBLE
            val other = parseName(endpointId, info.endpointName)
            val pairing = if (asRider) mode == Mode.RIDER_PAIRING else connectingTo?.pairing == true
            val knownPeer = other != null && (if (asRider) passengers(ctx) else riders(ctx)).any { it.id == other.id }
            when {
                other == null -> c.rejectConnection(endpointId)
                pairing -> {
                    val ask = onConfirmCode
                    if (ask == null) { c.rejectConnection(endpointId); return }
                    main.post {
                        ask(other.name, info.authenticationDigits, {
                            pendingPair = other.id to other.name
                            c.acceptConnection(endpointId, payloads)
                        }, { c.rejectConnection(endpointId) })
                    }
                }
                knownPeer -> c.acceptConnection(endpointId, payloads)
                else -> { log("rejected an unknown phone"); c.rejectConnection(endpointId) }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val ctx = app ?: return
            if (!result.status.isSuccess) { log("connection refused (${result.status.statusCode})"); connectingTo = null; changed(); return }
            endpoint = endpointId
            verified = false
            val asRider = mode == Mode.RIDER_PAIRING || mode == Mode.RIDER_VISIBLE
            val pair = pendingPair
            if (asRider) {
                if (pair != null) {
                    // New passenger: create the shared secret and hand it over.
                    val secret = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                    savePeers(ctx, "passengers", passengers(ctx).filter { it.id != pair.first } + Peer(pair.first, pair.second, secret))
                    send(JSONObject().put("t", "pair").put("id", myId(ctx)).put("name", myName(ctx)).put("s", secret))
                    accepted(ctx, pair.second)
                    pendingPair = null
                    mode = Mode.RIDER_VISIBLE
                } else {
                    nonce = UUID.randomUUID().toString()
                    send(JSONObject().put("t", "challenge").put("n", nonce))
                }
            } else {
                mode = Mode.PASSENGER_CONNECTED
                peerName = connectingTo?.name
            }
            changed()
        }

        override fun onDisconnected(endpointId: String) {
            log("disconnected")
            if (endpoint == endpointId) { endpoint = null; verified = false; peerName = null }
            mode = when (mode) {
                Mode.PASSENGER_CONNECTED -> Mode.IDLE
                else -> mode
            }
            changed()
        }
    }

    /** Rider side: a passenger is in. Tell them our places for one-tap buttons. */
    private fun accepted(ctx: Context, name: String) {
        verified = true
        peerName = name
        val places = JSONArray()
        for (slot in 0 until SavedPlaces.COUNT) if (SavedPlaces.isSet(ctx, slot)) {
            places.put(JSONObject().put("slot", slot).put("name", SavedPlaces.name(ctx, slot).ifBlank { SavedPlaces.query(ctx, slot) }))
        }
        send(JSONObject().put("t", "welcome").put("name", myName(ctx)).put("places", places))
        log("passenger connected")
        main.post { Toast.makeText(ctx, ctx.getString(R.string.pax_rider_toast, name), Toast.LENGTH_LONG).show() }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val ctx = app ?: return
            val o = try { JSONObject(String(payload.asBytes() ?: return)) } catch (_: Exception) { return }
            when (o.optString("t")) {
                // passenger side
                "pair" -> {
                    savePeers(ctx, "riders", riders(ctx).filter { it.id != o.getString("id") } +
                        Peer(o.getString("id"), o.optString("name"), o.getString("s")))
                    peerName = o.optString("name"); pendingPair = null
                    log("paired with a rider")
                    changed()
                }
                "challenge" -> {
                    val rider = connectingTo?.id?.let { id -> riders(ctx).firstOrNull { it.id == id } } ?: return
                    send(JSONObject().put("t", "hello").put("id", myId(ctx)).put("mac", mac(rider.secret, o.optString("n"))))
                }
                "welcome" -> {
                    peerName = o.optString("name")
                    val a = o.optJSONArray("places") ?: JSONArray()
                    riderPlaces = (0 until a.length()).map { a.getJSONObject(it).let { p -> p.getInt("slot") to p.optString("name") } }
                    changed()
                }
                // rider side
                "hello" -> {
                    val p = passengers(ctx).firstOrNull { it.id == o.optString("id") }
                    val n = nonce
                    if (p != null && n != null && mac(p.secret, n) == o.optString("mac")) accepted(ctx, p.name)
                    else { log("passenger failed verification"); client?.disconnectFromEndpoint(endpointId) }
                    nonce = null
                }
                else -> if (verified) main.post { command(ctx, o) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    /** Rider side, verified passenger only. */
    private fun command(ctx: Context, o: JSONObject) {
        when (o.optString("t")) {
            "dest" -> o.optString("q").takeIf { it.isNotBlank() }?.let { q ->
                log("destination from the passenger")
                NavLauncher.navigate(ctx, q) { LogBus.log(it) }
                Toast.makeText(ctx, ctx.getString(R.string.pax_dest_toast, peerName ?: "", q), Toast.LENGTH_LONG).show()
            }
            "place" -> o.optInt("slot", -1).takeIf { it in 0 until SavedPlaces.COUNT && SavedPlaces.isSet(ctx, it) }?.let { slot ->
                NavLauncher.navigate(ctx, SavedPlaces.query(ctx, slot)) { LogBus.log(it) }
            }
            "key" -> {
                val k = o.optInt("k")
                val allowed = setOf(AaInput.KEY_UP, AaInput.KEY_DOWN, AaInput.KEY_LEFT, AaInput.KEY_RIGHT, AaInput.KEY_ENTER,
                    AaInput.KEY_BACK, AaInput.KEY_HOME, AaInput.KEY_PLAY_PAUSE, AaInput.KEY_NEXT, AaInput.KEY_PREVIOUS)
                if (k in allowed) (MapInputBridge.keySink ?: AaVideoBridge.keySink)?.invoke(k)
            }
            "scroll" -> o.optInt("d").coerceIn(-1, 1).takeIf { it != 0 }?.let { d ->
                (MapInputBridge.scrollSink ?: AaVideoBridge.scrollSink)?.invoke(d)
            }
            "vol" -> (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, if (o.optInt("d") > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        }
    }

    private fun mac(secret: String, msg: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return m.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

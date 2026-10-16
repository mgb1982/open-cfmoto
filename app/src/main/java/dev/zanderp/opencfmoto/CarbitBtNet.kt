// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v2.3 · Carbit "phone hotspot" dashes (QR `action=128`, e.g. Zontes 125M): the dash is the Wi‑Fi
 * client and the phone must host the network. Carbit Ride does it like this (from its APK,
 * net.easyconn.carman SdpBluetoothUtil, 16 Oct 2026):
 *  1. Classic Bluetooth RFCOMM to the paired dash, service UUID [SERVICE].
 *  2. CmdBaseHead frames (same 16-byte header as the PXC control socket, see [PxcFrame]):
 *     0x80000 EBT_CONN_TYPE_P2C → 0x80030 (car network info) → 0x80020 EBT_P2C_CLIENT_INFO
 *     (reply JSON {status, phoneApFrequency}).
 *  3. The phone starts an Android *local-only hotspot* (random SSID/password; it only works with
 *     the normal hotspot OFF, which is what the rider saw) …
 *  4. … and sends 0x80040 EBT_P2C_NOTIFY_AP_INFO {"ssid","pwd","auth","ip"}. The dash joins and
 *     announces itself over mDNS; from there the usual EasyConn link takes over.
 * Every step is logged with the dash's replies so a failing step is visible in the rider's log.
 */
object CarbitBtNet {
    private val SERVICE: UUID = UUID.fromString("9f03b326-5d75-46f1-9a39-b71f144d1d97")
    private const val CMD_CONN_TYPE_P2C = 0x80000
    private const val CMD_CAR_NET_INFO = 0x80030
    private const val CMD_CLIENT_INFO = 0x80020
    private const val CMD_NOTIFY_AP_INFO = 0x80040

    @Volatile private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile var hotspotSsid: String? = null
        private set

    /** Close our hotspot (session ended / user stopped). */
    fun release() {
        try { reservation?.close() } catch (_: Exception) {}
        if (reservation != null) LogBus.log("[BTNET] local hotspot closed")
        reservation = null; hotspotSsid = null
    }

    @SuppressLint("MissingPermission")
    fun findDash(ctx: Context, qrMac: String?): BluetoothDevice? {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: BluetoothAdapter.getDefaultAdapter() ?: return null
        val bonded = try { adapter.bondedDevices.orEmpty() } catch (_: Exception) { emptySet() }
        // The QR's "bm" can differ in the first byte from the dash's real address (DD:… vs DC:…):
        // match on the last five bytes, or on the name ending with the last three (ZT2428DC).
        val tail = qrMac?.uppercase()?.replace(":", "")?.takeLast(10)
        return bonded.firstOrNull { tail != null && it.address.uppercase().replace(":", "").endsWith(tail) }
            ?: bonded.firstOrNull { d -> tail != null && (d.name ?: "").uppercase().endsWith(tail.takeLast(6)) }
            ?: bonded.firstOrNull { d -> DashClock.nameLooksLikeDash(d.name) }
    }

    /** Blocking (call off the main thread). True when the dash accepted our hotspot details. */
    @SuppressLint("MissingPermission")
    fun run(ctx: Context, qrMac: String?): Boolean {
        val log = { s: String -> LogBus.log("[BTNET] $s") }
        val dev = findDash(ctx, qrMac)
        if (dev == null) { log("no paired dash found (pair the bike in Bluetooth settings first)"); return false }
        log("dash ${dev.name} ${dev.address}")
        var sock: BluetoothSocket? = null
        try {
            try { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } catch (_: Exception) {}
            sock = try { dev.createRfcommSocketToServiceRecord(SERVICE).also { it.connect() } }
                catch (e: Exception) { log("secure RFCOMM failed ($e), trying insecure"); dev.createInsecureRfcommSocketToServiceRecord(SERVICE).also { it.connect() } }
            val s = sock ?: return false
            log("RFCOMM connected")
            val out = s.outputStream; val inp = s.inputStream
            // One reader for the whole session; replies are queued in arrival order.
            val replies = java.util.concurrent.LinkedBlockingQueue<Pair<Int, String>>()
            Thread({ while (true) { val f = readFrame(inp) ?: break; replies.put(f) } }, "carbit-bt-rx").apply { isDaemon = true }.start()
            fun ask(cmd: Int, body: ByteArray = ByteArray(0)): Pair<Int, String>? {
                PxcFrame(cmd, body).write(out)
                val r = replies.poll(6, TimeUnit.SECONDS)
                log("→ 0x${Integer.toHexString(cmd)}${if (body.isNotEmpty()) " " + String(body) else ""} ← " +
                    (r?.let { "0x${Integer.toHexString(it.first)} ${it.second.take(300)}" } ?: "no reply"))
                return r
            }
            ask(CMD_CONN_TYPE_P2C)
            ask(CMD_CAR_NET_INFO)
            val info = ask(CMD_CLIENT_INFO)
            val status = info?.second?.let { runCatching { JSONObject(it).optInt("status", -100) }.getOrNull() }
            log("client info status=$status")

            // Our own temporary hotspot (needs the normal hotspot off and Location on).
            release()
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return false
            val latch = CountDownLatch(1)
            var failReason = 0
            try {
                wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) { reservation = r; latch.countDown() }
                    override fun onFailed(reason: Int) { failReason = reason; latch.countDown() }
                    override fun onStopped() { log("local hotspot stopped by the system"); reservation = null }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) { log("local hotspot not allowed: $e"); return false }
            latch.await(15, TimeUnit.SECONDS)
            val res = reservation
            if (res == null) {
                log("local hotspot failed (reason=$failReason — 1=no channel, 2=generic, 3=incompatible mode: turn the normal hotspot off, 4=tethering disallowed)")
                return false
            }
            val (ssid, pwd) = credentials(res)
            if (ssid.isNullOrBlank() || pwd.isNullOrBlank()) { log("local hotspot has no SSID/password?"); return false }
            hotspotSsid = ssid
            // Our address on the new hotspot (the dash connects back to it).
            var ip: String? = null
            for (i in 0 until 20) {
                ip = PhoneHotspotScan.tetheringSubnets().firstOrNull { !it.interfaceName.startsWith("rmnet") }?.localAddress?.hostAddress
                if (ip != null) break
                Thread.sleep(500)
            }
            log("local hotspot up: ssid='$ssid' ip=${ip ?: "?"}")
            val body = JSONObject().put("ssid", ssid).put("pwd", pwd).put("auth", "WPA2").put("ip", ip ?: "").toString().toByteArray()
            ask(CMD_NOTIFY_AP_INFO, body)
            return true
        } catch (e: Exception) {
            log("failed: $e")
            return false
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun credentials(r: WifiManager.LocalOnlyHotspotReservation): Pair<String?, String?> =
        if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString()?.trim('"') else c.ssid
            ssid to c.passphrase
        } else {
            val c = r.wifiConfiguration
            c?.SSID?.trim('"') to c?.preSharedKey?.trim('"')
        }

    /** One CmdBaseHead frame: (cmd, payload as text), or null when the stream ends. */
    private fun readFrame(inp: InputStream): Pair<Int, String>? = try {
        val head = ByteArray(16); var n = 0
        while (n < 16) { val k = inp.read(head, n, 16 - n); if (k < 0) throw java.io.EOFException(); n += k }
        val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.getInt(0); val total = bb.getInt(4)
        val len = (total - 16).coerceIn(0, 64 * 1024)
        val body = ByteArray(len); n = 0
        while (n < len) { val k = inp.read(body, n, len - n); if (k < 0) break; n += k }
        cmd to String(body, 0, n)
    } catch (_: Exception) { null }
}

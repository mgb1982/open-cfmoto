// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * 🆘 Crash detection (v2.2, opt-in).
 *
 * Armed only around a ride: while projecting to the bike and for [AFTER_SESSION_MS] after the session
 * ends (a crash usually kills the engine → the dash → the session, so ending must not disarm us), and
 * only if the phone saw ≥ [ARM_SPEED_KMH] in the last [ARM_WINDOW_MS]. A crash is a hard impact
 * (≥ [IMPACT_G]) followed, within [CONFIRM_WINDOW_MS], by the phone lying still for [STILL_MS] with the
 * GPS speed near zero. Then a [COUNTDOWN_S] alarm (phone + watch) the rider can cancel; if nobody does,
 * an SMS with a map link goes to up to 3 contacts, and a second one with a fresh position after
 * [FOLLOW_UP_MS]. Never calls emergency services by itself (a false positive there is serious).
 * SMS needs the GitHub build (Play forbids SEND_SMS for non-messaging apps); the Play build alarms and
 * offers to call the first contact instead.
 */
object CrashDetector : SensorEventListener {
    private const val PREFS = "crash_detector"
    const val MAX_CONTACTS = 3
    const val COUNTDOWN_S = 60
    private const val FOLLOW_UP_MS = 5 * 60_000L
    private const val AFTER_SESSION_MS = 3 * 60_000L
    private const val ARM_SPEED_KMH = 15f
    private const val ARM_WINDOW_MS = 30_000L
    private const val IMPACT_G = 4.0f
    private const val CONFIRM_WINDOW_MS = 30_000L
    private const val STILL_MS = 10_000L
    private const val STILL_TOLERANCE = 0.6f          // m/s² deviation from 1 g
    private const val STOPPED_KMH = 5f
    private const val CHANNEL = "crash_alert"
    const val NOTIF_ID = 4501

    data class Contact(val name: String, val number: String)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var sensorOn = false
    private var sessionActive = false
    private var disarmAt = 0L
    private var lastFastAt = 0L
    private var impactAt = 0L
    private var stillSince = 0L
    @Volatile var alarmActive = false
        private set
    @Volatile var alarmTest = false
        private set
    private var alarmEndsAt = 0L
    private var ringtone: Ringtone? = null

    // ── Settings

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", false)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("on", on).apply()
    fun riderName(ctx: Context): String = prefs(ctx).getString("name", "").orEmpty()
    fun setRiderName(ctx: Context, name: String) = prefs(ctx).edit().putString("name", name.trim()).apply()

    fun contacts(ctx: Context): List<Contact> = try {
        val a = JSONArray(prefs(ctx).getString("contacts", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Contact(o.optString("n"), o.optString("p")) } }
    } catch (_: Exception) { emptyList() }

    fun setContacts(ctx: Context, list: List<Contact>) {
        val a = JSONArray()
        list.take(MAX_CONTACTS).forEach { a.put(JSONObject().put("n", it.name).put("p", it.number)) }
        prefs(ctx).edit().putString("contacts", a.toString()).apply()
    }

    val smsSupported: Boolean get() = BuildConfig.STORE == "github"

    fun canSms(ctx: Context) = smsSupported &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    // ── Arming (called from RideExtras.onSession)

    fun onSession(ctx: Context, active: Boolean) {
        app = ctx.applicationContext
        main.post {
            sessionActive = active
            if (active) {
                disarmAt = 0L
                if (enabled(app)) startSensor()
            } else if (sensorOn) {
                disarmAt = SystemClock.elapsedRealtime() + AFTER_SESSION_MS
                main.postDelayed(::maybeDisarm, AFTER_SESSION_MS + 1000)
            }
        }
    }

    private fun maybeDisarm() {
        if (sessionActive || alarmActive) return
        if (disarmAt != 0L && SystemClock.elapsedRealtime() >= disarmAt) stopSensor()
    }

    private fun startSensor() {
        if (sensorOn) return
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorOn = sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME, main)
        impactAt = 0L; stillSince = 0L
        LogBus.log("[CRASH] detector armed (${contacts(app).size} contact(s), sms=${canSms(app)})")
    }

    private fun stopSensor() {
        if (!sensorOn) return
        (app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(this)
        sensorOn = false
        LogBus.log("[CRASH] detector disarmed")
    }

    // ── Detection

    private fun speedKmh(): Float? {
        val l = TripRecorder.lastSeen ?: return null
        val age = System.currentTimeMillis() - l.time
        if (age > 15_000L || !l.hasSpeed()) return null
        return l.speed * 3.6f
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (alarmActive) return
        val now = SystemClock.elapsedRealtime()
        val v = speedKmh()
        if (v != null && v >= ARM_SPEED_KMH) lastFastAt = now
        val mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])

        if (impactAt == 0L) {
            if (mag >= IMPACT_G * SensorManager.GRAVITY_EARTH && now - lastFastAt <= ARM_WINDOW_MS) {
                impactAt = now
                stillSince = 0L
                LogBus.log("[CRASH] impact ${"%.1f".format(mag / SensorManager.GRAVITY_EARTH)} g at ${v?.toInt() ?: "?"} km/h — watching for stillness")
            }
            return
        }
        if (now - impactAt > CONFIRM_WINDOW_MS) {
            LogBus.log("[CRASH] impact not confirmed (rider moving again) — ignored")
            impactAt = 0L
            return
        }
        val still = kotlin.math.abs(mag - SensorManager.GRAVITY_EARTH) < STILL_TOLERANCE
        if (!still) { stillSince = 0L; return }
        if (stillSince == 0L) stillSince = now
        val stopped = (v ?: 0f) < STOPPED_KMH
        if (now - stillSince >= STILL_MS && stopped) {
            impactAt = 0L
            LogBus.log("[CRASH] impact + ${STILL_MS / 1000}s still + stopped → alarm")
            startAlarm(app, test = false)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Alarm

    /** Also used by "Simulate a crash" in settings ([test] = nothing is sent, only shown). */
    fun startAlarm(ctx: Context, test: Boolean) {
        app = ctx.applicationContext
        main.post {
            if (alarmActive) return@post
            alarmActive = true
            alarmTest = test
            alarmEndsAt = SystemClock.elapsedRealtime() + COUNTDOWN_S * 1000L
            playAlarm()
            showAlarmUi()
            WearBridge.sendToWatches(app, WearBridge.PATH_CRASH, "start:$COUNTDOWN_S")
            main.postDelayed(countdownEnd, COUNTDOWN_S * 1000L)
        }
    }

    fun secondsLeft(): Int =
        if (!alarmActive) 0 else ((alarmEndsAt - SystemClock.elapsedRealtime()) / 1000L).toInt().coerceAtLeast(0)

    /** "I'm OK" — from the alert screen, the notification or the watch. */
    fun cancelAlarm(ctx: Context, from: String) {
        main.post {
            if (!alarmActive) return@post
            alarmActive = false
            main.removeCallbacks(countdownEnd)
            stopAlarmSound()
            dismissNotification(ctx)
            WearBridge.sendToWatches(ctx.applicationContext, WearBridge.PATH_CRASH, "stop")
            LogBus.log("[CRASH] alarm cancelled ($from)")
            CrashAlertActivity.close()
        }
    }

    /** "Send now" from the alert screen. */
    fun sendNow(ctx: Context) {
        main.post {
            if (!alarmActive) return@post
            main.removeCallbacks(countdownEnd)
            countdownEnd.run()
        }
    }

    private val countdownEnd = Runnable {
        if (!alarmActive) return@Runnable
        alarmActive = false
        stopAlarmSound()
        WearBridge.sendToWatches(app, WearBridge.PATH_CRASH, "sent")
        val test = alarmTest
        val sent = sendAlerts(app, followUp = false, test = test)
        CrashAlertActivity.showSent(sent, test)
        if (!test && sent.isNotEmpty()) main.postDelayed({ sendAlerts(app, followUp = true, test = false) }, FOLLOW_UP_MS)
        dismissNotification(app)
        if (!test) postSentNotification(app, sent)
    }

    private fun playAlarm() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(app, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        } catch (e: Exception) {
            LogBus.log("[CRASH] alarm sound failed: $e")
        }
        try {
            vibrator(app)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
        } catch (_: Exception) {}
    }

    private fun stopAlarmSound() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try { vibrator(app)?.cancel() } catch (_: Exception) {}
    }

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    private fun ensureChannel(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.crash_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null) // the alarm plays on its own, on the alarm stream
                },
            )
        }
        return nm
    }

    /** Full-screen alert (works over the lock screen) + "I'm OK" action. */
    private fun showAlarmUi() {
        val nm = ensureChannel(app)
        val open = PendingIntent.getActivity(
            app, 1, Intent(app, CrashAlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ok = PendingIntent.getBroadcast(
            app, 2, Intent(app, CrashActionReceiver::class.java).setAction(CrashActionReceiver.ACTION_OK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle(app.getString(R.string.crash_alert_title))
            .setContentText(app.getString(R.string.crash_alert_text, COUNTDOWN_S))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(0, app.getString(R.string.crash_im_ok), ok)
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (e: Exception) { LogBus.log("[CRASH] notify failed: $e") }
        try { app.startActivity(Intent(app, CrashAlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    private fun dismissNotification(ctx: Context) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (_: Exception) {}
    }

    private fun postSentNotification(ctx: Context, sentTo: List<String>) {
        try {
            val nm = ensureChannel(ctx)
            val text = if (sentTo.isEmpty()) ctx.getString(R.string.crash_sent_none)
                else ctx.getString(R.string.crash_sent_to, sentTo.joinToString(", "))
            nm.notify(NOTIF_ID + 1, NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_power)
                .setContentTitle(ctx.getString(R.string.crash_sent_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .build())
        } catch (_: Exception) {}
    }

    // ── Messages

    fun messageText(ctx: Context, here: Location?, followUp: Boolean, test: Boolean): String {
        val name = riderName(ctx).ifBlank { ctx.getString(R.string.crash_sms_someone) }
        val where = if (here != null) {
            val acc = if (here.hasAccuracy()) " (±${here.accuracy.toInt()} m)" else ""
            "https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, here.latitude, here.longitude) + acc
        } else ctx.getString(R.string.crash_sms_no_location)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val body = ctx.getString(
            if (followUp) R.string.crash_sms_followup else R.string.crash_sms_body, name, COUNTDOWN_S, where, time,
        )
        return if (test) ctx.getString(R.string.crash_sms_test_prefix) + " " + body else body
    }

    private fun currentLocation(): Location? = RideExtras.lastLocation(app, 10 * 60_000L)

    /** @return names of the contacts the SMS went to (empty if none / not possible). */
    fun sendAlerts(ctx: Context, followUp: Boolean, test: Boolean, forceSend: Boolean = false): List<String> {
        val list = contacts(ctx)
        val here = currentLocation()
        val text = messageText(ctx, here, followUp, test)
        if (test && !forceSend) {
            LogBus.log("[CRASH] simulation — would send to ${list.size} contact(s): $text")
            return list.map { it.name.ifBlank { it.number } }
        }
        if (!canSms(ctx)) {
            LogBus.log("[CRASH] SMS not possible (store=${BuildConfig.STORE}, permission=${ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED})")
            return emptyList()
        }
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        val sent = ArrayList<String>()
        for (c in list) {
            try {
                val parts = sms.divideMessage(text)
                sms.sendMultipartTextMessage(c.number, null, parts, null, null)
                sent += c.name.ifBlank { c.number }
            } catch (e: Exception) {
                LogBus.log("[CRASH] SMS to ${c.name} failed: $e")
            }
        }
        LogBus.log("[CRASH] ${if (followUp) "follow-up " else ""}SMS sent to ${sent.size}/${list.size}")
        return sent
    }
}

/** Notification action "I'm OK". */
class CrashActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_OK) CrashDetector.cancelAlarm(context, "notification")
    }

    companion object {
        const val ACTION_OK = "dev.zanderp.opencfmoto.CRASH_OK"
    }
}

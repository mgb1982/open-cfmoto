// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The selected Garage bike's photo on the watch (compass background, idle screens). Sent as a
 * Data Layer item, so a watch that's away gets it when it reconnects; only re-sent when it changes.
 */
object WearBikePhoto {
    const val PATH = "/ocm/bikephoto"
    private const val SIZE = 450

    fun push(ctx: Context) {
        val app = ctx.applicationContext
        Thread({
            try {
                val path = BikeMemory.selected(app)?.photoPath
                val sig = path?.let { "$it:${File(it).lastModified()}" } ?: ""
                val prefs = app.getSharedPreferences("wear_bike_photo", Context.MODE_PRIVATE)
                if (prefs.getString("sent", null) == sig) return@Thread
                val req = PutDataMapRequest.create(PATH)
                req.dataMap.putLong("t", System.currentTimeMillis())
                val bytes = path?.let { encode(it) }
                if (bytes != null) req.dataMap.putAsset("photo", Asset.createFromBytes(bytes))
                else req.dataMap.putBoolean("none", true)
                Wearable.getDataClient(app).putDataItem(req.asPutDataRequest())
                prefs.edit().putString("sent", sig).apply()
                LogBus.log("[WEAR] bike photo ${if (bytes != null) "sent (${bytes.size / 1024} KB)" else "cleared"}")
            } catch (e: Exception) {
                LogBus.log("[WEAR] bike photo not sent: ${e.message}")
            }
        }, "wear-bike-photo").start()
    }

    /** Centre square, SIZE px, JPEG: small enough for the Data Layer, sharp on a round watch. */
    private fun encode(path: String): ByteArray? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, b)
        if (b.outWidth <= 0) return null
        var s = 1
        while (minOf(b.outWidth, b.outHeight) / (s * 2) >= SIZE) s *= 2
        val src = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val side = minOf(src.width, src.height)
        val crop = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = Bitmap.createScaledBitmap(crop, SIZE, SIZE, true)
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        if (crop !== src) crop.recycle()
        src.recycle()
        out.recycle()
        return bos.toByteArray()
    }
}

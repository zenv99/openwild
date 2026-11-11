package org.openwild.app.pack

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import org.json.JSONObject
import org.openwild.app.core.RegionInfo

/** Offline region detection: last known coarse location + bundled region boxes. No Google Play Services. */
object Location {

    /** (lon, lat) or null. Caller must hold ACCESS_COARSE_LOCATION. Location never leaves the phone. */
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): Pair<Double, Double>? = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.getProviders(true)
            .mapNotNull { lm.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
            ?.let { it.longitude to it.latitude }
    } catch (e: SecurityException) {
        null
    }

    fun bundledRegions(context: Context): List<RegionInfo> = try {
        val root = JSONObject(context.assets.open("regions.json").bufferedReader().readText())
        val arr = root.getJSONArray("regions")
        (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val boxes = r.getJSONArray("bbox")
            RegionInfo(
                id = r.getString("id"),
                name = r.getString("name"),
                boxes = (0 until boxes.length()).map { j ->
                    val b = boxes.getJSONArray(j)
                    DoubleArray(4) { k -> b.getDouble(k) }
                },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

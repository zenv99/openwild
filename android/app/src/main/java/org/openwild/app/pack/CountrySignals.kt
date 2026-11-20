package org.openwild.app.pack

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.openwild.app.core.CountryHint
import org.openwild.app.core.RegionInfo
import org.openwild.app.core.RegionLocator
import java.util.Locale
import java.util.TimeZone

/**
 * Reads the phone's own hints for a country pack. Nothing here is sent anywhere.
 * Coarse location is used only when the person has already allowed it.
 */
object CountrySignals {
    @Suppress("DEPRECATION")
    fun read(context: Context, regions: List<RegionInfo>): CountryHint.Signals {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sim = runCatching { tm?.simCountryIso }.getOrNull()
        val network = runCatching { tm?.networkCountryIso }.getOrNull()
        return CountryHint.Signals(
            localeCountry = Locale.getDefault().country,
            simCountry = sim,
            networkCountry = network,
            locationCountryId = coarseCountry(context, regions),
            timezoneId = TimeZone.getDefault().id,
        )
    }

    private fun coarseCountry(context: Context, regions: List<RegionInfo>): String? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val fix = Location.lastKnown(context) ?: return null
        return RegionLocator.suggest(regions, fix.first, fix.second)?.first?.id
    }
}

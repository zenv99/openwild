package org.openwild.app.pack

import android.content.Context
import android.net.ConnectivityManager

/** Local check only: is the active network metered? Nothing is sent. */
object Networks {
    fun isMetered(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.isActiveNetworkMetered
    } catch (_: SecurityException) {
        true
    }
}

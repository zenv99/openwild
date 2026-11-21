package org.openwild.app.ui

import android.content.Context
import android.util.Base64
import org.openwild.app.BuildConfig
import org.openwild.app.OpenWildApp
import org.openwild.app.core.ProfileSyncGate
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads the on-phone queue. The default build leaves this off, so [sync] returns
 * before it opens a connection. The host comes from the build, not from a literal in this file.
 */
object ProfileSync {
    fun enabled(): Boolean = ProfileSyncGate.allowed(BuildConfig.PROFILES_ONLINE, BuildConfig.PROFILES_API_URL)

    fun sync(context: Context): String {
        if (!enabled()) return "disabled"
        val base = BuildConfig.PROFILES_API_URL.trimEnd('/')
        val store = (context.applicationContext as OpenWildApp).credits
        val installId = store.installId()
        for (item in store.pending()) {
            val avatar = store.avatarJpeg(item.id)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            val code = post("$base/v1/submissions", item.toSyncJson(installId, avatar), installId)
            if (code in 200..299) store.markSynced(item.id) else return "error"
        }
        return "synced"
    }

    private fun post(url: String, body: String, installId: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-Install-Id", installId)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}

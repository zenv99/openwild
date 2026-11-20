package org.openwild.app.core

import java.util.Locale

/** When a region download is large enough to warn that Wi-Fi is the better way to get it. */
object DownloadCaution {
    /**
     * A single country pack is often under this. A country plus World, or a full photo set,
     * is often over it. At or above this size the app asks before the download starts.
     */
    const val WIFI_WARN_BYTES = 50L * 1_000_000

    fun needsWarning(bytes: Long): Boolean = bytes >= WIFI_WARN_BYTES

    /** Decimal megabytes. One decimal under 10 MB, otherwise a whole number. */
    fun format(bytes: Long): String {
        if (bytes <= 0) return "—"
        val mb = bytes / 1_000_000.0
        return if (mb < 10) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.0f MB", mb)
    }
}

package org.openwild.app.core

data class PackVersion(val regionId: String, val version: Int, val bytes: Long)

/**
 * Region-pack updates are offered, never started on their own.
 * A large download on a metered network has to be agreed to explicitly.
 */
object PackUpdates {
    /** About the size of a photo pack. Smaller fixes can be confirmed with a plain Download. */
    const val LARGE_BYTES = 20L * 1000 * 1000

    fun pending(installed: Map<String, Int>, remote: List<PackVersion>): List<PackVersion> =
        remote.filter { r -> installed[r.regionId]?.let { it < r.version } == true }

    /** True when the confirm button must say that mobile data will be used. */
    fun confirmMobileData(bytes: Long, metered: Boolean): Boolean =
        metered && (bytes <= 0L || bytes >= LARGE_BYTES)
}

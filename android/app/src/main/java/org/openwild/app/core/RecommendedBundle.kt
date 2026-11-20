package org.openwild.app.core

/**
 * The recommended download: one country pack plus World.
 * The country is what Explore shows. World stays alongside it.
 */
object RecommendedBundle {
    const val WORLD_ID = CountryHint.WORLD_ID

    data class Side(
        val bytes: Long = 0,
        val onPhone: Boolean = false,
        val light: Boolean = false,
        /** A full pack can be fetched (remote index or a bundled file). */
        val offered: Boolean = false,
    )

    data class Plan(
        val countryId: String,
        val countryName: String,
        val country: Side,
        val world: Side,
    ) {
        /** Full country photo set is already stored. A lighter download still needs the full one. */
        val fullCountry: Boolean get() = country.onPhone && !country.light
        val fullWorld: Boolean get() = world.onPhone && !world.light
        val fetchCountry: Boolean get() = !fullCountry && country.offered
        val fetchWorld: Boolean get() = !fullWorld && world.offered
        val canStart: Boolean get() = fetchCountry || fetchWorld
        val complete: Boolean get() = fullCountry && fullWorld
        val totalBytes: Long get() = pos(country.bytes) + pos(world.bytes)
        /** Bytes this tap will transfer. Already-full packs count as zero. */
        val transferBytes: Long
            get() = (if (fetchCountry) pos(country.bytes) else 0L) + (if (fetchWorld) pos(world.bytes) else 0L)
    }

    enum class SizeKind { TOTAL, AT_LEAST, UNKNOWN }

    fun sizeOf(countryBytes: Long, worldBytes: Long): Pair<SizeKind, Long> {
        val c = pos(countryBytes)
        val w = pos(worldBytes)
        return when {
            c > 0 && w > 0 -> SizeKind.TOTAL to (c + w)
            c > 0 || w > 0 -> SizeKind.AT_LEAST to (c + w)
            else -> SizeKind.UNKNOWN to 0L
        }
    }

    /**
     * Progress of a recommended download already running, or null.
     * One file at a time reports that file. Both at once average the two.
     */
    fun activeProgress(countryId: String, downloading: Map<String, Float>): Float? {
        val c = downloading[countryId]
        val w = downloading[WORLD_ID]
        return when {
            c != null && w != null -> (c + w) / 2f
            c != null -> c
            w != null -> w
            else -> null
        }
    }

    private fun pos(n: Long) = if (n > 0) n else 0L
}

package org.openwild.app.core

/**
 * Which country pack to offer with World.
 *
 * Signals are tried in order. The first one that names a visible country pack wins.
 * A hidden finer pack is never a download on its own; callers pass the parent country
 * when a coarse fix landed inside one. World is not a country.
 */
object CountryHint {
    const val WORLD_ID = "WORLD"

    data class Signals(
        val localeCountry: String? = null,
        val simCountry: String? = null,
        val networkCountry: String? = null,
        /** Country pack id from a coarse fix. Null when location is not permitted or unknown. */
        val locationCountryId: String? = null,
        val timezoneId: String? = null,
    )

    /**
     * Those three zones cover the country whose pack id is ID. A phone there often has a
     * language setting with no country, so the zone is what names the pack.
     */
    private val TIMEZONE_COUNTRY = mapOf(
        "Asia/Jakarta" to "ID",
        "Asia/Makassar" to "ID",
        "Asia/Jayapura" to "ID",
    )

    /** Location, then the cell network, the SIM, an Indonesian time zone, then the locale. */
    fun orderedIds(signals: Signals): List<String> {
        val out = LinkedHashSet<String>()
        fun add(id: String?) {
            if (!id.isNullOrBlank() && id != WORLD_ID) out += id
        }
        add(normalizeRegionId(signals.locationCountryId))
        add(normalizeCode(signals.networkCountry))
        add(normalizeCode(signals.simCountry))
        add(TIMEZONE_COUNTRY[signals.timezoneId])
        add(normalizeCode(signals.localeCountry))
        return out.toList()
    }

    fun match(regions: List<RegionInfo>, signals: Signals): RegionInfo? {
        val visible = regions.filter { !it.hidden && it.id != WORLD_ID }.associateBy { it.id }
        return orderedIds(signals).firstNotNullOfOrNull { visible[it] }
    }

    /** Countries a person can download. Hidden finer packs and World stay out of the list. */
    fun countries(regions: List<RegionInfo>): List<RegionInfo> =
        regions.filter { !it.hidden && it.id != WORLD_ID }.sortedBy { it.name.lowercase() }

    fun world(regions: List<RegionInfo>): RegionInfo? =
        regions.firstOrNull { it.id == WORLD_ID && !it.hidden }

    /** ISO country from the locale, SIM, or network. Two letters only. */
    fun normalizeCode(raw: String?): String? {
        val s = raw?.trim()?.uppercase() ?: return null
        if (s.length != 2 || s.any { !it.isLetter() }) return null
        return s
    }

    /** A pack id already resolved by the region list. World is dropped. */
    fun normalizeRegionId(raw: String?): String? {
        val s = raw?.trim()?.uppercase() ?: return null
        if (s.isEmpty() || s == WORLD_ID) return null
        return s
    }
}

package org.openwild.app.core

/** [minLon, minLat, maxLon, maxLat] boxes from regions.json. Same rule as the pipeline's regions.locate(). */
data class RegionInfo(
    val id: String,
    val name: String,
    val boxes: List<DoubleArray>,
    val continent: String = "",
    /** Coarser pack this region sits inside, when it is a finer area such as Bali or a US state. */
    val parentId: String? = null,
    /** Kept for phones that already downloaded it. Not offered as a new download. */
    val hidden: Boolean = false,
) {
    fun contains(lon: Double, lat: Double): Boolean =
        boxes.any { b -> lon >= b[0] && lon <= b[2] && lat >= b[1] && lat <= b[3] }

    val area: Double get() = boxes.sumOf { (it[2] - it[0]) * (it[3] - it[1]) }
}

object RegionLocator {
    /** Smallest region whose boxes contain the point, or null. Finer packs such as Bali still win here. */
    fun locate(regions: List<RegionInfo>, lon: Double, lat: Double): RegionInfo? =
        regions.filter { it.contains(lon, lat) }.minByOrNull { it.area }

    /** Walk parent links to the country (or world) pack. */
    fun countryOf(regions: List<RegionInfo>, region: RegionInfo): RegionInfo {
        val byId = regions.associateBy { it.id }
        val seen = HashSet<String>()
        var cur = region
        while (cur.parentId != null && seen.add(cur.id)) {
            cur = byId[cur.parentId] ?: return cur
        }
        return cur
    }

    /**
     * Country download for this point. A hidden finer pack (Bali, Hawaii) resolves to its parent
     * even when the parent box does not cover that point. The second value is always null.
     */
    fun suggest(regions: List<RegionInfo>, lon: Double, lat: Double): Pair<RegionInfo, RegionInfo?>? {
        val hits = regions.filter { it.id != "WORLD" && it.contains(lon, lat) }
        val visible = hits.filter { !it.hidden }
        if (visible.isNotEmpty()) return visible.minBy { it.area } to null
        if (hits.isNotEmpty()) {
            val country = countryOf(regions, hits.minBy { it.area })
            if (!country.hidden) return country to null
        }
        val world = regions.firstOrNull { it.id == "WORLD" && !it.hidden && it.contains(lon, lat) }
        return world?.let { it to null }
    }
}

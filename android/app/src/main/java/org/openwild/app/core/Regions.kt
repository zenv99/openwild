package org.openwild.app.core

/** [minLon, minLat, maxLon, maxLat] boxes from regions.json. Same rule as the pipeline's regions.locate(). */
data class RegionInfo(
    val id: String,
    val name: String,
    val boxes: List<DoubleArray>,
    val continent: String = "",
    /** Coarser pack this region sits inside, when it is a finer area such as Bali or a US state. */
    val parentId: String? = null,
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
     * Country pack to offer first, and the finer pack when the point sits inside one.
     * Bali is not removed: it is the finer pack inside Indonesia.
     */
    fun suggest(regions: List<RegionInfo>, lon: Double, lat: Double): Pair<RegionInfo, RegionInfo?>? {
        val finest = locate(regions, lon, lat) ?: return null
        val country = countryOf(regions, finest)
        val finer = if (finest.id != country.id) finest else null
        return country to finer
    }
}

package org.openwild.app.core

/** [minLon, minLat, maxLon, maxLat] boxes from regions.json. Same rule as the pipeline's regions.locate(). */
data class RegionInfo(val id: String, val name: String, val boxes: List<DoubleArray>, val continent: String = "") {
    fun contains(lon: Double, lat: Double): Boolean =
        boxes.any { b -> lon >= b[0] && lon <= b[2] && lat >= b[1] && lat <= b[3] }

    val area: Double get() = boxes.sumOf { (it[2] - it[0]) * (it[3] - it[1]) }
}

object RegionLocator {
    /** Smallest region whose boxes contain the point, or null. */
    fun locate(regions: List<RegionInfo>, lon: Double, lat: Double): RegionInfo? =
        regions.filter { it.contains(lon, lat) }.minByOrNull { it.area }
}

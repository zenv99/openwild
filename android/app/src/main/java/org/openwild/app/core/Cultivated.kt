package org.openwild.app.core

/**
 * Houseplants and other ornamentals are often scarce in wild occurrence lists, so the regional prior
 * buries them under common weeds when a photo is a close call (a pot on a table, a cultivar).
 * Flooring the observation count only changes close calls. A clearly better visual match still wins.
 * The floor is applied on the phone at scoring time. It does not rewrite region data.
 */
object Cultivated {
    /** Treated as at least this many regional records. Measured on ID-BA: 500, not higher. */
    const val OBS_FLOOR = 500

    private val names = setOf(
        "asplenium nidus",
        "epipremnum aureum",
        "monstera deliciosa",
        "dieffenbachia seguine",
        "dracaena trifasciata",
        "sansevieria trifasciata",
        "spathiphyllum wallisii",
        "codiaeum variegatum",
        "platycerium bifurcatum",
        "alocasia macrorrhizos",
    )

    fun isCultivated(scientificName: String): Boolean = scientificName.trim().lowercase() in names

    fun floorCounts(scientificNames: Array<String>, obsCounts: IntArray): IntArray {
        val out = obsCounts.copyOf()
        val n = minOf(out.size, scientificNames.size)
        for (i in 0 until n) {
            if (isCultivated(scientificNames[i]) && out[i] < OBS_FLOOR) out[i] = OBS_FLOOR
        }
        return out
    }
}

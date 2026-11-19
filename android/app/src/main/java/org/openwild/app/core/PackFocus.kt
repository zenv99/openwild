package org.openwild.app.core

/**
 * Which downloaded region Explore, the species page, and the home photo rows should show.
 *
 * A region such as World is names and safety text only: its photo table is empty. If that
 * region is the one on screen, every picture in the guide disappears even though another
 * downloaded region still has its photos. Keep a region that has pictures in front.
 */
object PackFocus {
    data class Candidate(val regionId: String, val hasPhotos: Boolean, val speciesCount: Int = 0)

    fun choose(activeId: String?, packs: List<Candidate>): String? {
        if (packs.isEmpty()) return null
        val active = packs.firstOrNull { it.regionId == activeId }
        if (active?.hasPhotos == true) return active.regionId
        val pictured = packs.filter { it.hasPhotos }
        if (pictured.isNotEmpty()) {
            // Larger guide first. Same size: lower region id, so the choice does not flicker.
            return pictured.maxWith(compareBy<Candidate> { it.speciesCount }.thenByDescending { it.regionId }).regionId
        }
        return active?.regionId ?: packs.first().regionId
    }
}

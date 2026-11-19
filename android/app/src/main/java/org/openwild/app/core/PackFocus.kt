package org.openwild.app.core

/**
 * Which downloaded region Explore, the species page, and the home photo rows should show.
 *
 * A pack with an empty photo table (an older World download is names and safety text only)
 * would hide every picture if it stayed on screen while another downloaded region still had
 * photos. Keep a region that has pictures in front, unless the one on screen already has any.
 * A World pack that carries the deadly collection has those photos, so it stays on screen.
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

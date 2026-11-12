package org.openwild.app.core

/** Which installed pack to open. A new install has no choice yet, so nothing is activated. */
object RegionChoice {
    fun activeId(chosenId: String?, installedIds: Collection<String>): String? =
        chosenId?.takeIf { it in installedIds }
}

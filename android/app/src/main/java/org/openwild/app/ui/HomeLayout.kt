package org.openwild.app.ui

/**
 * Home screen section order. Identify, then the pack and brain controls, then everything else.
 * Survival mode stays below the brain section on every screen size.
 */
object HomeLayout {
    const val HEADER = "header"
    const val IDENTIFY = "identify"
    const val BRAIN_AND_PACKS = "brain_and_packs"
    const val EXPLORE = "explore"
    const val PHOTO_ROWS = "photo_rows"
    const val PHOTOGRAPHER = "photographer"
    const val SURVIVAL = "survival"
    const val CREDITS = "credits"

    val sections: List<String> = listOf(
        HEADER,
        IDENTIFY,
        BRAIN_AND_PACKS,
        EXPLORE,
        PHOTO_ROWS,
        PHOTOGRAPHER,
        SURVIVAL,
        CREDITS,
    )

    fun brainFollowsIdentify(): Boolean =
        sections.indexOf(BRAIN_AND_PACKS) == sections.indexOf(IDENTIFY) + 1

    fun brainBeforeSurvival(): Boolean =
        sections.indexOf(BRAIN_AND_PACKS) >= 0 &&
            sections.indexOf(BRAIN_AND_PACKS) < sections.indexOf(SURVIVAL)
}

package org.openwild.app.ui

/**
 * Home screen section order. Packs and the brain are last, under Survival mode.
 */
object HomeLayout {
    const val HEADER = "header"
    const val IDENTIFY = "identify"
    const val EXPLORE = "explore"
    const val PHOTO_ROWS = "photo_rows"
    const val PHOTOGRAPHER = "photographer"
    const val SURVIVAL = "survival"
    const val BRAIN_AND_PACKS = "brain_and_packs"
    const val CREDITS = "credits"

    val sections: List<String> = listOf(
        HEADER,
        IDENTIFY,
        EXPLORE,
        PHOTO_ROWS,
        PHOTOGRAPHER,
        SURVIVAL,
        BRAIN_AND_PACKS,
        CREDITS,
    )

    fun packsAfterSurvival(): Boolean =
        sections.indexOf(BRAIN_AND_PACKS) > sections.indexOf(SURVIVAL)

    fun packsBeforeCredits(): Boolean =
        sections.indexOf(BRAIN_AND_PACKS) + 1 == sections.indexOf(CREDITS)
}

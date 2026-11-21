package org.openwild.app.ui

/**
 * Where each volunteer button goes. These are plain URLs. The screen does not fetch them.
 * A tap opens one URL in the browser through Compose's LocalUriHandler, which sends
 * [android.content.Intent.ACTION_VIEW].
 */
object VolunteerHelp {
    const val PHOTOS_ANCHOR = "add-or-fix-plant-photos"
    const val SAFETY_ANCHOR = "check-plant-safety-info"
    const val TRANSLATE_ANCHOR = "translate-the-app"

    fun photos(repoUrl: String): String = contributing(repoUrl, PHOTOS_ANCHOR)

    fun safety(repoUrl: String): String = contributing(repoUrl, SAFETY_ANCHOR)

    fun translate(repoUrl: String): String = contributing(repoUrl, TRANSLATE_ANCHOR)

    fun bug(repoUrl: String): String = "${trim(repoUrl)}/issues/new"

    fun code(repoUrl: String): String = trim(repoUrl)

    /** The five buttons, in the order shown on the volunteer screen. */
    fun links(repoUrl: String): List<String> = listOf(
        photos(repoUrl),
        safety(repoUrl),
        translate(repoUrl),
        bug(repoUrl),
        code(repoUrl),
    )

    private fun contributing(repoUrl: String, anchor: String): String =
        "${trim(repoUrl)}/blob/main/CONTRIBUTING.md#$anchor"

    private fun trim(repoUrl: String): String = repoUrl.trimEnd('/')
}

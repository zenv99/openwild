package org.openwild.app.ui

import org.openwild.app.core.Attribution
import org.openwild.app.core.LocalContribution
import org.openwild.app.core.LocalProfile
import org.openwild.app.core.PhotoLicense

data class ProfileCreditRow(val id: Long, val line: String, val reported: Boolean)

data class ProfileForm(
    val displayName: String = "",
    val title: String = "",
    val affiliation: String = "",
    val anonymous: Boolean = false,
    val email: String = "",
    val verifiedAcademic: Boolean = false,
    val verifiedDomain: String = "",
    val license: String = PhotoLicense.CC_BY.code,
    val hasPhoto: Boolean = false,
    val domainHint: String = "",
    val onlineEnabled: Boolean = false,
    val savedNote: String = "",
    val contributions: List<ProfileCreditRow> = emptyList(),
) {
    val visibility: String get() = if (anonymous) "anonymous" else "public"

    fun photoCreditLine(): String = Attribution.photoCredit(
        Attribution.creditName(visibility, displayName),
        PhotoLicense.fromCode(license) ?: PhotoLicense.DEFAULT,
    )

    fun reviewCreditLine(): String = Attribution.reviewCredit(Attribution.creditName(visibility, displayName))

    val badgeVisible: Boolean get() = verifiedAcademic && !anonymous && verifiedDomain.isNotBlank()

    fun toProfile(previousHasPhoto: Boolean = hasPhoto): LocalProfile = LocalProfile(
        displayName = displayName,
        title = title,
        affiliation = affiliation,
        visibility = visibility,
        email = email,
        verifiedAcademic = verifiedAcademic,
        verifiedDomain = verifiedDomain.ifBlank { null },
        license = license,
        hasPhoto = previousHasPhoto,
    )
}

fun LocalProfile.toForm(rows: List<LocalContribution>, onlineEnabled: Boolean, hint: String = ""): ProfileForm = ProfileForm(
    displayName = displayName,
    title = title,
    affiliation = affiliation,
    anonymous = visibility == "anonymous",
    email = email,
    verifiedAcademic = verifiedAcademic,
    verifiedDomain = verifiedDomain.orEmpty(),
    license = license,
    hasPhoto = hasPhoto,
    domainHint = hint,
    onlineEnabled = onlineEnabled,
    contributions = rows.map { row ->
        val license = PhotoLicense.fromCode(row.license) ?: PhotoLicense.DEFAULT
        val line = when (row.kind) {
            "photo" -> Attribution.photoCredit(row.credit, license)
            "id_feedback" -> Attribution.feedbackCredit(row.credit)
            else -> Attribution.reviewCredit(row.credit)
        }
        ProfileCreditRow(row.id, "${row.species}: $line", row.reported)
    },
)

object ProfileSamples {
    val empty = ProfileForm(onlineEnabled = false)
    val publicVerified = ProfileForm(
        displayName = "Ada Lovelace",
        title = "Student",
        affiliation = "Example University",
        email = "ada@mit.edu",
        verifiedAcademic = true,
        verifiedDomain = "mit.edu",
        license = PhotoLicense.CC_BY.code,
        hasPhoto = true,
        domainHint = "mit.edu is on the school list. The badge appears after the address is confirmed.",
        onlineEnabled = false,
        contributions = listOf(
            ProfileCreditRow(1, "Quercus robur: Review by Ada Lovelace", false),
            ProfileCreditRow(2, "Hedera helix: Photo: Ada Lovelace · CC BY 4.0", false),
        ),
    )
    val anonymous = ProfileForm(
        displayName = "Ada Lovelace",
        title = "Student",
        affiliation = "Example University",
        anonymous = true,
        email = "ada@mit.edu",
        verifiedAcademic = true,
        verifiedDomain = "mit.edu",
        license = PhotoLicense.CC_BY.code,
        onlineEnabled = false,
        contributions = listOf(
            ProfileCreditRow(1, "Quercus robur: Review by Anonymous", false),
            ProfileCreditRow(2, "Hedera helix: Photo: Anonymous · CC BY 4.0", false),
        ),
    )
}

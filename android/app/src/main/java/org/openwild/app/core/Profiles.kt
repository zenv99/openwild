package org.openwild.app.core

/**
 * Optional profile rules shared by the phone and described in docs/PROFILES-DESIGN.md.
 * The worker in server/src uses the same license codes, trust weights, and domain check.
 * Nothing here opens a network connection.
 */
enum class PhotoLicense(val code: String, val label: String) {
    CC_BY("cc-by", "CC BY 4.0"),
    CC0("cc0", "CC0"),
    CC_BY_SA("cc-by-sa", "CC BY-SA 4.0"),
    ;

    companion object {
        val DEFAULT = CC_BY
        fun fromCode(code: String?): PhotoLicense? = entries.firstOrNull { it.code == code }
    }
}

object Attribution {
    const val ANONYMOUS = "Anonymous"

    /** Name stored on a contribution. Anonymous never uses the typed name or the email. */
    fun creditName(visibility: String, displayName: String): String {
        if (visibility == "anonymous") return ANONYMOUS
        val name = displayName.trim().take(60)
        return if (name.isBlank()) ANONYMOUS else name
    }

    fun photoCredit(name: String, license: PhotoLicense): String = "Photo: $name · ${license.label}"

    fun reviewCredit(name: String): String = "Review by $name"

    fun feedbackCredit(name: String): String = "ID note by $name"
}

object CoarseLocation {
    /** One decimal degree, about 11 km. Same rounding the wrong-ID sheet already uses. */
    fun round1(v: Double): Double = kotlin.math.round(v * 10.0) / 10.0

    /**
     * Null unless the person opted in and both coordinates are real.
     * [longitude] then [latitude], matching [org.openwild.app.pack.Location.lastKnown].
     */
    fun cell(optIn: Boolean, longitude: Double?, latitude: Double?): Pair<Double, Double>? {
        if (!optIn || longitude == null || latitude == null) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return round1(longitude) to round1(latitude)
    }
}

object TrustWeight {
    /**
     * First match wins. Anonymous rows stay at 1 so a single note cannot reveal a school mailbox.
     * Unapproved photos are 0 so they are left out of a score.
     */
    fun weight(
        verifiedAcademic: Boolean,
        visibility: String,
        accountAgeDays: Int,
        kind: String,
        approved: Boolean,
    ): Double {
        if (kind == "photo" && !approved) return 0.0
        if (visibility == "anonymous") return 1.0
        if (verifiedAcademic) return 2.0
        if (accountAgeDays >= 7) return 1.25
        return 0.75
    }
}

object ProfileSyncGate {
    fun allowed(online: Boolean, apiUrl: String): Boolean = online && apiUrl.isNotBlank()
}

data class LocalProfile(
    val displayName: String = "",
    val title: String = "",
    val affiliation: String = "",
    val visibility: String = "public",
    val email: String = "",
    val verifiedAcademic: Boolean = false,
    val verifiedDomain: String? = null,
    val license: String = PhotoLicense.CC_BY.code,
    val hasPhoto: Boolean = false,
)

data class LocalContribution(
    val id: Long = 0,
    val kind: String,
    val species: String,
    val regionId: String,
    val body: String = "",
    val rating: Int = 0,
    val visibility: String,
    val license: String,
    val credit: String,
    val status: String = "queued",
    val reported: Boolean = false,
    val shareLocation: Boolean = false,
    val coarseLon: Double? = null,
    val coarseLat: Double? = null,
    val createdAt: Long = 0,
) {
    /** JSON sent to the profiles host. The mailbox is not a field. */
    fun toSyncJson(): String = JsonText.obj(
        listOf(
            "kind" to JsonText.str(kind),
            "species" to JsonText.str(species),
            "regionId" to JsonText.str(regionId),
            "body" to JsonText.str(body),
            "rating" to rating.toString(),
            "visibility" to JsonText.str(visibility),
            "license" to JsonText.str(license),
            "credit" to JsonText.str(credit),
            "shareLocation" to if (shareLocation) "true" else "false",
            "coarseLat" to (coarseLat?.toString() ?: "null"),
            "coarseLon" to (coarseLon?.toString() ?: "null"),
            "clientId" to id.toString(),
        ),
    )
}

object ProfileDocuments {
    /** What other people are allowed to learn. No mailbox, and no school domain when anonymous. */
    fun publicJson(profile: LocalProfile): String {
        val credit = Attribution.creditName(profile.visibility, profile.displayName)
        if (profile.visibility == "anonymous") {
            return JsonText.obj(
                listOf(
                    "credit" to JsonText.str(credit),
                    "verifiedAcademic" to "false",
                ),
            )
        }
        val showBadge = profile.verifiedAcademic && !profile.verifiedDomain.isNullOrBlank()
        return JsonText.obj(
            listOf(
                "credit" to JsonText.str(credit),
                "title" to JsonText.str(profile.title),
                "affiliation" to JsonText.str(profile.affiliation),
                "verifiedAcademic" to if (showBadge) "true" else "false",
                "verifiedDomain" to if (showBadge) JsonText.str(profile.verifiedDomain!!) else "null",
            ),
        )
    }

    fun ownerExport(profile: LocalProfile, items: List<LocalContribution>): String {
        val profileJson = JsonText.obj(
            listOf(
                "displayName" to JsonText.str(profile.displayName),
                "title" to JsonText.str(profile.title),
                "affiliation" to JsonText.str(profile.affiliation),
                "visibility" to JsonText.str(profile.visibility),
                "email" to JsonText.str(profile.email),
                "verifiedAcademic" to if (profile.verifiedAcademic) "true" else "false",
                "verifiedDomain" to (profile.verifiedDomain?.let { JsonText.str(it) } ?: "null"),
                "license" to JsonText.str(profile.license),
                "hasPhoto" to if (profile.hasPhoto) "true" else "false",
            ),
        )
        val rows = items.joinToString(",") { it.toSyncJson() }
        return JsonText.obj(
            listOf(
                "profile" to profileJson,
                "public" to publicJson(profile),
                "contributions" to "[$rows]",
            ),
        )
    }
}

object JsonText {
    fun str(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    fun obj(fields: List<Pair<String, String>>): String =
        fields.joinToString(",", "{", "}") { (key, value) -> "\"$key\":$value" }
}

object AcademicDomains {
    // Keep in sync with server/src/academic.js FREE_MAIL.
    private val FREE_MAIL = setOf(
        "gmail.com", "googlemail.com", "yahoo.com", "ymail.com", "outlook.com", "hotmail.com",
        "live.com", "msn.com", "icloud.com", "me.com", "mac.com", "proton.me", "protonmail.com",
        "pm.me", "aol.com", "gmx.com", "gmx.net", "yandex.com", "yandex.ru", "qq.com", "163.com",
        "mail.com", "zoho.com", "fastmail.com", "tutanota.com", "tuta.com",
    )

    data class Result(val academic: Boolean, val matchedDomain: String?, val reason: String)

    fun parseList(text: String): Set<String> = text.lineSequence().map { line ->
        val v = line.trim().lowercase()
        if (v.isBlank() || v.startsWith("#")) "" else v
    }.filter { it.isNotEmpty() }.toSet()

    fun parents(domain: String): List<String> {
        val parts = domain.split('.')
        return (0 until parts.size - 1).map { parts.drop(it).joinToString(".") }
    }

    fun suffixAcademic(domain: String): Boolean {
        val p = domain.split('.')
        if (p.size >= 2 && p.last() == "edu") return true
        if (p.size >= 3 && p.last().length == 2 && (p[p.size - 2] == "ac" || p[p.size - 2] == "edu")) return true
        return false
    }

    fun domainOf(email: String): String? {
        val t = email.trim().lowercase()
        val at = t.lastIndexOf('@')
        if (at <= 0 || at == t.length - 1) return null
        val local = t.substring(0, at)
        val domain = t.substring(at + 1).trimEnd('.')
        if (local.isBlank() || domain.contains('@') || domain.any { it.isWhitespace() } || !domain.contains('.')) return null
        if (!domain.matches(Regex("[a-z0-9.-]+")) || domain.contains("..") || domain.startsWith(".") || domain.startsWith("-")) return null
        return domain
    }

    fun check(email: String, domains: Set<String>, abused: Set<String>): Result {
        val domain = domainOf(email) ?: return Result(false, null, "not-an-email")
        val chain = parents(domain)
        if (chain.any { it in FREE_MAIL }) return Result(false, null, "free-mail")
        if (chain.any { it in abused }) return Result(false, null, "abused")
        val hit = chain.firstOrNull { it in domains }
        if (hit != null) return Result(true, hit, "listed")
        if (suffixAcademic(domain)) return Result(true, domain, "suffix")
        return Result(false, null, "not-academic")
    }
}

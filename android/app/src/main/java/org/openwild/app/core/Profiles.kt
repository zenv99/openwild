package org.openwild.app.core

/**
 * Credit attached to something a person chooses to send. There is no account.
 * A blank name is credited as Anonymous. See docs/PROFILES-DESIGN.md.
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

    fun creditName(typedName: String): String {
        val name = typedName.trim().take(60)
        return if (name.isBlank()) ANONYMOUS else name
    }

    fun isAnonymous(typedName: String): Boolean = creditName(typedName) == ANONYMOUS

    /** A picture is sent only with a named credit. */
    fun avatarBytes(typedName: String, jpeg: ByteArray?): ByteArray? =
        if (isAnonymous(typedName) || jpeg == null || jpeg.isEmpty()) null else jpeg

    fun photoCredit(name: String, license: PhotoLicense): String = "Photo: $name · ${license.label}"

    fun reviewCredit(name: String): String = "Review by $name"

    fun feedbackCredit(name: String): String = "ID note by $name"
}

object CoarseLocation {
    /** One decimal degree, about 11 km. Same rounding the wrong-ID sheet already uses. */
    fun round1(v: Double): Double = kotlin.math.round(v * 10.0) / 10.0

    /** Null unless the person opted in. Longitude then latitude. */
    fun cell(optIn: Boolean, longitude: Double?, latitude: Double?): Pair<Double, Double>? {
        if (!optIn || longitude == null || latitude == null) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return round1(longitude) to round1(latitude)
    }
}

/** Every note counts the same. There is no account to weigh. */
object TrustWeight {
    fun weight(): Double = 1.0
}

object ProfileSyncGate {
    fun allowed(online: Boolean, apiUrl: String): Boolean = online && apiUrl.isNotBlank()
}

data class QueuedSubmission(
    val id: Long = 0,
    val kind: String,
    val species: String,
    val regionId: String,
    val body: String = "",
    val rating: Int = 0,
    val credit: String,
    val title: String = "",
    val affiliation: String = "",
    val license: String = PhotoLicense.CC_BY.code,
    val status: String = "queued",
    val shareLocation: Boolean = false,
    val coarseLon: Double? = null,
    val coarseLat: Double? = null,
    val createdAt: Long = 0,
    val hasAvatar: Boolean = false,
) {
    fun toSyncJson(installId: String, avatarBase64: String?): String {
        val avatar = if (credit == Attribution.ANONYMOUS) null else avatarBase64
        return JsonText.obj(
            listOf(
                "installId" to JsonText.str(installId),
                "kind" to JsonText.str(kind),
                "species" to JsonText.str(species),
                "regionId" to JsonText.str(regionId),
                "body" to JsonText.str(body),
                "rating" to rating.toString(),
                "credit" to JsonText.str(credit),
                "title" to JsonText.str(if (credit == Attribution.ANONYMOUS) "" else title),
                "affiliation" to JsonText.str(if (credit == Attribution.ANONYMOUS) "" else affiliation),
                "license" to JsonText.str(license),
                "shareLocation" to if (shareLocation) "true" else "false",
                "coarseLat" to (coarseLat?.toString() ?: "null"),
                "coarseLon" to (coarseLon?.toString() ?: "null"),
                "clientId" to id.toString(),
                "avatarBase64" to (avatar?.let { JsonText.str(it) } ?: "null"),
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

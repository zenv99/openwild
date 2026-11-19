package org.openwild.app.core

/**
 * Pl@ntNet identify request, without the network call.
 * The key the person types wins. A private build may supply a key that is not in source control;
 * it is used only when the field on the phone is empty.
 */
object PlantNetRequest {
    const val HOST = "my-api.plantnet.org"

    fun resolveKey(userField: String, buildSecret: String): String {
        val typed = userField.trim()
        if (typed.isNotEmpty()) return typed
        return buildSecret.trim()
    }

    /** Pl@ntNet organ tag for one photo. Mushrooms have no organ tag; the service is for plants. */
    fun organ(part: String): String = when (part) {
        "LEAF" -> "leaf"
        "FLOWER_FRUIT" -> "flower"
        "BARK" -> "bark"
        else -> "auto"
    }
}

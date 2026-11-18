package org.openwild.app.core

/**
 * Offline name suggestions for the wrong-ID sheet. Matches scientific and common names already
 * installed in a region pack. Nothing is fetched.
 */
object NameSuggest {
    data class Hit(val label: String, val scientific: String)

    fun matches(
        query: String,
        scientific: Array<String>,
        common: Array<String?>,
        limit: Int = 6,
    ): List<Hit> {
        val q = query.trim().lowercase()
        if (q.length < 2 || limit <= 0) return emptyList()
        val out = ArrayList<Hit>(limit)
        val seen = HashSet<String>()
        val n = minOf(scientific.size, common.size)
        for (i in 0 until n) {
            val sci = scientific[i]
            val key = sci.trim().lowercase()
            if (!seen.add(key)) continue
            val com = common[i]
            val hit = key.contains(q) || com?.lowercase()?.contains(q) == true
            if (!hit) continue
            val label = if (!com.isNullOrBlank()) "$com ($sci)" else sci
            out += Hit(label, sci)
            if (out.size >= limit) break
        }
        return out
    }
}

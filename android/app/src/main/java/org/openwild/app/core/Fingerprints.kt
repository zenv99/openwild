package org.openwild.app.core

import kotlin.math.sqrt

/** Average several L2-normalised fingerprints (test-time crops) back into one unit vector. */
object Fingerprints {
    fun meanUnit(vectors: List<FloatArray>): FloatArray {
        require(vectors.isNotEmpty()) { "no fingerprints" }
        val dim = vectors[0].size
        val acc = FloatArray(dim)
        for (v in vectors) {
            require(v.size == dim) { "fingerprint length" }
            for (i in 0 until dim) acc[i] += v[i]
        }
        var n = 0f
        for (x in acc) n += x * x
        n = sqrt(n).coerceAtLeast(1e-12f)
        for (i in acc.indices) acc[i] /= n
        return acc
    }
}

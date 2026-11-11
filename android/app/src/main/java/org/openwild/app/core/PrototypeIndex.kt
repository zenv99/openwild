package org.openwild.app.core

/**
 * All prototypes of a region pack in one flat int8 array, for fast brute-force cosine search.
 * A few thousand species × a few prototypes × 768 dims takes well under a second per photo on a phone.
 */
class PrototypeIndex(
    val dim: Int,
    /** prototypeCount * dim int8 values, row-major. */
    private val vectors: ByteArray,
    /** Dequantisation scale per prototype. */
    private val scales: FloatArray,
    /** Species index (0 until speciesCount) per prototype. */
    private val speciesOf: IntArray,
    /** True for text-derived (lower-trust) prototypes. */
    private val isText: BooleanArray,
    val speciesCount: Int,
) {
    val size: Int get() = scales.size

    init {
        require(vectors.size == size * dim) { "vector data size mismatch" }
        require(speciesOf.size == size && isText.size == size)
    }

    /**
     * Best-prototype similarity of one L2-normalised photo fingerprint to every species.
     * Species not in [allowed] (or with no prototype) get NEGATIVE_INFINITY.
     */
    fun similarity(embedding: FloatArray, textPenalty: Double, allowed: BooleanArray? = null): DoubleArray {
        require(embedding.size == dim) { "embedding has ${embedding.size} dims, pack expects $dim" }
        val out = DoubleArray(speciesCount) { Double.NEGATIVE_INFINITY }
        for (j in 0 until size) {
            val sp = speciesOf[j]
            if (allowed != null && !allowed[sp]) continue
            var dot = 0f
            val base = j * dim
            for (d in 0 until dim) dot += embedding[d] * vectors[base + d]
            var sim = (dot * scales[j]).toDouble()
            if (isText[j]) sim -= textPenalty
            if (sim > out[sp]) out[sp] = sim
        }
        return out
    }
}

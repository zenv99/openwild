package org.openwild.app.core

import java.util.stream.IntStream

/**
 * All prototypes of a region pack in one flat int8 array, for fast brute-force cosine search.
 *
 * Performance notes:
 *  - All photos are scored in ONE pass over the prototypes (each 768-byte row is read once, from cache).
 *  - The dot-product loop is unrolled 4x.
 *  - The prototype range is split into chunks scored in parallel on all CPU cores.
 * A pack of ~25k prototypes × 768 dims scores 5 photos in well under 100 ms on a mid-range phone.
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

    /** Single-photo convenience wrapper around [similarityBatch]. */
    fun similarity(embedding: FloatArray, textPenalty: Double, allowed: BooleanArray? = null): DoubleArray =
        similarityBatch(listOf(embedding), textPenalty, allowed, parallel = false)[0]

    /**
     * Best-prototype similarity of each L2-normalised photo fingerprint to every species: result[photo][species].
     * Species not in [allowed] (or with no prototype) get NEGATIVE_INFINITY.
     */
    fun similarityBatch(
        embeddings: List<FloatArray>,
        textPenalty: Double,
        allowed: BooleanArray? = null,
        parallel: Boolean = true,
    ): Array<DoubleArray> {
        embeddings.forEach { require(it.size == dim) { "embedding has ${it.size} dims, pack expects $dim" } }
        val n = embeddings.size
        val chunks = if (parallel && size >= PARALLEL_MIN) Runtime.getRuntime().availableProcessors().coerceIn(1, 8) else 1
        val partial = Array(chunks) { Array(n) { DoubleArray(speciesCount) { Double.NEGATIVE_INFINITY } } }
        val per = (size + chunks - 1) / chunks
        val work = { c: Int ->
            val out = partial[c]
            val end = minOf(size, (c + 1) * per)
            for (j in c * per until end) {
                val sp = speciesOf[j]
                if (allowed != null && !allowed[sp]) continue
                val base = j * dim
                val penalty = if (isText[j]) textPenalty else 0.0
                for (p in 0 until n) {
                    val sim = dot(embeddings[p], base) * scales[j] - penalty
                    if (sim > out[p][sp]) out[p][sp] = sim
                }
            }
        }
        if (chunks == 1) work(0) else IntStream.range(0, chunks).parallel().forEach { work(it) }

        val result = partial[0]
        for (c in 1 until chunks) {
            for (p in 0 until n) {
                val dst = result[p]
                val src = partial[c][p]
                for (s in 0 until speciesCount) if (src[s] > dst[s]) dst[s] = src[s]
            }
        }
        return result
    }

    private fun dot(e: FloatArray, base: Int): Double {
        var a0 = 0f
        var a1 = 0f
        var a2 = 0f
        var a3 = 0f
        var d = 0
        val limit = dim - 3
        while (d < limit) {
            a0 += e[d] * vectors[base + d]
            a1 += e[d + 1] * vectors[base + d + 1]
            a2 += e[d + 2] * vectors[base + d + 2]
            a3 += e[d + 3] * vectors[base + d + 3]
            d += 4
        }
        while (d < dim) {
            a0 += e[d] * vectors[base + d]
            d++
        }
        return (a0 + a1 + a2 + a3).toDouble()
    }

    companion object {
        /** Below this many prototypes, threading overhead isn't worth it. */
        const val PARALLEL_MIN = 2048
    }
}

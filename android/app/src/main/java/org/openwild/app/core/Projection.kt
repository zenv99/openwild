package org.openwild.app.core

import kotlin.math.sqrt

/**
 * PCA projection stored in a region pack (pipeline/openwild_pipeline/projection.py).
 * Shrinks fingerprints (e.g. 768 -> 256 dims) so packs can hold tens of thousands of species.
 */
class Projection(val rows: Int, val cols: Int, private val matrix: FloatArray, private val mean: FloatArray) {
    init {
        require(matrix.size == rows * cols && mean.size == cols) { "projection size mismatch" }
    }

    /** z = normalize(W · (x − mean)) */
    fun apply(x: FloatArray): FloatArray {
        require(x.size == cols) { "expected $cols dims, got ${x.size}" }
        val centered = FloatArray(cols) { x[it] - mean[it] }
        val z = FloatArray(rows)
        var norm = 0f
        for (r in 0 until rows) {
            var acc = 0f
            val base = r * cols
            for (c in 0 until cols) acc += matrix[base + c] * centered[c]
            z[r] = acc
            norm += acc * acc
        }
        val n = sqrt(norm).coerceAtLeast(1e-12f)
        for (r in 0 until rows) z[r] /= n
        return z
    }
}

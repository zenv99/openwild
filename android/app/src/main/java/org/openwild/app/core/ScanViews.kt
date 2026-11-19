package org.openwild.app.core

/**
 * Views of one photo used for identification.
 * One look is the whole frame. Several looks add a horizontal flip, the upper band, and a tighter
 * centre. The pack's reference fingerprints stay as they are; only the query changes.
 */
object ScanViews {
    data class Spec(val flip: Boolean, val left: Int, val top: Int, val width: Int, val height: Int)

    const val UPPER = 0.72f
    const val TIGHT = 0.72f

    fun of(width: Int, height: Int, careful: Boolean): List<Spec> {
        if (!careful || width < 2 || height < 2) return listOf(Spec(false, 0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1)))
        val upperH = (height * UPPER).toInt().coerceIn(1, height)
        val side = (minOf(width, height) * TIGHT).toInt().coerceAtLeast(1)
        val left = ((width - side) / 2).coerceAtLeast(0)
        val top = ((height - side) / 2).coerceAtLeast(0)
        val tightW = side.coerceAtMost(width - left)
        val tightH = side.coerceAtMost(height - top)
        return listOf(
            Spec(false, 0, 0, width, height),
            Spec(true, 0, 0, width, height),
            Spec(false, 0, 0, width, upperH),
            Spec(false, left, top, tightW, tightH),
        )
    }
}

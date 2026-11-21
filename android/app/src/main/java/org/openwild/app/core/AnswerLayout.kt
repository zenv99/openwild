package org.openwild.app.core

/**
 * When the result is not a clear match, the screen lists the top few names instead of crowning one.
 * High confidence already requires a wide gap. A medium result with a close second is treated the
 * same way: three possibilities, not a single name that is often wrong.
 */
object AnswerLayout {
    const val SHORTLIST = 3

    /** Gap between the first and second probability before a medium result is shown as one name. */
    const val SINGLE_MARGIN = 0.15

    fun singleAnswer(result: ConfidenceResult): Boolean = when (result.tier) {
        Tier.LOW, Tier.UNKNOWN -> false
        Tier.HIGH -> true
        Tier.MEDIUM -> {
            if (result.order.size < 2) true
            else result.probs[result.order[0]] - result.probs[result.order[1]] >= SINGLE_MARGIN
        }
    }
}

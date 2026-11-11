package org.openwild.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt

class ConfidenceTest {

    /** Same case as pipeline/tests/fixtures/confidence_case.json — results must match Python exactly. */
    private fun fixtureSim(photos: List<DoubleArray>): Array<DoubleArray> {
        val protos = listOf(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0), doubleArrayOf(0.8, 0.6, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0, 0.0),
        )
        val owner = intArrayOf(0, 0, 1, 2)
        val isText = booleanArrayOf(false, false, false, true)
        return photos.map { raw ->
            val norm = sqrt(raw.sumOf { it * it })
            val p = raw.map { it / norm }
            val out = DoubleArray(3) { Double.NEGATIVE_INFINITY }
            protos.forEachIndexed { j, v ->
                var s = (0 until 4).sumOf { p[it] * v[it] }
                if (isText[j]) s -= 0.05
                if (s > out[owner[j]]) out[owner[j]] = s
            }
            out
        }.toTypedArray()
    }

    private fun logPrior(): DoubleArray {
        val p = Confidence.prior(intArrayOf(100, 10, 1), intArrayOf(255, 128, 0))
        return DoubleArray(3) { ln(p[it]) }
    }

    @Test
    fun matchesPythonFixture() {
        val sim = fixtureSim(listOf(doubleArrayOf(0.9, 0.1, 0.0, 0.0), doubleArrayOf(0.7, 0.7, 0.0, 0.0)))
        val r = Confidence.score(sim, logPrior(), ConfidenceParams(temperature = 0.2))
        assertEquals(0, r.top)
        assertEquals(Tier.HIGH, r.tier)
        assertEquals(99, r.score)
        val expected = doubleArrayOf(0.987411111280852, 0.012433086477724535, 0.0001558022414234856)
        for (i in 0 until 3) assertEquals(expected[i], r.probs[i], 1e-6)
    }

    @Test
    fun unknownWhenNothingMatches() {
        val sim = fixtureSim(listOf(doubleArrayOf(0.0, 0.0, 0.0, 1.0)))
        assertEquals(Tier.UNKNOWN, Confidence.score(sim, logPrior(), ConfidenceParams(temperature = 0.2)).tier)
    }

    @Test
    fun ineligibleSpeciesNeverWin() {
        val sim = arrayOf(doubleArrayOf(Double.NEGATIVE_INFINITY, 0.5, 0.4))
        val r = Confidence.score(sim, doubleArrayOf(0.0, 0.0, 0.0), ConfidenceParams())
        assertEquals(1, r.top)
        assertFalse(r.order.contains(0))
    }
}

class PrototypeIndexTest {
    @Test
    fun bestPrototypePerSpeciesAndFilter() {
        val dim = 4
        val vecs = byteArrayOf(127, 0, 0, 0, 0, 127, 0, 0, 0, 0, 127, 0)
        val idx = PrototypeIndex(
            dim, vecs, floatArrayOf(1 / 127f, 1 / 127f, 1 / 127f),
            intArrayOf(0, 1, 1), booleanArrayOf(false, false, true), speciesCount = 2,
        )
        val sim = idx.similarity(floatArrayOf(0f, 0f, 1f, 0f), textPenalty = 0.05)
        assertEquals(0.0, sim[0], 1e-6)
        assertEquals(0.95, sim[1], 1e-6) // text prototype, penalised
        val filtered = idx.similarity(floatArrayOf(1f, 0f, 0f, 0f), 0.05, booleanArrayOf(false, true))
        assertTrue(filtered[0].isInfinite())
    }
}

class SafetyPolicyTest {
    private val deadly = SpeciesContent(edibility = "deadly", toxicity = "deadly")
    private val edible = SpeciesContent(edibility = "edible", toxicity = "none", expertReviewed = true)
    private val psychoactive = SpeciesContent(edibility = "psychoactive", toxicity = "mild", psychoactive = true)

    @Test
    fun highTierPlantShowsEverything() {
        val v = SafetyPolicy.visibility(Tier.HIGH, edible, isFungus = false, photoCount = 1, hasUndersidePhoto = false)
        assertTrue(v.showBenefits && v.showFeeling && v.showHarms)
        assertTrue(v.notices.isEmpty())
    }

    @Test
    fun mediumTierHidesEatingInfo() {
        val v = SafetyPolicy.visibility(Tier.MEDIUM, edible, false, 3, false)
        assertFalse(v.showBenefits || v.showFeeling)
        assertTrue(v.showHarms)
        assertTrue(Notice.NOT_CONFIDENT_EATING in v.notices)
    }

    @Test
    fun fungusNeedsUndersideAndTwoPhotos() {
        val one = SafetyPolicy.visibility(Tier.HIGH, edible, true, 1, true)
        assertFalse(one.showBenefits)
        assertTrue(Notice.NEED_UNDERSIDE_PHOTO in one.notices)
        val ok = SafetyPolicy.visibility(Tier.HIGH, edible, true, 2, true)
        assertTrue(ok.showBenefits)
        assertTrue(Notice.MUSHROOM_POOR_SURVIVAL_FOOD in ok.notices)
    }

    @Test
    fun noContentMeansUnsafe() {
        val v = SafetyPolicy.visibility(Tier.HIGH, null, false, 3, false)
        assertFalse(v.showBenefits)
        assertTrue(Notice.NO_SAFETY_DATA in v.notices)
    }

    @Test
    fun psychoactiveAlwaysCarriesLegalWarning() {
        val v = SafetyPolicy.visibility(Tier.LOW, psychoactive, true, 1, false)
        assertTrue(Notice.LEGAL_WARNING in v.notices)
        assertTrue(Notice.NOT_EXPERT_REVIEWED in v.notices)
    }

    @Test
    fun deadlyBannerIncludesLikelyDeadlyCandidates() {
        val r = ConfidenceResult(
            order = intArrayOf(0, 1, 2), probs = doubleArrayOf(0.9, 0.08, 0.01),
            tier = Tier.HIGH, score = 90, agreement = 1.0, meanTopSim = 0.8,
        )
        val contents = listOf(edible, deadly, deadly)
        assertEquals(listOf(1), SafetyPolicy.deadlyCandidates(r) { contents[it].isDeadly })
    }
}

class RegionLocatorTest {
    @Test
    fun smallestContainingRegionWins() {
        val world = RegionInfo("WORLD", "World", listOf(doubleArrayOf(-180.0, -90.0, 180.0, 90.0)))
        val ph = RegionInfo("PH", "Philippines", listOf(doubleArrayOf(116.9, 4.5, 126.7, 21.2)))
        assertEquals("PH", RegionLocator.locate(listOf(world, ph), 121.0, 14.6)?.id)
        assertEquals("WORLD", RegionLocator.locate(listOf(world, ph), -30.0, 0.0)?.id)
    }
}

class PrototypeBatchTest {
    @Test
    fun parallelBatchMatchesSequentialSingle() {
        val rnd = java.util.Random(7)
        val dim = 64
        val protos = 5000
        val species = 700
        val vecs = ByteArray(protos * dim) { (rnd.nextInt(255) - 127).toByte() }
        val scales = FloatArray(protos) { 1f / (127f * kotlin.math.sqrt(dim.toFloat())) }
        val owner = IntArray(protos) { it % species }
        val isText = BooleanArray(protos) { it % 11 == 0 }
        val idx = PrototypeIndex(dim, vecs, scales, owner, isText, species)
        val photos = List(4) { FloatArray(dim) { rnd.nextFloat() - 0.5f } }
        val allowed = BooleanArray(species) { it % 3 != 0 }
        val batch = idx.similarityBatch(photos, 0.05, allowed, parallel = true)
        photos.forEachIndexed { p, e ->
            val single = idx.similarity(e, 0.05, allowed)
            for (s in 0 until species) {
                if (single[s].isInfinite()) assertTrue(batch[p][s].isInfinite())
                else assertEquals(single[s], batch[p][s], 1e-9)
            }
        }
    }
}

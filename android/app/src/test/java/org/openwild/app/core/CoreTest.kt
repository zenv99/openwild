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

    @Test
    fun countryDownloadSkipsHiddenFinerPacks() {
        val world = RegionInfo("WORLD", "World", listOf(doubleArrayOf(-180.0, -90.0, 180.0, 90.0)))
        val indonesia = RegionInfo("ID", "Indonesia", listOf(doubleArrayOf(94.9, -11.1, 141.1, 6.1)))
        val bali = RegionInfo("ID-BA", "Bali, Indonesia", listOf(doubleArrayOf(114.4, -8.86, 115.72, -8.06)), parentId = "ID", hidden = true)
        val us = RegionInfo("US", "United States", listOf(doubleArrayOf(-124.8, 24.5, -66.9, 49.4)))
        val hawaii = RegionInfo("US-HI", "Hawaii, USA", listOf(doubleArrayOf(-160.3, 18.9, -154.8, 22.3)), parentId = "US", hidden = true)
        val regions = listOf(world, indonesia, bali, us, hawaii)
        assertEquals("ID-BA", RegionLocator.locate(regions, 115.2, -8.65)?.id)
        val (baliCountry, baliFiner) = RegionLocator.suggest(regions, 115.2, -8.65)!!
        assertEquals("ID", baliCountry.id)
        assertEquals(null, baliFiner)
        val (hi, hiFiner) = RegionLocator.suggest(regions, -157.8, 21.3)!!
        assertEquals("US", hi.id)
        assertEquals(null, hiFiner)
        val manila = RegionInfo("PH", "Philippines", listOf(doubleArrayOf(116.9, 4.5, 126.7, 21.2)))
        val (ph, none) = RegionLocator.suggest(listOf(world, manila), 121.0, 14.6)!!
        assertEquals("PH", ph.id)
        assertEquals(null, none)
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

class ProjectionAndGroupTest {
    @Test
    fun projectionMatchesPython() {
        // Same case computed with pipeline/openwild_pipeline/projection.py
        val p = Projection(2, 4, floatArrayOf(1f, 0f, 0f, 0f, 0f, 0.6f, 0.8f, 0f), floatArrayOf(0.1f, 0f, 0f, 0f))
        val z = p.apply(floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f))
        assertEquals(0.49613893, z[0].toDouble(), 1e-5)
        assertEquals(0.86824316, z[1].toDouble(), 1e-5)
    }

    @Test
    fun groupLevelWarningNeverUnlocksEating() {
        val genusOnly = SpeciesContent(edibility = "edible", toxicity = "mild", level = "genus", groupTaxon = "Russula")
        val v = SafetyPolicy.visibility(Tier.HIGH, genusOnly, isFungus = false, photoCount = 3, hasUndersidePhoto = true)
        assertFalse(v.showBenefits || v.showFeeling)
        assertTrue(Notice.GROUP_LEVEL_ONLY in v.notices)
    }
}

class RegionFusionTest {
    private fun library(regionId: String, names: Array<String>, vectors: ByteArray, owners: IntArray): RegionLibrary {
        val n = names.size
        val index = PrototypeIndex(
            dim = 4,
            vectors = vectors,
            scales = FloatArray(owners.size) { 1f / 127f },
            speciesOf = owners,
            isText = BooleanArray(owners.size),
            speciesCount = n,
        )
        val species = SpeciesTable(
            ids = LongArray(n) { it.toLong() },
            scientificNames = names,
            commonNames = arrayOfNulls(n),
            isFungus = BooleanArray(n),
            obsCounts = IntArray(n) { 10 },
            months = Array(12) { IntArray(n) { 128 } },
        )
        return RegionLibrary(regionId, species, index, ConfidenceParams(temperature = 0.2))
    }

    @Test
    fun onePackMatchesIdentifier() {
        val vecs = byteArrayOf(127, 0, 0, 0, 0, 127, 0, 0)
        val lib = library("ID", arrayOf("Cocos nucifera", "Rafflesia arnoldii"), vecs, intArrayOf(0, 1))
        val photo = listOf(floatArrayOf(1f, 0f, 0f, 0f))
        val direct = Identifier(lib.index, lib.species, lib.params, null).identify(photo, KingdomFilter.ANY, 6)
        val fused = RegionFusion.identify(listOf(lib), photo, KingdomFilter.ANY, 6, "ID")!!
        assertEquals(direct.top, fused.result.top)
        assertEquals(direct.tier, fused.result.tier)
        assertEquals(direct.score, fused.result.score)
        assertEquals(direct.probs[direct.top], fused.result.probs[fused.result.top], 1e-9)
    }

    @Test
    fun sameSpeciesInTwoPacksIsNotSplit() {
        val vec = byteArrayOf(127, 0, 0, 0)
        val bali = library("ID-BA", arrayOf("Cocos nucifera"), vec, intArrayOf(0))
        val indonesia = library(
            "ID",
            arrayOf("Cocos nucifera", "Rafflesia arnoldii"),
            byteArrayOf(127, 0, 0, 0, 0, 127, 0, 0),
            intArrayOf(0, 1),
        )
        val photo = listOf(floatArrayOf(1f, 0f, 0f, 0f))
        val split = Confidence.score(
            arrayOf(doubleArrayOf(1.0, 1.0, 0.0)),
            doubleArrayOf(kotlin.math.ln(1.0 / 3), kotlin.math.ln(1.0 / 3), kotlin.math.ln(1.0 / 3)),
            ConfidenceParams(temperature = 0.2),
        )
        val fused = RegionFusion.identify(listOf(bali, indonesia), photo, KingdomFilter.ANY, 6, "ID-BA")!!
        assertEquals("ID-BA", fused.displayRegionId)
        assertEquals(0, fused.result.top)
        assertTrue(fused.result.probs[0] > split.probs[0])
        println("FUSE splitTop=${"%.3f".format(split.probs[0])} fusedTop=${"%.3f".format(fused.result.probs[0])}")
    }
}

class CultivatedPriorTest {
    @Test
    fun floorOnlyLiftsListedHouseplants() {
        val names = arrayOf("Erigeron sumatrensis", "Asplenium nidus", "Cocos nucifera")
        val out = Cultivated.floorCounts(names, intArrayOf(8000, 22, 4000))
        assertEquals(8000, out[0])
        assertEquals(Cultivated.OBS_FLOOR, out[1])
        assertEquals(4000, out[2])
        assertEquals(22, intArrayOf(8000, 22, 4000)[1])
    }

    @Test
    fun closeCallMovesTowardTheHouseplantAndAClearVisualMatchDoesNot() {
        val params = ConfidenceParams(temperature = 0.022, priorBeta = 0.5, photoAlpha = 0.6)
        val raw = intArrayOf(8000, 22)
        val floored = Cultivated.floorCounts(arrayOf("Erigeron sumatrensis", "Asplenium nidus"), raw)
        fun logPrior(obs: IntArray) = DoubleArray(obs.size) { ln(Confidence.prior(obs, null)[it]) }
        val close = arrayOf(doubleArrayOf(0.62, 0.67))
        val before = Confidence.score(close, logPrior(raw), params)
        val after = Confidence.score(close, logPrior(floored), params)
        assertEquals(0, before.top)
        assertEquals(1, after.top)
        val clear = arrayOf(doubleArrayOf(0.80, 0.62))
        val stillWeed = Confidence.score(clear, logPrior(floored), params)
        assertEquals(0, stillWeed.top)
    }
}

class FingerprintAverageTest {
    @Test
    fun meanOfUnitVectorsIsUnit() {
        val v = Fingerprints.meanUnit(listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
        val n = sqrt((v[0] * v[0] + v[1] * v[1]).toDouble())
        assertEquals(1.0, n, 1e-5)
        assertEquals(v[0].toDouble(), v[1].toDouble(), 1e-5)
    }
}

class NameSuggestTest {
    @Test
    fun matchesScientificAndCommonNamesOffline() {
        val sci = arrayOf("Asplenium nidus", "Cocos nucifera")
        val common = arrayOf<String?>("Hawai'I Birdnest Fern", "Coconut")
        assertTrue(NameSuggest.matches("", sci, common).isEmpty())
        assertTrue(NameSuggest.matches("a", sci, common).isEmpty())
        val hit = NameSuggest.matches("bird", sci, common)
        assertEquals(1, hit.size)
        assertEquals("Asplenium nidus", hit[0].scientific)
        assertTrue(hit[0].label.contains("Birdnest"))
        assertEquals("Cocos nucifera", NameSuggest.matches("coco", sci, common)[0].scientific)
    }
}

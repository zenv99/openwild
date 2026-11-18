package org.openwild.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openwild.app.OpenWildApp
import org.openwild.app.R
import org.openwild.app.data.ReviewStore
import java.io.File
import org.openwild.app.core.ConfidenceResult
import org.openwild.app.core.Identifier
import org.openwild.app.core.KingdomFilter
import org.openwild.app.core.RegionInfo
import org.openwild.app.core.RegionLocator
import org.openwild.app.core.SafetyPolicy
import org.openwild.app.core.Tier
import org.openwild.app.ml.ImageUtils
import org.openwild.app.pack.Location
import org.openwild.app.pack.PackManager
import org.openwild.app.pack.RegionPack
import java.util.Calendar

enum class PhotoPart(val label: Int, val forFungus: Boolean, val isUnderside: Boolean = false) {
    WHOLE(R.string.part_whole, false),
    LEAF(R.string.part_leaf, false),
    FLOWER_FRUIT(R.string.part_flower_fruit, false),
    BARK(R.string.part_bark, false),
    CAP(R.string.part_cap, true),
    UNDERSIDE(R.string.part_underside, true, isUnderside = true),
    STEM_BASE(R.string.part_stem_base, true),
}

data class Photo(val bitmap: Bitmap, val part: PhotoPart)

data class Candidate(val index: Int, val prob: Double, val isDeadly: Boolean)

data class IdResult(
    val result: ConfidenceResult,
    val candidates: List<Candidate>,
    val deadly: List<Int>,
    val photoCount: Int,
    val hasUnderside: Boolean,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

sealed interface Screen {
    data object Home : Screen
    data object Capture : Screen
    data object Results : Screen
    data class Species(val index: Int, val tier: Tier) : Screen
    /** Swipe through the ranked matches for the last identification, most likely first. */
    data class Matches(val start: Int = 0) : Screen
    data class AddPhotos(val index: Int, val stage: String? = null) : Screen
    data object Packs : Screen
    data object Survival : Screen
    data object Credits : Screen
    data object Explore : Screen
    data object About : Screen
}

/** Bottom-navigation destinations. */
val TopLevel: List<Screen> = listOf(Screen.Home, Screen.Explore, Screen.Packs, Screen.About)

data class UiState(
    val disclaimerAccepted: Boolean = false,
    val stack: List<Screen> = listOf(Screen.Home),
    val survivalMode: Boolean = false,
    val modelMissing: Boolean = false,
    /** Active pack has photo fingerprints (guide-only packs don't yet). */
    val photoIdReady: Boolean = false,
    val activeRegionName: String? = null,
    val activeSpeciesCount: Int = 0,
    val suggestedRegion: String? = null,
    val suggestedRegionId: String? = null,
    val regions: List<RegionInfo> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.DARK,
    val installed: List<PackManager.InstalledPack> = emptyList(),
    val remote: List<PackManager.RemotePack> = emptyList(),
    val remoteError: Boolean = false,
    /** The list of downloadable regions is being fetched from GitHub. */
    val remoteLoading: Boolean = false,
    /** Home-screen reminders about newer downloads (switch in Packs). */
    val updateReminders: Boolean = true,
    /** "Not now" on a home-screen offer: region -> the version that was dismissed. */
    val dismissedUpdates: Map<String, Int> = emptyMap(),
    val downloading: Map<String, Float> = emptyMap(),
    val filter: KingdomFilter = KingdomFilter.ANY,
    val photos: List<Photo> = emptyList(),
    val busy: Boolean = false,
    val result: IdResult? = null,
    val message: String? = null,
    val exploreQuery: String = "",
    val exploreFilter: RegionPack.ExploreFilter = RegionPack.ExploreFilter.ALL,
    /** False until installed packs have been read. The region chooser waits for this. */
    val bootstrapped: Boolean = false,
    val activeRegionId: String? = null,
    val activePackVersion: Int = 0,
    val bundled: List<PackManager.BundledPack> = emptyList(),
    val installingRegion: String? = null,
    /** Optional "Better recognition" (BioCLIP 2). */
    val upgrade: UpgradeState = UpgradeState(),
) {
    val screen: Screen get() = stack.last()
}

data class UpgradeState(
    val on: Boolean = false,
    val modelInstalled: Boolean = false,
    /** Matching uses BioCLIP 2 right now (model + this region's boost file present and current). */
    val active: Boolean = false,
    val modelMb: Int = 0,
    val regionMb: Int = 0,
    /** A boost file exists on GitHub for the active region. */
    val regionAvailable: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    /** Opt-in online check (cloud identification); reset for every new identification. */
    val online = OnlineCheck(app, viewModelScope)


    private val ow = app as OpenWildApp
    private val prefs = app.getSharedPreferences("openwild", 0)
    private val _state = MutableStateFlow(
        UiState(
            disclaimerAccepted = prefs.getBoolean("disclaimer", false),
            survivalMode = prefs.getBoolean("survival", false),
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString("theme", "DARK")!!) }.getOrDefault(ThemeMode.DARK),
        ),
    )
    val state: StateFlow<UiState> = _state

    /** The loaded active pack (kept out of UiState because it's large). */
    var pack: RegionPack? = null
        private set
    private var identifier: Identifier? = null
    /** BioCLIP 2 embedder when "Better recognition" is active for this pack; otherwise the built-in model. */
    private var activeEmbedder: org.openwild.app.ml.Embedder? = null
    private var upgradeEmbedder: org.openwild.app.ml.Embedder? = null
    private var upgradeInfo: PackManager.Upgrade? = null

    init {
        viewModelScope.launch {
            val missing = withContext(Dispatchers.Default) { ow.embedder == null }
            val regions = withContext(Dispatchers.IO) { Location.bundledRegions(getApplication<Application>()) }
            val bundled = withContext(Dispatchers.IO) { ow.packs.bundledCatalog() }
            val ui = getApplication<Application>().getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
            val dismissed = ui.all.filterKeys { it.startsWith("dismissed_") }
                .mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix("dismissed_") to it } }.toMap()
            _state.update { it.copy(modelMissing = missing, regions = regions, bundled = bundled,
                updateReminders = ui.getBoolean("update_reminders", true), dismissedUpdates = dismissed) }
            // First-run suggestion without asking for location: the phone's time zone and region setting.
            if (_state.value.suggestedRegionId == null) {
                val tz = java.util.TimeZone.getDefault().id
                val country = java.util.Locale.getDefault().country.uppercase()
                val guess = when {
                    tz == "Asia/Makassar" && regions.any { it.id == "ID-BA" } -> "ID-BA"   // Bali, Nusa Tenggara, Sulawesi
                    else -> regions.firstOrNull { it.id == country }?.id
                }
                regions.firstOrNull { it.id == guess }?.let { r ->
                    _state.update { it.copy(suggestedRegion = r.name, suggestedRegionId = r.id) }
                }
            }
            refreshInstalled()
            loadActivePack()
            _state.update { it.copy(bootstrapped = true) }
        }
    }

    /** Install the pack for a region the user explicitly chose. Does nothing for unfinished regions. */
    fun installRegion(regionId: String) = viewModelScope.launch {
        _state.update { it.copy(installingRegion = regionId) }
        val ok = withContext(Dispatchers.IO) { runCatching { ow.packs.installBundled(regionId) }.getOrDefault(false) }
        if (!ok) {
            val name = _state.value.regions.firstOrNull { it.id == regionId }?.name ?: regionId
            _state.update {
                it.copy(installingRegion = null, message = getApplication<Application>().getString(R.string.region_not_ready, name))
            }
            return@launch
        }
        refreshInstalled()
        ow.packs.activeRegionId = regionId
        loadActivePack()
        _state.update { it.copy(installingRegion = null) }
    }

    fun loadReview(regionId: String, scientificName: String): ReviewStore.Review? = ow.reviews.get(regionId, scientificName)

    fun saveReview(regionId: String, scientificName: String, rating: Int, body: String) = viewModelScope.launch(Dispatchers.IO) {
        ow.reviews.save(regionId, scientificName, rating, body)
        val saved = getApplication<Application>().getString(R.string.review_saved)
        _state.update { it.copy(message = saved) }
    }

    fun setThemeMode(m: ThemeMode) {
        prefs.edit().putString("theme", m.name).apply()
        _state.update { it.copy(themeMode = m) }
    }

    /** Download every installed pack that has a newer version in the index. */
    fun updateAll() {
        val have = _state.value.installed.associateBy { it.regionId }
        _state.value.remote.mapNotNull { r -> have[r.regionId]?.let { PackManager.updateFor(it, r) } }.forEach { download(it) }
    }

    // ---------- navigation ----------
    fun go(s: Screen) = _state.update { it.copy(stack = it.stack + s) }
    fun back(): Boolean {
        if (_state.value.stack.size <= 1) return false
        _state.update { it.copy(stack = it.stack.dropLast(1)) }
        return true
    }
    /** Switch bottom-nav tab: resets the back stack to that tab. */
    fun tab(s: Screen) = _state.update { it.copy(stack = if (s == Screen.Home) listOf(Screen.Home) else listOf(Screen.Home, s)) }

    fun setExploreQuery(q: String) = _state.update { it.copy(exploreQuery = q) }
    fun setExploreFilter(f: RegionPack.ExploreFilter) = _state.update { it.copy(exploreFilter = f) }

    private fun replaceTop(s: Screen) = _state.update { it.copy(stack = it.stack.dropLast(1) + s) }

    fun acceptDisclaimer() {
        prefs.edit().putBoolean("disclaimer", true).apply()
        _state.update { it.copy(disclaimerAccepted = true) }
    }

    fun setSurvivalMode(on: Boolean) {
        prefs.edit().putBoolean("survival", on).apply()
        _state.update { it.copy(survivalMode = on) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ---------- packs ----------
    private suspend fun refreshInstalled() {
        val list = withContext(Dispatchers.IO) { ow.packs.installed() }
        _state.update { it.copy(installed = list) }
    }

    /** Called after location permission is granted (or denied — then nothing happens). */
    fun detectRegion() = viewModelScope.launch {
        val app = getApplication<Application>()
        val region = withContext(Dispatchers.IO) {
            val loc = Location.lastKnown(app) ?: return@withContext null
            RegionLocator.locate(Location.bundledRegions(app), loc.first, loc.second)
        } ?: return@launch
        _state.update { it.copy(suggestedRegion = region.name, suggestedRegionId = region.id) }
        // Auto-select an installed pack for the detected region if none is active yet.
        if (ow.packs.activeRegionId == null && _state.value.installed.any { it.regionId == region.id && it.compatible }) {
            useRegion(region.id)
        }
    }

    private suspend fun loadActivePack() {
        val installed = _state.value.installed.filter { it.compatible }
        val id = ow.packs.activeRegionId?.takeIf { a -> installed.any { it.regionId == a } }
            ?: installed.firstOrNull()?.regionId
        val loaded = withContext(Dispatchers.IO) {
            id?.let { runCatching { RegionPack(ow.packs.fileFor(it)) }.getOrNull() }?.also { runCatching { it.safety.size } }
        }
        pack?.close()
        pack = loaded
        identifier = loaded?.let { Identifier(it.index, it.species, it.params, it.projection) }
        activeEmbedder = null
        // Better recognition: BioCLIP 2 model + this pack's boost file -> match with those instead.
        var usingUpgrade = false
        if (loaded != null && ow.packs.betterRecognition && ow.packs.upgradeModel.exists()) {
            val boost = ow.packs.boostFile(loaded.regionId)
            if (boost.exists()) {
                val pair = withContext(Dispatchers.IO) {
                    val e = upgradeEmbedder ?: org.openwild.app.ml.Embedder.loadFileOrNull(ow.packs.upgradeModel)?.also { upgradeEmbedder = it }
                    val idx = loaded.boostIndex(boost)
                    if (e != null && idx != null && idx.dim == e.dim) e to idx else null
                }
                if (pair != null) {
                    activeEmbedder = pair.first
                    // Name matches (plants with no reference photo) are lifted onto the photo-match scale.
                    val prm = org.openwild.app.core.ConfidenceParams.calibrated(loaded.params, "bioclip2")
                        .copy(textProtoPenalty = -loaded.boostTextOffset)
                    identifier = Identifier(pair.second, loaded.species, prm, null)
                    usingUpgrade = true
                }
            }
        }
        _state.update { s -> s.copy(upgrade = s.upgrade.copy(on = ow.packs.betterRecognition, modelInstalled = ow.packs.upgradeModel.exists(), active = usingUpgrade)) }
        if (loaded != null) ow.packs.activeRegionId = loaded.regionId
        _state.update {
            it.copy(
                activeRegionName = loaded?.regionName,
                activeRegionId = loaded?.regionId,
                activePackVersion = loaded?.version ?: 0,
                activeSpeciesCount = loaded?.species?.size ?: 0,
                photoIdReady = (loaded?.index?.size ?: 0) > 0,
            )
        }
    }

    // ---------------- Better recognition (BioCLIP 2) ----------------

    /** What's available on GitHub for the upgrade (sizes, whether this region has a boost file). */
    fun refreshUpgrade() = viewModelScope.launch {
        if (!org.openwild.app.BuildConfig.ALLOW_NETWORK) return@launch
        val u = withContext(Dispatchers.IO) { runCatching { ow.packs.fetchUpgrade() }.getOrNull() } ?: return@launch
        upgradeInfo = u
        val b = u.boosts[_state.value.activeRegionId]
        _state.update { s ->
            s.copy(upgrade = s.upgrade.copy(
                modelMb = (u.modelSize / 1_000_000).toInt(), regionMb = ((b?.size ?: 0) / 1_000_000).toInt(),
                regionAvailable = b != null && (pack?.boostFits(b.packVersion) ?: false),
            ))
        }
    }

    /** Download the model (once) and the boost files for every installed region that has one, then switch on. */
    fun installUpgrade() = viewModelScope.launch {
        _state.update { it.copy(upgrade = it.upgrade.copy(progress = 0f, error = null)) }
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val u = upgradeInfo ?: ow.packs.fetchUpgrade().also { upgradeInfo = it }
                if (!ow.packs.upgradeModel.exists()) {
                    ow.packs.downloadVerified(u.modelUrl, u.modelSha256, u.modelSize, ow.packs.upgradeModel) { f ->
                        _state.update { s -> s.copy(upgrade = s.upgrade.copy(progress = f * 0.9f)) }
                    }
                }
                val regions = _state.value.installed.map { it.regionId }
                regions.mapNotNull { u.boosts[it] }.forEach { b ->
                    ow.packs.downloadVerified(b.url, b.sha256, b.size, ow.packs.boostFile(b.regionId)) { f ->
                        _state.update { s -> s.copy(upgrade = s.upgrade.copy(progress = 0.9f + f * 0.1f)) }
                    }
                }
                ow.packs.betterRecognition = true
            }
        }
        _state.update { it.copy(upgrade = it.upgrade.copy(progress = null, error = r.exceptionOrNull()?.message)) }
        loadActivePack()
    }

    fun setBetterRecognition(on: Boolean) = viewModelScope.launch {
        ow.packs.betterRecognition = on
        loadActivePack()
    }

    private val uiPrefs get() = getApplication<Application>().getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)

    fun setUpdateReminders(on: Boolean) {
        uiPrefs.edit().putBoolean("update_reminders", on).apply()
        _state.update { it.copy(updateReminders = on) }
    }

    /** Hide the home-screen offer for this version; a newer version is offered again. */
    fun dismissUpdate(regionId: String, version: Int) {
        uiPrefs.edit().putInt("dismissed_$regionId", version).apply()
        _state.update { it.copy(dismissedUpdates = it.dismissedUpdates + (regionId to version)) }
    }

    /** Better recognition files on this phone: (label, file). */
    fun upgradeFiles(): List<java.io.File> = ow.packs.upgradeDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    /** Delete one Better recognition file (the model deletes everything that depends on it). */
    fun deleteUpgradeFile(f: java.io.File) {
        if (f.name.endsWith(".tflite")) { removeUpgrade(); return }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { f.delete() }
            loadActivePack()
        }
    }

    fun removeUpgrade() = viewModelScope.launch {
        upgradeEmbedder?.close()
        upgradeEmbedder = null
        withContext(Dispatchers.IO) { ow.packs.removeUpgrade() }
        loadActivePack()
    }

    fun useRegion(regionId: String) = viewModelScope.launch {
        ow.packs.activeRegionId = regionId
        loadActivePack()
    }

    /** Install a region file chosen by the user. */
    fun importPack(uri: Uri) = viewModelScope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { ow.packs.importPack(uri) } }
        refreshInstalled()
        r.onSuccess { useRegion(it) }
        _state.update { it.copy(message = r.exceptionOrNull()?.message) }
    }

    fun refreshRemote() = viewModelScope.launch {
        if (!org.openwild.app.BuildConfig.ALLOW_NETWORK) return@launch
        _state.update { it.copy(remoteLoading = true, remoteError = false) }
        // First launch can race the network coming up: try a few times before giving up.
        var r: Result<List<PackManager.RemotePack>> = Result.failure(IllegalStateException())
        for (attempt in 0 until 3) {
            r = withContext(Dispatchers.IO) { runCatching { ow.packs.fetchIndex() } }
            if (r.isSuccess) break
            kotlinx.coroutines.delay(2000L * (attempt + 1))
        }
        _state.update { it.copy(remote = r.getOrDefault(it.remote), remoteError = r.isFailure, remoteLoading = false) }
    }

    fun download(p: PackManager.RemotePack) = viewModelScope.launch {
        _state.update { it.copy(downloading = it.downloading + (p.regionId to 0f)) }
        val r = withContext(Dispatchers.IO) {
            runCatching {
                ow.packs.download(p) { f -> _state.update { s -> s.copy(downloading = s.downloading + (p.regionId to f)) } }
            }
        }
        _state.update { it.copy(downloading = it.downloading - p.regionId, message = r.exceptionOrNull()?.message) }
        refreshInstalled()
        if (r.isSuccess && (pack == null || pack?.regionId == p.regionId)) useRegion(p.regionId)
    }

    fun deletePack(regionId: String) = viewModelScope.launch {
        if (pack?.regionId == regionId) {
            pack?.close(); pack = null; identifier = null
        }
        withContext(Dispatchers.IO) { ow.packs.delete(regionId) }
        refreshInstalled()
        loadActivePack()
    }

    // ---------- capture & identify ----------
    fun setFilter(f: KingdomFilter) = _state.update { it.copy(filter = f) }

    fun startCapture() {
        _state.update { it.copy(photos = emptyList(), result = null) }
        go(Screen.Capture)
    }

    fun addPhoto(uri: Uri) = viewModelScope.launch {
        if (_state.value.photos.size >= MAX_PHOTOS) return@launch
        val bmp = withContext(Dispatchers.IO) { ImageUtils.load(getApplication<Application>(), uri) }
        if (bmp == null) {
            _state.update { it.copy(message = getApplication<Application>().getString(R.string.photo_unreadable)) }
            return@launch
        }
        addBitmap(bmp)
    }

    /** A JPEG the camera wrote into app cache. Read from the path; file:// Uris are not reliable. */
    fun addCapturedFile(file: File) = viewModelScope.launch {
        if (_state.value.photos.size >= MAX_PHOTOS) return@launch
        val bmp = withContext(Dispatchers.IO) { ImageUtils.loadFile(file.absolutePath) }
        if (bmp == null) {
            _state.update { it.copy(message = getApplication<Application>().getString(R.string.photo_unreadable)) }
            return@launch
        }
        addBitmap(bmp)
    }

    private fun addBitmap(bmp: android.graphics.Bitmap) {
        _state.update { s ->
            val defaultPart = defaultPartFor(s.filter, s.photos.size)
            s.copy(photos = (s.photos + Photo(bmp, defaultPart)).take(MAX_PHOTOS))
        }
    }

    fun setPart(i: Int, part: PhotoPart) =
        _state.update { s -> s.copy(photos = s.photos.mapIndexed { j, p -> if (j == i) p.copy(part = part) else p }) }

    fun removePhoto(i: Int) = _state.update { s -> s.copy(photos = s.photos.filterIndexed { j, _ -> j != i }) }

    fun identify() = viewModelScope.launch {
        online.reset()
        val s = _state.value
        val emb = activeEmbedder ?: ow.embedder
        val id = identifier
        val p = pack
        val app = getApplication<Application>()
        if (s.photos.isEmpty()) return@launch
        if (emb == null) {
            _state.update { it.copy(message = app.getString(R.string.identify_needs_model)) }
            return@launch
        }
        if (id == null || p == null) {
            _state.update { it.copy(message = app.getString(R.string.identify_needs_pack)) }
            return@launch
        }
        if (p.index.size == 0) {
            _state.update { it.copy(message = app.getString(R.string.identify_no_fingerprints)) }
            return@launch
        }
        _state.update { it.copy(busy = true) }
        val res = withContext(Dispatchers.Default) {
            val embeddings = s.photos.map { emb.embed(it.bitmap) }
            val month = Calendar.getInstance().get(Calendar.MONTH) + 1
            val r = id.identify(embeddings, s.filter, month)
            val deadly = SafetyPolicy.deadlyCandidates(r) { p.content(it)?.isDeadly == true }
            // Keep a long ranked list so the user can browse down to the right plant if the first is wrong.
            val cands = r.order.take(MAX_MATCHES).map { Candidate(it, r.probs[it], p.content(it)?.isDeadly == true) }
            IdResult(r, cands, deadly, s.photos.size, s.photos.any { it.part.isUnderside })
        }
        _state.update { it.copy(busy = false, result = res) }
        if (_state.value.screen == Screen.Results) replaceTop(Screen.Results) else go(Screen.Results)
    }

    /** Back from results to add another photo, keeping the existing ones. */
    fun addMorePhotos() {
        back()
        if (_state.value.screen != Screen.Capture) go(Screen.Capture)
    }

    /**
     * The user says the photo shows [chosenIndex] (found at [rank] in the list, 0 if found by search).
     * Saved on the phone only. Returns the stored correction, ready for an optional user-tapped send.
     */
    fun recordCorrection(chosenIndex: Int, rank: Int): ReviewStore.Correction? {
        val p = pack ?: return null
        val res = _state.value.result ?: return null
        val names = p.species.scientificNames
        val top = res.candidates.take(5).joinToString("; ") { "${names[it.index]} ${(it.prob * 100).toInt()}%" }
        val c = ReviewStore.Correction(
            id = 0, regionId = p.regionId, packVersion = p.version, modelId = p.modelId,
            predicted = names[res.result.top], chosen = names[chosenIndex], chosenRank = rank,
            topMatches = top, photoCount = res.photoCount, createdAt = System.currentTimeMillis(), sent = false,
        )
        val id = ow.reviews.saveCorrection(c)
        _state.update { it.copy(message = getApplication<Application>().getString(R.string.correction_saved)) }
        return c.copy(id = id)
    }

    fun markCorrectionSent(id: Long) = viewModelScope.launch(Dispatchers.IO) { ow.reviews.markSent(id) }

    companion object {
        const val MAX_MATCHES = 30
        const val MAX_PHOTOS = 5

        fun defaultPartFor(filter: KingdomFilter, n: Int): PhotoPart = when (filter) {
            KingdomFilter.FUNGI -> listOf(PhotoPart.CAP, PhotoPart.UNDERSIDE, PhotoPart.STEM_BASE).getOrElse(n) { PhotoPart.CAP }
            else -> listOf(PhotoPart.WHOLE, PhotoPart.LEAF, PhotoPart.FLOWER_FRUIT).getOrElse(n) { PhotoPart.WHOLE }
        }
    }

    override fun onCleared() {
        pack?.close()
        super.onCleared()
    }
}

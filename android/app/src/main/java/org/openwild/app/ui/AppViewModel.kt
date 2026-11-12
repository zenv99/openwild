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
) {
    val screen: Screen get() = stack.last()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

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

    init {
        viewModelScope.launch {
            val missing = withContext(Dispatchers.Default) { ow.embedder == null }
            val regions = withContext(Dispatchers.IO) { Location.bundledRegions(getApplication<Application>()) }
            val bundled = withContext(Dispatchers.IO) { ow.packs.bundledCatalog() }
            _state.update { it.copy(modelMissing = missing, regions = regions, bundled = bundled) }
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
        val have = _state.value.installed.associate { it.regionId to it.version }
        _state.value.remote.filter { r -> have[r.regionId]?.let { it < r.version } == true }.forEach { download(it) }
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
        val r = withContext(Dispatchers.IO) { runCatching { ow.packs.fetchIndex() } }
        _state.update { it.copy(remote = r.getOrDefault(it.remote), remoteError = r.isFailure) }
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
        val s = _state.value
        val emb = ow.embedder
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
            val cands = r.order.take(5).map { Candidate(it, r.probs[it], p.content(it)?.isDeadly == true) }
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

    companion object {
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

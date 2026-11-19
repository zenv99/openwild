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
import org.openwild.app.core.RegionFusion
import org.openwild.app.core.RegionInfo
import org.openwild.app.core.RegionLibrary
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
    /** Fingerprints of the photos just identified, same order as the photos. Kept for a wrong-ID report. */
    val embeddings: List<FloatArray> = emptyList(),
    /** Model that produced this result (standard or bioclip). Frozen so a later switch does not rewrite the report. */
    val brain: String = org.openwild.app.core.BrainList.STANDARD,
    /** What the person had selected. Differs from [brain] when BioCLIP was selected but not on the phone. */
    val brainRequested: String = org.openwild.app.core.BrainList.STANDARD,
    /** several_crops or one_crop. */
    val scanDetail: String = org.openwild.app.core.BrainList.SCAN_SEVERAL,
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
    /** On-device photo model. Off stops photo identification; search and browse stay available. */
    val brainOn: Boolean = true,
    /** Size of the model file inside the app. The install keeps this even when the brain is off. */
    val brainBytes: Long = 0,
    val modelId: String = "",
    /** standard or bioclip. Scan detail is separate. */
    val brainModel: String = org.openwild.app.pack.PackManager.BRAIN_STANDARD,
    /** Several looks at each photo. Off is one look. */
    val scanCareful: Boolean = true,
    /** Region packs included in Identify. Explore shows [activeRegionId] among these. */
    val enabledRegionIds: Set<String> = emptySet(),
    val finerRegionId: String? = null,
    val finerRegionName: String? = null,
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
    /** The boost file for the open pack is already on the phone. */
    val boostInstalled: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    /** Opt-in online check (cloud identification); reset for every new identification. */
    val online = OnlineCheck(app, viewModelScope)

    /** Opt-in Pl@ntNet check. A photo is sent only when the person taps the button on a result. */
    val plantNet = PlantNet(app, viewModelScope)


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
    private var openPacks: List<RegionPack> = emptyList()
    private var libraries: List<RegionLibrary> = emptyList()
    /** BioCLIP 2 embedder when "Better recognition" is active for this pack; otherwise the built-in model. */
    private var activeEmbedder: org.openwild.app.ml.Embedder? = null
    private var upgradeEmbedder: org.openwild.app.ml.Embedder? = null
    private var upgradeInfo: PackManager.Upgrade? = null

    init {
        viewModelScope.launch {
            val missing = withContext(Dispatchers.Default) { ow.embedder == null }
            val regions = withContext(Dispatchers.IO) { Location.bundledRegions(getApplication<Application>()) }
            val bundled = withContext(Dispatchers.IO) { ow.packs.bundledCatalog() }
            val brainBytes = withContext(Dispatchers.IO) { ow.packs.brainAssetBytes() }
            val ui = getApplication<Application>().getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
            val dismissed = ui.all.filterKeys { it.startsWith("dismissed_") }
                .mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix("dismissed_") to it } }.toMap()
            _state.update {
                it.copy(
                    modelMissing = missing,
                    regions = regions,
                    bundled = bundled,
                    updateReminders = ui.getBoolean("update_reminders", true),
                    dismissedUpdates = dismissed,
                    brainOn = ow.packs.brainOn,
                    brainBytes = brainBytes,
                    brainModel = ow.packs.brainModel,
                    scanCareful = ow.packs.scanCareful,
                    modelId = org.openwild.app.ml.Embedder.modelId(getApplication()),
                )
            }
            // First-run suggestion is a country. Asia/Makassar covers Bali, Sulawesi and Nusa Tenggara,
            // so the suggestion is Indonesia, not Bali.
            if (_state.value.suggestedRegionId == null) {
                val tz = java.util.TimeZone.getDefault().id
                val country = java.util.Locale.getDefault().country.uppercase()
                val guess = if (tz == "Asia/Jakarta" || tz == "Asia/Makassar" || tz == "Asia/Jayapura") "ID" else country
                val match = regions.firstOrNull { it.id == guess && !it.hidden }
                    ?: regions.firstOrNull { it.id == country && !it.hidden }
                if (match != null) {
                    _state.update { it.copy(suggestedRegion = match.name, suggestedRegionId = match.id) }
                }
            }
            refreshInstalled()
            loadActivePack()
            _state.update { it.copy(bootstrapped = true) }
            withContext(Dispatchers.IO) { runCatching { ReportOutbox.flush(getApplication()) } }
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
        val enabled = ow.packs.enabledRegionIds().toMutableSet()
        enabled += regionId
        ow.packs.setEnabledRegions(enabled)
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
            RegionLocator.suggest(Location.bundledRegions(app), loc.first, loc.second)
        } ?: return@launch
        val (country, _) = region
        _state.update { it.copy(suggestedRegion = country.name, suggestedRegionId = country.id) }
        // Use the country pack when it is already on the phone. A hidden pack such as Bali keeps
        // working until that country download exists.
        if (ow.packs.activeRegionId == null) {
            val have = _state.value.installed.filter { it.compatible }.map { it.regionId }.toSet()
            if (country.id in have) {
                if (!ow.packs.hasEnabledPreference()) ow.packs.setEnabledRegions(setOf(country.id))
                ow.packs.activeRegionId = country.id
                loadActivePack()
            }
        }
    }

    private suspend fun loadActivePack() {
        val installed = _state.value.installed.filter { it.compatible }
        if (!ow.packs.hasEnabledPreference()) {
            val seed = ow.packs.activeRegionId?.takeIf { id -> installed.any { it.regionId == id } }
                ?: installed.firstOrNull()?.regionId
            if (seed != null) ow.packs.setEnabledRegions(setOf(seed))
        }
        val enabled = ow.packs.enabledRegionIds().filter { id -> installed.any { it.regionId == id } }
        var focusId = ow.packs.activeRegionId?.takeIf { it in enabled } ?: enabled.firstOrNull()
        val focusInfo = _state.value.regions.firstOrNull { it.id == focusId }
        if (focusInfo?.hidden == true && focusInfo.parentId != null && focusInfo.parentId in enabled) {
            focusId = focusInfo.parentId
            ow.packs.activeRegionId = focusId
        }
        val loaded = withContext(Dispatchers.IO) {
            enabled.mapNotNull { id ->
                runCatching { RegionPack(ow.packs.fileFor(id)) }.getOrNull()?.also { runCatching { it.safety.size } }
            }
        }
        openPacks.forEach { runCatching { it.close() } }
        openPacks = loaded
        val focus = loaded.firstOrNull { it.regionId == focusId } ?: loaded.firstOrNull()
        pack = focus
        libraries = loaded.map { RegionLibrary(it.regionId, it.species, it.index, it.params, it.projection) }
        identifier = focus?.let { Identifier(it.index, it.species, it.params, it.projection) }
        activeEmbedder = null
        // Better recognition uses a different fingerprint space, so it stays on the Explore region
        // rather than being mixed with the built-in fingerprints.
        var usingUpgrade = false
        if (focus != null && ow.packs.betterRecognition && ow.packs.upgradeModel.exists()) {
            val boost = ow.packs.boostFile(focus.regionId)
            if (boost.exists()) {
                val pair = withContext(Dispatchers.IO) {
                    val e = upgradeEmbedder ?: org.openwild.app.ml.Embedder.loadFileOrNull(ow.packs.upgradeModel)?.also { upgradeEmbedder = it }
                    val idx = focus.boostIndex(boost)
                    if (e != null && idx != null && idx.dim == e.dim) e to idx else null
                }
                if (pair != null) {
                    activeEmbedder = pair.first
                    val prm = org.openwild.app.core.ConfidenceParams.calibrated(focus.params, "bioclip2")
                        .copy(textProtoPenalty = -focus.boostTextOffset)
                    identifier = Identifier(pair.second, focus.species, prm, null)
                    libraries = emptyList()
                    usingUpgrade = true
                }
            }
        }
        val shownModel = if (usingUpgrade) "bioclip2-vitl14-224-v1" else org.openwild.app.ml.Embedder.modelId(getApplication())
        _state.update { s ->
            s.copy(
                brainModel = ow.packs.brainModel,
                scanCareful = ow.packs.scanCareful,
                modelId = shownModel,
                upgrade = s.upgrade.copy(
                    on = ow.packs.betterRecognition,
                    modelInstalled = ow.packs.upgradeModel.exists(),
                    active = usingUpgrade,
                    boostInstalled = focus != null && ow.packs.boostFile(focus.regionId).exists(),
                ),
            )
        }
        if (focus != null) ow.packs.activeRegionId = focus.regionId
        _state.update {
            it.copy(
                activeRegionName = focus?.regionName,
                activeRegionId = focus?.regionId,
                activePackVersion = focus?.version ?: 0,
                activeSpeciesCount = focus?.species?.size ?: 0,
                photoIdReady = loaded.any { p -> p.index.size > 0 },
                enabledRegionIds = enabled.toSet(),
                brainOn = ow.packs.brainOn,
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
                ow.packs.brainModel = org.openwild.app.pack.PackManager.BRAIN_BIOCLIP
                ow.packs.betterRecognition = true
            }
        }
        if (r.isFailure) {
            ow.packs.brainModel = org.openwild.app.pack.PackManager.BRAIN_STANDARD
            ow.packs.betterRecognition = false
        }
        _state.update { it.copy(upgrade = it.upgrade.copy(progress = null, error = r.exceptionOrNull()?.message)) }
        loadActivePack()
    }

    fun selectStandardBrain() = viewModelScope.launch {
        ow.packs.brainModel = org.openwild.app.pack.PackManager.BRAIN_STANDARD
        ow.packs.betterRecognition = false
        _state.update { it.copy(brainModel = org.openwild.app.pack.PackManager.BRAIN_STANDARD) }
        loadActivePack()
    }

    /** Select BioCLIP 2 when this pack can use it. Starts the download if the files are not here yet. */
    fun selectBioclip() {
        val offered = _state.value.upgrade.regionAvailable || _state.value.upgrade.boostInstalled || _state.value.upgrade.progress != null
        if (!offered) return
        val ready = _state.value.upgrade.modelInstalled && _state.value.upgrade.boostInstalled
        ow.packs.brainModel = org.openwild.app.pack.PackManager.BRAIN_BIOCLIP
        _state.update { it.copy(brainModel = org.openwild.app.pack.PackManager.BRAIN_BIOCLIP) }
        if (ready) {
            viewModelScope.launch {
                ow.packs.betterRecognition = true
                loadActivePack()
            }
        } else {
            installUpgrade()
        }
    }

    fun setScanCareful(careful: Boolean) {
        ow.packs.scanCareful = careful
        _state.update { it.copy(scanCareful = careful) }
    }

    /** Names from every installed pack, for the wrong-ID sheet. Offline. */
    fun suggestNames(query: String): List<org.openwild.app.core.NameSuggest.Hit> {
        val out = ArrayList<org.openwild.app.core.NameSuggest.Hit>()
        val seen = HashSet<String>()
        for (p in openPacks) {
            for (hit in org.openwild.app.core.NameSuggest.matches(query, p.species.scientificNames, p.species.commonNames)) {
                if (seen.add(hit.scientific.lowercase())) out += hit
                if (out.size >= 6) return out
            }
        }
        return out
    }

    fun setBetterRecognition(on: Boolean) {
        if (on) selectBioclip() else selectStandardBrain()
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
        val enabled = ow.packs.enabledRegionIds().toMutableSet()
        enabled += regionId
        ow.packs.setEnabledRegions(enabled)
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
        if (r.isSuccess) {
            val cur = pack?.regionId
            val curInfo = _state.value.regions.firstOrNull { it.id == cur }
            val replacesLegacy = curInfo?.hidden == true && curInfo.parentId == p.regionId
            if (cur == null || cur == p.regionId || replacesLegacy) useRegion(p.regionId)
        }
    }

    fun deletePack(regionId: String) = viewModelScope.launch {
        openPacks.forEach { runCatching { it.close() } }
        openPacks = emptyList()
        pack = null
        identifier = null
        libraries = emptyList()
        withContext(Dispatchers.IO) { ow.packs.delete(regionId) }
        refreshInstalled()
        loadActivePack()
    }

    /** Include or leave out a downloaded region for Identify. Turning one on also shows it in Explore. */
    fun setRegionEnabled(regionId: String, on: Boolean) = viewModelScope.launch {
        if (on && _state.value.installed.none { it.regionId == regionId && it.compatible }) return@launch
        val next = ow.packs.enabledRegionIds().toMutableSet()
        if (on) {
            next += regionId
            ow.packs.activeRegionId = regionId
        } else {
            next -= regionId
            if (ow.packs.activeRegionId == regionId) ow.packs.activeRegionId = next.firstOrNull()
        }
        ow.packs.setEnabledRegions(next)
        loadActivePack()
    }

    fun setBrainOn(on: Boolean) {
        ow.packs.brainOn = on
        _state.update { it.copy(brainOn = on) }
    }

    /**
     * Turn the photo model off and delete a downloaded "Better recognition" model if one is stored.
     * The model built into the app cannot be removed from the install; turning it back on uses that copy.
     */
    /** Delete BioCLIP 2 from this phone. The built-in model stays, and photo identification stays on. */
    fun removeDownloadedModel() = viewModelScope.launch {
        upgradeEmbedder?.close()
        upgradeEmbedder = null
        activeEmbedder = null
        withContext(Dispatchers.IO) { ow.packs.removeUpgrade() }
        loadActivePack()
    }

    fun deleteBrain() = viewModelScope.launch {
        ow.packs.brainOn = false
        upgradeEmbedder?.close()
        upgradeEmbedder = null
        activeEmbedder = null
        withContext(Dispatchers.IO) { ow.packs.removeUpgrade() }
        val note = getApplication<Application>().getString(R.string.brain_disabled_note)
        _state.update { it.copy(brainOn = false, message = note) }
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
        plantNet.reset()
        val s = _state.value
        val emb = activeEmbedder ?: ow.embedder
        val id = identifier
        val p = pack
        val app = getApplication<Application>()
        if (s.photos.isEmpty()) return@launch
        if (!s.brainOn || emb == null) {
            _state.update {
                it.copy(message = app.getString(if (!s.brainOn) R.string.brain_off_search else R.string.identify_needs_model))
            }
            tab(Screen.Explore)
            return@launch
        }
        val libs = libraries
        if (libs.isEmpty() && (id == null || p == null)) {
            _state.update { it.copy(message = app.getString(R.string.identify_needs_pack)) }
            return@launch
        }
        if (libs.isEmpty() && p != null && p.index.size == 0) {
            _state.update { it.copy(message = app.getString(R.string.identify_no_fingerprints)) }
            return@launch
        }
        if (libs.isNotEmpty() && libs.none { it.index.size > 0 }) {
            _state.update { it.copy(message = app.getString(R.string.identify_no_fingerprints)) }
            return@launch
        }
        _state.update { it.copy(busy = true) }
        val careful = s.scanCareful
        val requested = s.brainModel
        val ran = org.openwild.app.core.BrainList.ran(requested, s.upgrade.active)
        val scan = org.openwild.app.core.BrainList.scanDetail(careful)
        val res = withContext(Dispatchers.Default) {
            val embeddings = s.photos.map { emb.embed(it.bitmap, careful) }
            val month = Calendar.getInstance().get(Calendar.MONTH) + 1
            if (libs.isNotEmpty()) {
                val fused = RegionFusion.identify(libs, embeddings, s.filter, month, p?.regionId)
                if (fused == null) return@withContext null
                val display = openPacks.firstOrNull { it.regionId == fused.displayRegionId } ?: p ?: return@withContext null
                val r = fused.result
                val deadly = SafetyPolicy.deadlyCandidates(r) { display.content(it)?.isDeadly == true }
                val cands = r.order.take(MAX_MATCHES).map { Candidate(it, r.probs[it], display.content(it)?.isDeadly == true) }
                display to IdResult(r, cands, deadly, s.photos.size, s.photos.any { it.part.isUnderside }, embeddings, ran, requested, scan)
            } else {
                val shown = p ?: return@withContext null
                val r = id!!.identify(embeddings, s.filter, month)
                val deadly = SafetyPolicy.deadlyCandidates(r) { shown.content(it)?.isDeadly == true }
                val cands = r.order.take(MAX_MATCHES).map { Candidate(it, r.probs[it], shown.content(it)?.isDeadly == true) }
                shown to IdResult(r, cands, deadly, s.photos.size, s.photos.any { it.part.isUnderside }, embeddings, ran, requested, scan)
            }
        }
        if (res == null) {
            _state.update { it.copy(busy = false, message = app.getString(R.string.identify_no_fingerprints)) }
            return@launch
        }
        val (display, result) = res
        pack = display
        ow.packs.activeRegionId = display.regionId
        _state.update {
            it.copy(
                busy = false,
                result = result,
                activeRegionId = display.regionId,
                activeRegionName = display.regionName,
                activeSpeciesCount = display.species.size,
                activePackVersion = display.version,
            )
        }
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
            brain = res.brain, brainRequested = res.brainRequested, scanDetail = res.scanDetail,
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
        openPacks.forEach { runCatching { it.close() } }
        openPacks = emptyList()
        pack = null
        super.onCleared()
    }
}

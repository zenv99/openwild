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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openwild.app.OpenWildApp
import org.openwild.app.R
import org.openwild.app.core.ConfidenceResult
import org.openwild.app.core.FieldNote
import org.openwild.app.core.Identifier
import org.openwild.app.core.KingdomFilter
import org.openwild.app.core.PackUpdates
import org.openwild.app.core.PackVersion
import org.openwild.app.core.RegionChoice
import org.openwild.app.core.RegionInfo
import org.openwild.app.core.RegionLocator
import org.openwild.app.core.SafetyPolicy
import org.openwild.app.core.Tier
import org.openwild.app.data.FieldNotes
import org.openwild.app.ml.ImageUtils
import org.openwild.app.pack.Location
import org.openwild.app.pack.Networks
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

data class UpdateOffer(
    val packs: List<PackManager.RemotePack>,
    val bytes: Long,
    val confirmMobileData: Boolean,
)

data class UiState(
    val ready: Boolean = false,
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
    val updateOffer: UpdateOffer? = null,
    val fieldNote: FieldNote? = null,
) {
    val screen: Screen get() = stack.last()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ow = app as OpenWildApp
    private val prefs = app.getSharedPreferences("openwild", 0)
    private val fieldNotes = FieldNotes(app)
    private val noteLock = Mutex()
    private var updatesDismissed = false
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
            _state.update { it.copy(modelMissing = missing) }
            withContext(Dispatchers.IO) { runCatching { ow.packs.installBundled() } }
            val regions = withContext(Dispatchers.IO) { Location.bundledRegions(getApplication<Application>()) }
            _state.update { it.copy(regions = regions) }
            refreshInstalled()
            loadActivePack()
            _state.update { it.copy(ready = true) }
            checkForPackUpdates()
        }
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
        checkForPackUpdates()
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
        // A suggestion only. The user still has to tap the region.
        _state.update { it.copy(suggestedRegion = region.name, suggestedRegionId = region.id) }
    }

    private suspend fun loadActivePack() {
        val installed = _state.value.installed.filter { it.compatible }
        val id = RegionChoice.activeId(ow.packs.activeRegionId, installed.map { it.regionId })
        if (ow.packs.activeRegionId != null && id == null) ow.packs.activeRegionId = null
        val loaded = withContext(Dispatchers.IO) {
            id?.let { runCatching { RegionPack(ow.packs.fileFor(it)) }.getOrNull() }
        }
        pack?.close()
        pack = loaded
        identifier = loaded?.let { Identifier(it.index, it.species, it.params, it.projection) }
        _state.update {
            it.copy(
                activeRegionName = loaded?.regionName,
                activeSpeciesCount = loaded?.species?.size ?: 0,
                photoIdReady = (loaded?.index?.size ?: 0) > 0,
            )
        }
    }

    fun useRegion(regionId: String) = viewModelScope.launch {
        ow.packs.activeRegionId = regionId
        loadActivePack()
        checkForPackUpdates()
    }

    /** Install a region file chosen by the user. */
    fun importPack(uri: Uri) = viewModelScope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { ow.packs.importPack(uri) } }
        refreshInstalled()
        r.onSuccess { useRegion(it) }
        _state.update { it.copy(message = r.exceptionOrNull()?.message) }
    }

    /** Fetch the public pack index and, once a region is chosen, offer updates. Never downloads by itself. */
    fun checkForPackUpdates() = viewModelScope.launch {
        if (!org.openwild.app.BuildConfig.ALLOW_NETWORK) return@launch
        val fetched = withContext(Dispatchers.IO) { runCatching { ow.packs.fetchIndex() } }
        val remote = fetched.getOrNull()
        _state.update { it.copy(remote = remote ?: it.remote, remoteError = fetched.isFailure && it.remote.isEmpty() && remote == null) }
        if (remote == null || updatesDismissed || ow.packs.activeRegionId == null || !_state.value.disclaimerAccepted) return@launch
        val updates = PackUpdates.pending(
            _state.value.installed.associate { it.regionId to it.version },
            remote.map { PackVersion(it.regionId, it.version, it.size) },
        )
        if (updates.isEmpty()) {
            _state.update { it.copy(updateOffer = null) }
            return@launch
        }
        val metered = withContext(Dispatchers.Default) { Networks.isMetered(getApplication()) }
        val bytes = updates.sumOf { it.bytes }
        val byId = remote.associateBy { it.regionId }
        _state.update {
            it.copy(
                updateOffer = UpdateOffer(
                    packs = updates.mapNotNull { u -> byId[u.regionId] },
                    bytes = bytes,
                    confirmMobileData = PackUpdates.confirmMobileData(bytes, metered),
                ),
            )
        }
    }

    fun acceptPackUpdates() {
        val offer = _state.value.updateOffer ?: return
        _state.update { it.copy(updateOffer = null) }
        offer.packs.forEach { download(it) }
    }

    fun dismissPackUpdates() {
        updatesDismissed = true
        _state.update { it.copy(updateOffer = null) }
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

    // ---------- on-device notes (never uploaded unless the user taps submit) ----------
    fun loadFieldNote(name: String) = viewModelScope.launch(Dispatchers.IO) {
        val note = fieldNotes.get(name)
        _state.update { s ->
            val cur = s.fieldNote
            val merged = if (cur?.scientificName == name) {
                note.copy(
                    text = cur.text.ifEmpty { note.text },
                    rating = if (cur.rating != 0) cur.rating else note.rating,
                    photos = (cur.photos + note.photos).distinct(),
                )
            } else {
                note
            }
            s.copy(fieldNote = merged)
        }
    }

    fun setFieldRating(name: String, rating: Int) {
        val cur = _state.value.fieldNote?.takeIf { it.scientificName == name } ?: FieldNote(name)
        _state.update { it.copy(fieldNote = cur.copy(rating = FieldNote.clampRating(rating))) }
        persistCurrentNote(name)
    }

    fun setFieldText(name: String, text: String) {
        val cur = _state.value.fieldNote?.takeIf { it.scientificName == name } ?: FieldNote(name)
        _state.update { it.copy(fieldNote = cur.copy(text = text)) }
        persistCurrentNote(name)
    }

    fun addFieldPhoto(name: String, uri: Uri) = viewModelScope.launch {
        noteLock.withLock {
            val bmp = withContext(Dispatchers.IO) { ImageUtils.load(getApplication(), uri, maxSide = 1600) } ?: return@withLock
            val saved = withContext(Dispatchers.IO) { fieldNotes.addPhoto(name, bmp) }
            if (!bmp.isRecycled) bmp.recycle()
            mergeSavedNote(name, saved)
        }
    }

    fun addFieldPhotoBitmap(name: String, bitmap: android.graphics.Bitmap) = viewModelScope.launch {
        noteLock.withLock {
            val saved = withContext(Dispatchers.IO) { fieldNotes.addPhoto(name, bitmap) }
            if (!bitmap.isRecycled) bitmap.recycle()
            mergeSavedNote(name, saved)
        }
    }

    fun removeFieldPhoto(name: String, fileName: String) = viewModelScope.launch {
        noteLock.withLock {
            val saved = withContext(Dispatchers.IO) { fieldNotes.removePhoto(name, fileName) }
            mergeSavedNote(name, saved)
        }
    }

    private fun persistCurrentNote(name: String) {
        viewModelScope.launch {
            noteLock.withLock {
                val latest = _state.value.fieldNote?.takeIf { it.scientificName == name } ?: return@withLock
                withContext(Dispatchers.IO) { fieldNotes.save(latest) }
            }
        }
    }

    private fun mergeSavedNote(name: String, saved: FieldNote) {
        val typed = _state.value.fieldNote?.takeIf { it.scientificName == name }
        val merged = if (typed != null && typed.text != saved.text) saved.copy(text = typed.text, rating = typed.rating) else saved
        if (typed != null && (typed.text != saved.text || typed.rating != saved.rating)) {
            viewModelScope.launch {
                noteLock.withLock { withContext(Dispatchers.IO) { fieldNotes.save(merged) } }
            }
        }
        _state.update { it.copy(fieldNote = merged) }
    }

    fun fieldPhotoFile(fileName: String) = fieldNotes.photoFile(fileName)

    // ---------- capture & identify ----------
    fun setFilter(f: KingdomFilter) = _state.update { it.copy(filter = f) }

    fun startCapture() {
        _state.update { it.copy(photos = emptyList(), result = null) }
        go(Screen.Capture)
    }

    fun addPhoto(uri: Uri) = viewModelScope.launch {
        if (_state.value.photos.size >= MAX_PHOTOS) return@launch
        val bmp = withContext(Dispatchers.IO) { ImageUtils.load(getApplication<Application>(), uri) } ?: return@launch
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
        if (s.photos.isEmpty() || emb == null || id == null || p == null || p.index.size == 0) return@launch
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

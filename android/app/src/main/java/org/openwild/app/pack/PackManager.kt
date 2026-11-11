package org.openwild.app.pack

import android.content.Context
import org.json.JSONObject
import org.openwild.app.BuildConfig
import org.openwild.app.ml.Embedder
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads, verifies and stores region packs in app-private storage (filesDir/packs). */
class PackManager(private val context: Context) {

    data class RemotePack(
        val regionId: String,
        val name: String,
        val version: Int,
        val url: String,
        val sha256: String,
        val size: Long,
        val speciesCount: Int,
    )

    data class InstalledPack(val regionId: String, val name: String, val version: Int, val file: File, val compatible: Boolean)

    private val dir = File(context.filesDir, "packs").apply { mkdirs() }
    private val indexCache = File(context.filesDir, "index.json")
    private val prefs = context.getSharedPreferences("openwild", Context.MODE_PRIVATE)

    var activeRegionId: String?
        get() = prefs.getString("active_region", null)
        set(v) = prefs.edit().putString("active_region", v).apply()

    fun installed(): List<InstalledPack> =
        dir.listFiles { f -> f.extension == "owpack" }.orEmpty().mapNotNull { f ->
            val meta = RegionPack.readMeta(f) ?: return@mapNotNull null
            InstalledPack(
                regionId = meta["region_id"] ?: f.nameWithoutExtension,
                name = meta["region_name"] ?: f.nameWithoutExtension,
                version = meta["pack_version"]?.toIntOrNull() ?: 0,
                file = f,
                // Guide-only packs (no photo fingerprints) work with any model.
                compatible = meta["model_id"] == Embedder.modelId(context) || RegionPack.prototypeCount(f) == 0,
            )
        }.sortedBy { it.name }

    fun fileFor(regionId: String) = File(dir, "$regionId.owpack")

    /**
     * Copy packs shipped inside the APK (assets/packs) into storage on first launch, or when the
     * bundled version is newer than the installed one. Lets the field guide work with no internet at all.
     */
    fun installBundled() {
        // Bundled packs only change when the app is updated: copy them once per app version, not on every launch.
        val installStamp = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (installStamp != 0L && prefs.getLong("bundled_for_install", -1L) == installStamp) return
        val names = context.assets.list("packs").orEmpty().filter { it.endsWith(".owpack") }
        for (name in names) {
            val tmp = File(dir, "$name.bundled")
            context.assets.open("packs/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val meta = RegionPack.readMeta(tmp)
            val region = meta?.get("region_id")
            if (meta == null || region == null) {
                tmp.delete()
                continue
            }
            val bundledVersion = meta["pack_version"]?.toIntOrNull() ?: 0
            val dest = fileFor(region)
            val installedVersion = if (dest.exists()) RegionPack.readMeta(dest)?.get("pack_version")?.toIntOrNull() ?: -1 else -1
            if (bundledVersion > installedVersion) {
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            } else {
                tmp.delete()
            }
        }
        prefs.edit().putLong("bundled_for_install", installStamp).apply()
    }

    /**
     * Install a region pack from a file the user picked (Files app, USB, Bluetooth, SD card...).
     * Validated before install. This is how new regions arrive: the app never goes online.
     */
    fun importPack(uri: android.net.Uri): String {
        val tmp = File(dir, "import.tmp")
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("Can't read that file")
        val meta = RegionPack.readMeta(tmp)
        val region = meta?.get("region_id")
        if (meta == null || region == null || meta["schema_version"] == null) {
            tmp.delete()
            throw IOException("That isn't an OpenWild region file")
        }
        if (meta["model_id"] != Embedder.modelId(context) && RegionPack.prototypeCount(tmp) > 0) {
            tmp.delete()
            throw IOException("That region file needs a newer app version")
        }
        val dest = fileFor(region)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        return region
    }

    /** Fetch the hosted pack list. Disabled: the app has no INTERNET permission (BuildConfig.ALLOW_NETWORK). */
    fun fetchIndex(): List<RemotePack> {
        if (!BuildConfig.ALLOW_NETWORK) throw IOException("Network disabled in this build")
        val text = try {
            httpGet(BuildConfig.PACK_INDEX_URL).also { indexCache.writeText(it) }
        } catch (e: IOException) {
            if (indexCache.exists()) indexCache.readText() else throw e
        }
        val root = JSONObject(text)
        val packs = root.getJSONArray("packs")
        return (0 until packs.length()).map { i ->
            val p = packs.getJSONObject(i)
            RemotePack(
                regionId = p.getString("region_id"),
                name = p.getString("name"),
                version = p.getInt("version"),
                url = p.getString("url"),
                sha256 = p.getString("sha256"),
                size = p.optLong("size"),
                speciesCount = p.optInt("species_count"),
            )
        }
    }

    /** Download, verify checksum and model compatibility, then atomically install. */
    fun download(pack: RemotePack, onProgress: (Float) -> Unit) {
        if (!BuildConfig.ALLOW_NETWORK) throw IOException("Network disabled in this build")
        val tmp = File(dir, "${pack.regionId}.download")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = open(pack.url)
        try {
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else pack.size
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hex.equals(pack.sha256, ignoreCase = true)) {
            tmp.delete()
            throw IOException("Checksum mismatch for ${pack.regionId}")
        }
        val meta = RegionPack.readMeta(tmp)
        if (meta == null || (meta["model_id"] != Embedder.modelId(context) && RegionPack.prototypeCount(tmp) > 0)) {
            tmp.delete()
            throw IOException("Pack ${pack.regionId} was built for a different model; update the app")
        }
        val dest = fileFor(pack.regionId)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    fun delete(regionId: String) {
        fileFor(regionId).delete()
        if (activeRegionId == regionId) activeRegionId = null
    }

    private fun open(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(5) { // follow redirects (GitHub Releases redirects to its CDN)
            val c = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OpenWild-Android")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without location")
                c.disconnect()
                current = URL(current, loc)
            } else if (code == 200) {
                return c
            } else {
                c.disconnect()
                throw IOException("HTTP $code for $current")
            }
        }
        throw IOException("Too many redirects for $url")
    }

    private fun httpGet(url: String): String {
        val c = open(url)
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }
}

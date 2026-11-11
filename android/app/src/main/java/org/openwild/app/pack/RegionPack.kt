package org.openwild.app.pack

import android.database.sqlite.SQLiteDatabase
import org.openwild.app.core.ConfidenceParams
import org.openwild.app.core.Lookalike
import org.openwild.app.core.PrototypeIndex
import org.openwild.app.core.SpeciesContent
import org.openwild.app.core.SpeciesTable
import org.openwild.app.data.ContentParser
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File

/** A read-only region pack (docs/REGION_PACK_FORMAT.md) loaded into memory for matching. */
class RegionPack(file: File) : Closeable {

    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)

    val meta: Map<String, String> = buildMap {
        db.rawQuery("SELECT key, value FROM meta", null).use { c -> while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
    }
    val regionId: String = meta["region_id"] ?: file.nameWithoutExtension
    val regionName: String = meta["region_name"] ?: regionId
    val version: Int = meta["pack_version"]?.toIntOrNull() ?: 0
    val modelId: String = meta["model_id"] ?: ""
    val dim: Int = meta["embedding_dim"]?.toIntOrNull() ?: 0
    val params: ConfidenceParams = ConfidenceParams.fromMeta(meta)

    val species: SpeciesTable
    private val idToIndex: Map<Long, Int>
    val index: PrototypeIndex

    init {
        val ids = ArrayList<Long>()
        val sci = ArrayList<String>()
        val common = ArrayList<String?>()
        val fungus = ArrayList<Boolean>()
        val counts = ArrayList<Int>()
        val monthRows = ArrayList<ByteArray?>()
        db.rawQuery("SELECT id, scientific_name, common_name, kingdom, obs_count, months FROM species ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                ids += c.getLong(0)
                sci += c.getString(1)
                common += if (c.isNull(2)) null else c.getString(2)
                fungus += c.getString(3) == "Fungi"
                counts += c.getInt(4)
                monthRows += if (c.isNull(5)) null else c.getBlob(5)
            }
        }
        val n = ids.size
        val months = Array(12) { m -> IntArray(n) { i -> monthRows[i]?.getOrNull(m)?.toInt()?.and(0xFF) ?: 128 } }
        species = SpeciesTable(ids.toLongArray(), sci.toTypedArray(), common.toTypedArray(), fungus.toBooleanArray(), counts.toIntArray(), months)
        idToIndex = ids.withIndex().associate { (i, id) -> id to i }

        val vecs = ByteArrayOutputStream()
        val scales = ArrayList<Float>()
        val owner = ArrayList<Int>()
        val isText = ArrayList<Boolean>()
        db.rawQuery("SELECT species_id, vec, scale, kind FROM prototype ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val sp = idToIndex[c.getLong(0)] ?: continue
                val blob = c.getBlob(1)
                if (blob.size != dim) continue
                vecs.write(blob)
                scales += c.getFloat(2)
                owner += sp
                isText += c.getString(3) == "text"
            }
        }
        index = PrototypeIndex(dim, vecs.toByteArray(), scales.toFloatArray(), owner.toIntArray(), isText.toBooleanArray(), n)
    }

    private val contentCache = HashMap<Int, SpeciesContent?>()

    @Synchronized
    fun content(i: Int): SpeciesContent? = contentCache.getOrPut(i) {
        db.rawQuery("SELECT json FROM content WHERE species_id = ?", arrayOf(species.ids[i].toString())).use { c ->
            if (c.moveToFirst()) ContentParser.parse(c.getString(0)) else null
        }
    }

    fun lookalikes(i: Int): List<Lookalike> =
        db.rawQuery(
            "SELECT lookalike_name, danger, lookalike_species_id FROM lookalike WHERE species_id = ?",
            arrayOf(species.ids[i].toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Lookalike(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2))) }
        }.sortedBy { dangerRank(it.danger) }

    fun indexOfSpeciesId(id: Long): Int? = idToIndex[id]

    /** (description, wikipedia url) */
    fun description(i: Int): Pair<String?, String?> =
        db.rawQuery("SELECT description, wiki_url FROM species WHERE id = ?", arrayOf(species.ids[i].toString())).use { c ->
            if (c.moveToFirst()) (if (c.isNull(0)) null else c.getString(0)) to (if (c.isNull(1)) null else c.getString(1)) else null to null
        }

    data class Credit(val species: String, val observer: String?, val license: String, val url: String)

    fun credits(limit: Int = 500, offset: Int = 0): List<Credit> =
        db.rawQuery(
            "SELECT s.scientific_name, a.observer, a.license, a.url FROM attribution a JOIN species s ON s.id = a.species_id " +
                "ORDER BY s.scientific_name LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(Credit(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) } }

    fun creditCount(): Int = db.rawQuery("SELECT count(*) FROM attribution", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Survival guide: (high-value species, deadly species) for this region, each sorted by how common they are. */
    fun survivalGuide(): Pair<List<Int>, List<Int>> {
        val valuable = ArrayList<Int>()
        val deadly = ArrayList<Int>()
        db.rawQuery("SELECT species_id, json FROM content", null).use { c ->
            while (c.moveToNext()) {
                val i = idToIndex[c.getLong(0)] ?: continue
                val content = ContentParser.parse(c.getString(1)) ?: continue
                if (content.isDeadly) deadly += i
                else if (content.survivalPriority == "high_value") valuable += i
            }
        }
        val byCount = compareByDescending<Int> { species.obsCounts[it] }
        return valuable.sortedWith(byCount) to deadly.sortedWith(byCount)
    }

    override fun close() = db.close()

    companion object {
        fun dangerRank(d: String) = when (d) { "deadly" -> 0; "toxic" -> 1; "psychoactive" -> 2; "harmless" -> 3; else -> 4 }

        /** Read just the meta table (for listing installed packs without loading them). */
        fun readMeta(file: File): Map<String, String>? = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                buildMap { db.rawQuery("SELECT key, value FROM meta", null).use { c -> while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
            }
        } catch (e: Exception) {
            null
        }
    }
}

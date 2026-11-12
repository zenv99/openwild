package org.openwild.app.data

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import org.openwild.app.core.FieldNote
import java.io.File
import java.util.UUID

/** Reviews and photos stored only in app-private files. Nothing here is uploaded. */
class FieldNotes(context: Context) {
    private val dir = File(context.filesDir, "fieldnotes").apply { mkdirs() }
    private val photosDir = File(dir, "photos").apply { mkdirs() }
    private val file = File(dir, "notes.json")
    private val lock = Any()

    fun get(name: String): FieldNote = synchronized(lock) { read()[name] ?: FieldNote(name) }

    fun photoFile(fileName: String): File = File(photosDir, File(fileName).name)

    fun save(note: FieldNote): FieldNote = synchronized(lock) {
        val all = read()
        val next = note.copy(
            rating = FieldNote.clampRating(note.rating),
            photos = note.photos.take(FieldNote.MAX_PHOTOS),
        )
        if (next.hasContent()) all[next.scientificName] = next else all.remove(next.scientificName)
        write(all)
        next
    }

    fun addPhoto(name: String, bitmap: Bitmap): FieldNote = synchronized(lock) {
        val cur = read()[name] ?: FieldNote(name)
        if (cur.photos.size >= FieldNote.MAX_PHOTOS) return cur
        val fileName = sanitize(name) + "-" + UUID.randomUUID().toString().take(8) + ".jpg"
        File(photosDir, fileName).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
        }
        save(cur.copy(photos = cur.photos + fileName))
    }

    fun removePhoto(name: String, fileName: String): FieldNote = synchronized(lock) {
        photoFile(fileName).delete()
        val cur = read()[name] ?: FieldNote(name)
        save(cur.copy(photos = cur.photos.filter { it != fileName }))
    }

    private fun sanitize(name: String) =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "species" }

    private fun read(): MutableMap<String, FieldNote> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val arr = JSONObject(file.readText()).optJSONArray("notes") ?: JSONArray()
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.getString("name")
                    val photos = o.optJSONArray("photos")
                    put(
                        name,
                        FieldNote(
                            scientificName = name,
                            rating = o.optInt("rating"),
                            text = o.optString("text"),
                            photos = if (photos == null) emptyList() else List(photos.length()) { photos.getString(it) },
                        ),
                    )
                }
            }.toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun write(all: Map<String, FieldNote>) {
        val arr = JSONArray()
        all.values.filter { it.hasContent() }.forEach { n ->
            arr.put(
                JSONObject()
                    .put("name", n.scientificName)
                    .put("rating", n.rating)
                    .put("text", n.text)
                    .put("photos", JSONArray(n.photos)),
            )
        }
        file.writeText(JSONObject().put("notes", arr).toString())
    }
}

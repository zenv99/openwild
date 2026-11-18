package org.openwild.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Wrong-ID reports the phone could not send. Stored as plain JPEG plus a JSON file (no EXIF).
 * [flush] runs when the inbox can be reached. Nothing here opens a connection except through [PhotoInbox].
 */
object ReportOutbox {
    private const val DIR = "report_outbox"

    fun enqueue(context: Context, regionId: String, plant: String, fields: JSONObject, photos: List<Pair<Bitmap, String>>) {
        val id = UUID.randomUUID().toString().take(12)
        val folder = File(context.filesDir, "$DIR/$id").apply { mkdirs() }
        val sent = JSONArray()
        photos.forEachIndexed { i, (bmp, part) ->
            val safe = part.lowercase().replace(Regex("[^a-z0-9]+"), "_").ifBlank { "field" }
            val name = "${i + 1}_$safe.jpg"
            File(folder, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            sent.put(JSONObject().put("file", name).put("part", safe))
        }
        val body = JSONObject(fields.toString())
            .put("region", regionId)
            .put("plant", plant)
            .put("queued_photos", sent)
        File(folder, "report.json").writeText(body.toString())
    }

    /** Sends every queued report. Returns how many left the phone. Failures stay queued. */
    fun flush(context: Context): Int {
        if (!PhotoInbox.enabled) return 0
        val root = File(context.filesDir, DIR)
        val dirs = root.listFiles()?.filter { it.isDirectory && File(it, "report.json").isFile }.orEmpty()
        var sent = 0
        for (dir in dirs) {
            val ok = runCatching { sendDir(dir) }.isSuccess
            if (ok) {
                dir.deleteRecursively()
                sent++
            }
        }
        return sent
    }

    private fun sendDir(dir: File) {
        val fields = JSONObject(File(dir, "report.json").readText())
        val region = fields.getString("region")
        val plant = fields.optString("plant")
        val arr = fields.optJSONArray("queued_photos") ?: JSONArray()
        val photos = ArrayList<Pair<Bitmap, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("file")
            if (!name.matches(Regex("[A-Za-z0-9_.-]+"))) continue
            val file = File(dir, name)
            if (!file.isFile) continue
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: continue
            photos += bmp to o.optString("part", "field")
        }
        fields.remove("queued_photos")
        fields.remove("plant")
        PhotoInbox.sendReport(region, plant, fields, photos) { _, _ -> }
        photos.forEach { (bmp, _) -> bmp.recycle() }
    }
}

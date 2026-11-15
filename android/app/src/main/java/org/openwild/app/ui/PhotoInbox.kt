package org.openwild.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.openwild.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Sends a user's plant photos to the OpenWild photo inbox (a private GitHub repository), only after the
 * user has agreed on the permission screen. A bot checks each submission and adds it to the library.
 *
 * What is sent, and nothing else: the photos (re-encoded as plain JPEG, so no GPS position, time or camera
 * serial), the plant's name, the region, the growth stage of each photo, the name the user wants credited,
 * and the app version. No account, no device ID, no location.
 */
object PhotoInbox {
    /** True when this build can send photos directly (release builds carry an inbox key). */
    val enabled: Boolean get() = BuildConfig.INBOX_TOKEN.isNotBlank() && BuildConfig.ALLOW_NETWORK

    private const val API = "https://api.github.com"
    private const val MAX_SIDE = 1600

    /**
     * Upload [photos]; calls [progress] with (done, total). Returns the number of photos sent.
     * The small "submission.json" goes last, so the bot only picks up complete submissions.
     */
    fun send(
        context: Context,
        regionId: String,
        scientificName: String,
        credit: String,
        photos: List<Contribute.StagedPhoto>,
        progress: (Int, Int) -> Unit,
    ): Int {
        val id = UUID.randomUUID().toString().take(12)
        val folder = "inbox/$regionId/${slug(scientificName)}/$id"
        val sent = JSONArray()
        photos.forEachIndexed { k, p ->
            val bmp = Contribute.decodeUpright(context, p.uri, MAX_SIDE) ?: return@forEachIndexed
            val bytes = ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)   // fresh JPEG: carries no EXIF at all
                out.toByteArray()
            }
            bmp.recycle()
            val name = "${k + 1}_${p.stage}.jpg"
            put("$folder/$name", bytes, "Photo $name for $scientificName")
            sent.put(JSONObject().put("file", name).put("stage", p.stage))
            progress(sent.length(), photos.size)
        }
        if (sent.length() == 0) return 0
        val meta = JSONObject()
            .put("species", scientificName)
            .put("region", regionId)
            .put("credit", credit.take(60))
            .put("license", "CC BY 4.0")
            .put("own_photos", true)
            .put("photos", sent)
            .put("app_version", BuildConfig.VERSION_NAME)
        put("$folder/submission.json", meta.toString(2).toByteArray(), "Submission $id: $scientificName")
        return sent.length()
    }

    private fun put(path: String, bytes: ByteArray, message: String) {
        val url = URL("$API/repos/${BuildConfig.INBOX_REPO}/contents/$path")
        require(url.host == "api.github.com" && url.protocol == "https")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.INBOX_TOKEN}")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "OpenWild/${BuildConfig.VERSION_NAME}")
            val body = JSONObject()
                .put("message", message)
                .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .toString().toByteArray()
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("Upload failed ($code)")
        } finally {
            conn.disconnect()
        }
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}

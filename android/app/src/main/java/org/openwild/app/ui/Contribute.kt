package org.openwild.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import org.openwild.app.BuildConfig
import org.openwild.app.R
import java.io.File

/**
 * Ways users can contribute without OpenWild ever collecting data. Everything goes through the
 * Android share sheet or the browser, chosen and confirmed by the user.
 */
object Contribute {

    /** Share the first photo + the result through any app (e.g. iNaturalist, messaging). */
    fun shareSighting(context: Context, state: UiState, vm: AppViewModel) {
        val res = state.result ?: return
        val sp = vm.pack?.species ?: return
        val top = res.result.top
        val name = sp.commonNames[top]?.let { "$it (${sp.scientificNames[top]})" } ?: sp.scientificNames[top]
        val text = context.getString(R.string.share_text, name, res.result.score)
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, text)
            val photo = state.photos.firstOrNull()?.bitmap
            if (photo != null) {
                val uri = cacheShareImage(context, photo)
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_sighting)))
    }

    /** Opens a pre-filled GitHub issue. No photo or location is included. */
    fun reportWrongId(context: Context, vm: AppViewModel, res: IdResult) {
        val pack = vm.pack ?: return
        val top = pack.species.scientificNames[res.result.top]
        val title = "Wrong ID: app said $top"
        val body = buildString {
            appendLine("**App said:** $top (${res.result.score}% match confidence, ${res.result.tier})")
            appendLine("**Region pack:** ${pack.regionId} v${pack.version}")
            appendLine("**Brain:** ${res.brain} (selected ${res.brainRequested}, scan ${res.scanDetail})")
            appendLine("**Photos used:** ${res.photoCount}")
            appendLine()
            appendLine("**What I think it is (and how sure):** ")
            appendLine()
            appendLine("_Attach a photo here only if you want to. Don't include your exact location if you'd rather keep it private._")
        }
        open(context, "${BuildConfig.REPO_URL}/issues/new?title=${Uri.encode(title)}&body=${Uri.encode(body)}&labels=wrong-id")
    }

    /**
     * Opens a pre-filled correction report (a GitHub issue the user can read and edit before sending).
     * Contains only species names, match scores and pack/model versions: no photo, no location, no account.
     * Collected corrections are used to measure and tune photo ID.
     */
    fun sendCorrection(context: Context, c: org.openwild.app.data.ReviewStore.Correction) {
        val title = "Correction: app said ${c.predicted}, it was ${c.chosen}"
        val body = buildString {
            appendLine("**App said:** ${c.predicted}")
            appendLine("**It was:** ${c.chosen} " + if (c.chosenRank > 0) "(match #${c.chosenRank} in the list)" else "(not in the list, found by search)")
            appendLine("**Top matches:** ${c.topMatches}")
            appendLine("**Photos used:** ${c.photoCount}")
            appendLine("**Region pack:** ${c.regionId} v${c.packVersion} · model ${c.modelId}")
            appendLine("**Brain:** ${c.brain.ifBlank { "unknown" }} (selected ${c.brainRequested.ifBlank { c.brain }}, scan ${c.scanDetail.ifBlank { "unknown" }})")
            appendLine()
            appendLine("**How sure are you, and what told you?** ")
            appendLine()
            appendLine("_No photo or location is included. Attach a photo only if you want to._")
        }
        open(context, "${BuildConfig.REPO_URL}/issues/new?template=correction.yml&title=${Uri.encode(title)}&details=${Uri.encode(body)}")
    }

    /** One photo picked for a contribution, tagged with the growth stage it shows. */
    data class StagedPhoto(val uri: Uri, val stage: String)

    /**
     * Saves the user's photos to Pictures/OpenWild, re-encoded as plain JPEG (this drops EXIF: GPS position,
     * time, camera serial), named "Genus_species__stage__n.jpg" so the stage survives the upload. Returns the
     * number saved. Android 8-9 (no scoped gallery folder) get a share sheet instead.
     */
    fun savePhotosForContribution(context: Context, scientificName: String, photos: List<StagedPhoto>): Int {
        val base = scientificName.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
        val stamp = System.currentTimeMillis() / 1000
        var saved = 0
        val shareUris = ArrayList<Uri>()
        photos.forEachIndexed { k, p ->
            val bmp = decodeUpright(context, p.uri, maxSide = 2048) ?: return@forEachIndexed
            val name = "${base}__${p.stage}__${k + 1}_$stamp.jpg"
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OpenWild")
                }
                val r = context.contentResolver
                val uri = r.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@forEachIndexed
                r.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } ?: return@forEachIndexed
            } else {
                val dir = File(context.cacheDir, "share/contrib").apply { mkdirs() }
                val f = File(dir, name)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                shareUris += FileProvider.getUriForFile(context, "${context.packageName}.share", f)
            }
            bmp.recycle()
            saved++
        }
        if (shareUris.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.contrib_save_photos)))
        }
        return saved
    }

    /** Opens the "Plant photos" issue form, pre-filled. The user attaches the saved photos and submits. */
    fun openPhotoContribution(context: Context, regionId: String, scientificName: String, credit: String, photos: List<StagedPhoto>) {
        val stages = photos.groupingBy { it.stage }.eachCount().entries.joinToString(", ") { "${it.key} x${it.value}" }
        val url = "${BuildConfig.REPO_URL}/issues/new?template=photo-contribution.yml" +
            "&title=${Uri.encode("Photos: $scientificName")}" +
            "&species=${Uri.encode(scientificName)}&region=${Uri.encode(regionId)}" +
            "&credit=${Uri.encode(credit)}&stages=${Uri.encode(stages)}"
        open(context, url)
    }

    /** Decode a picked/taken photo upright (applies EXIF rotation) and no larger than [maxSide]. */
    fun decodeUpright(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val r = context.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = r.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rot = r.openInputStream(uri)?.use {
            when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = minOf(1f, maxSide.toFloat() / maxOf(raw.width, raw.height))
        if (rot == 0f && scale == 1f) return raw
        val m = android.graphics.Matrix().apply { postRotate(rot); postScale(scale, scale) }
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }.getOrNull()

    /** A fresh file the system camera writes into (inside the app's private share cache). */
    fun newCameraFile(context: Context): Uri {
        val dir = File(context.cacheDir, "share/camera").apply { mkdirs() }
        val f = File(dir, "shot_${System.nanoTime()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.share", f)
    }

    /** Opens the user's Lightning wallet; if none is installed, copies the address instead. */
    fun donateLightning(context: Context) {
        val address = BuildConfig.LIGHTNING_ADDRESS
        // LNURL-pay: wallets open straight onto a filled-in payment to this address.
        val target = Lightning.lnurl(address) ?: address
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("lightning:$target")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Lightning address", address))
            Toast.makeText(context, context.getString(R.string.copied_address), Toast.LENGTH_LONG).show()
        }
    }

    /** Feedback by email if an app address is configured, otherwise a GitHub issue. Nothing is sent automatically. */
    fun sendFeedback(context: Context) {
        val subject = "OpenWild feedback (v${BuildConfig.VERSION_NAME})"
        if (BuildConfig.FEEDBACK_EMAIL.isNotBlank()) {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.FEEDBACK_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        open(context, "${BuildConfig.REPO_URL}/issues/new?title=${Uri.encode(subject)}&labels=feedback")
    }

    fun open(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun cacheShareImage(context: Context, bmp: Bitmap): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "sighting.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.share", file)
    }
}

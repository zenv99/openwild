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
import org.openwild.app.core.FieldNote
import org.openwild.app.core.LightningAddress
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
            appendLine("**App said:** $top (${res.result.score}%, ${res.result.tier})")
            appendLine("**Region pack:** ${pack.regionId} v${pack.version}")
            appendLine("**Photos used:** ${res.photoCount}")
            appendLine()
            appendLine("**What I think it is (and how sure):** ")
            appendLine()
            appendLine("_Attach a photo here only if you want to. Don't include your exact location if you'd rather keep it private._")
        }
        open(context, "${BuildConfig.REPO_URL}/issues/new?title=${Uri.encode(title)}&body=${Uri.encode(body)}&labels=wrong-id")
    }

    /** Copies the Lightning Address. A `lightning:user@host` link is not an invoice, so wallets often ignore it. */
    fun copyLightningAddress(context: Context) {
        val address = BuildConfig.LIGHTNING_ADDRESS
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Lightning address", address))
    }

    /**
     * Opens a wallet on the LNURL-pay endpoint for the address (`https://host/.well-known/lnurlp/user`,
     * bech32-encoded). The app does not contact that host. If no wallet handles it, the address is already copied.
     */
    fun openLightningWallet(context: Context) {
        val address = BuildConfig.LIGHTNING_ADDRESS
        val uri = runCatching { LightningAddress.payUri(address) }.getOrElse { "lightning:$address" }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.donate_no_wallet, address), Toast.LENGTH_LONG).show()
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

    /** Share sheet with the note and any photos. The user picks where it goes; nothing is uploaded by the app. */
    fun submitFieldNote(context: Context, note: FieldNote, regionName: String?, photoFiles: List<File>) {
        val text = note.shareText(regionName)
        val uris = photoFiles.map { cacheCopy(context, it) }
        val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, "OpenWild field note: ${note.scientificName}")
            putExtra(Intent.EXTRA_TEXT, text)
            if (uris.isEmpty()) {
                type = "text/plain"
            } else {
                type = "image/jpeg"
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.notes_submit)))
    }

    /** Text-only GitHub issue. Photos are not attached; the share sheet is how photos leave the phone. */
    fun submitFieldNoteIssue(context: Context, note: FieldNote, regionName: String?) {
        val title = "Field note: ${note.scientificName}"
        val body = note.shareText(regionName) + "\n\n_Photos stay on my phone unless I attach them myself._"
        open(context, "${BuildConfig.REPO_URL}/issues/new?title=${Uri.encode(title)}&body=${Uri.encode(body)}&labels=field-note")
    }

    fun open(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun cacheCopy(context: Context, src: File): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val dest = File(dir, src.name)
        src.copyTo(dest, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.share", dest)
    }

    private fun cacheShareImage(context: Context, bmp: Bitmap): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "sighting.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.share", file)
    }
}

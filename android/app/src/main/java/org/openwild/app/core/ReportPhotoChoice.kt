package org.openwild.app.core

/**
 * What a wrong-ID report does with the photo. The photo stays on the phone unless the person
 * ticks "Send my photo". "Remember this" stores that tick for next time. Forgetting it, in
 * Settings or by sending with Remember off, returns to the default: photo off.
 */
object ReportPhotoChoice {
    data class Saved(val remember: Boolean, val sendPhoto: Boolean)

    /** The box starts ticked only when that choice was saved as on. */
    fun initialSendPhoto(saved: Saved): Boolean = saved.remember && saved.sendPhoto

    fun initialRemember(saved: Saved): Boolean = saved.remember

    /**
     * Persist only when Remember is ticked. Otherwise forget any previous choice, so the next
     * report starts with the photo off again.
     */
    fun afterSend(remember: Boolean, sendPhoto: Boolean): Saved =
        if (remember) Saved(remember = true, sendPhoto = sendPhoto) else Saved(remember = false, sendPhoto = false)

    /** Photos leave the phone only when the box is ticked. An empty list is never uploaded. */
    fun <T> attachments(sendPhoto: Boolean, photos: List<T>): List<T> =
        if (sendPhoto) photos else emptyList()

    /**
     * Fields the person typed, plus a hard off for location. Diagnostic keys already on the report
     * (brain, brain_requested, scan_detail, top matches) are not in this map, so they stay as they are.
     */
    fun fieldUpdates(predicted: String, chosen: String?, guess: String, sendPhoto: Boolean): Map<String, Any> {
        val name = guess.trim()
        return linkedMapOf(
            "predicted" to predicted,
            "chosen" to (chosen ?: ""),
            "guess" to name,
            "suggested" to name,
            "note" to "",
            "share_location" to false,
            "photos_included" to sendPhoto,
        )
    }
}

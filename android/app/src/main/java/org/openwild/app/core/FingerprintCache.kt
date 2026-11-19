package org.openwild.app.core

import java.util.WeakHashMap

/**
 * Fingerprints already computed for a photo, so identifying the same pictures again does not
 * run the model a second time. The key is the photo object the screen is holding. A different
 * model or a switch between one look and several looks is a miss, and the model runs again.
 */
class FingerprintCache {
    private class Slot(val embedder: Any, val careful: Boolean, val vector: FloatArray)

    private val map = WeakHashMap<Any, Slot>()

    @Synchronized
    fun get(photo: Any, embedder: Any, careful: Boolean): FloatArray? {
        val slot = map[photo] ?: return null
        return if (slot.embedder === embedder && slot.careful == careful) slot.vector else null
    }

    @Synchronized
    fun put(photo: Any, embedder: Any, careful: Boolean, vector: FloatArray) {
        map[photo] = Slot(embedder, careful, vector)
    }

    @Synchronized
    fun clear() = map.clear()
}

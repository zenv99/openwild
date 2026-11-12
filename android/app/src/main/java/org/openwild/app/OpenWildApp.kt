package org.openwild.app

import android.app.Application
import org.openwild.app.data.ReviewStore
import org.openwild.app.ml.Embedder
import org.openwild.app.pack.PackManager

class OpenWildApp : Application() {
    val embedder: Embedder? by lazy { Embedder.loadOrNull(this) }
    val packs: PackManager by lazy { PackManager(this) }
    val reviews: ReviewStore by lazy { ReviewStore(this) }
}

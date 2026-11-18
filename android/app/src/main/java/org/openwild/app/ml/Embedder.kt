package org.openwild.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import org.openwild.app.core.Fingerprints
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * On-device fingerprint model (LiteRT). Contract (pipeline/openwild_pipeline/model.py):
 * input float32 NHWC [1,224,224,3] RGB in [0,1]; output float32 [1,768] L2-normalised.
 */
class Embedder private constructor(private val interpreter: Interpreter) : Closeable {

    /** Fingerprint length, read from the model itself (384 for the free DINOv2-small model). */
    val dim: Int = interpreter.getOutputTensor(0).shape().last()

    companion object {
        /** Fallback when assets/embedder.json is missing. Packs must carry the same meta.model_id. */
        const val DEFAULT_MODEL_ID = "ow-dinov2s-384-v1"
        const val INPUT = 224
        private const val ASSET = "embedder.tflite"

        /** Model id written by the pipeline next to the model (assets/embedder.json). */
        fun modelId(context: Context): String = try {
            org.json.JSONObject(context.assets.open("embedder.json").bufferedReader().readText())
                .optString("model_id", DEFAULT_MODEL_ID)
        } catch (e: IOException) {
            DEFAULT_MODEL_ID
        }

        /** The optional downloaded model (BioCLIP 2, "Better recognition"). Null if missing or unreadable. */
        fun loadFileOrNull(file: java.io.File): Embedder? = try {
            FileInputStream(file).use { stream ->
                val buffer = stream.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                Embedder(
                    Interpreter(
                        buffer,
                        Interpreter.Options()
                            .setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
                            .setUseXNNPACK(true),
                    ),
                ).also { it.warmUp() }
            }
        } catch (e: Exception) {
            null
        }

        /** Returns null if the model asset is missing (e.g. a CI build without the pipeline output). */
        fun loadOrNull(context: Context): Embedder? = try {
            context.assets.openFd(ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    val buffer = stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    Embedder(
                        Interpreter(
                            buffer,
                            Interpreter.Options()
                                .setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                                .setUseXNNPACK(true),
                        ),
                    ).also { it.warmUp() }
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    private val input: ByteBuffer = ByteBuffer.allocateDirect(4 * INPUT * INPUT * 3).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(INPUT * INPUT)

    /**
     * One fingerprint. [careful] looks at several crops (centre, flip, upper band, tighter centre)
     * and averages them. The pack's prototypes stay the same; only the query changes.
     * Careful is the default because a phone photo often includes wall, pot and floor.
     */
    @Synchronized
    fun embed(bitmap: Bitmap, careful: Boolean = true): FloatArray {
        if (!careful) return embedOne(bitmap)
        val crops = queryCrops(bitmap)
        val vecs = crops.map { embedOne(it) }
        for (c in crops) if (c !== bitmap) c.recycle()
        return Fingerprints.meanUnit(vecs)
    }

    @Synchronized
    private fun embedOne(bitmap: Bitmap): FloatArray {
        val square = ImageUtils.centerCropSquare(bitmap, INPUT)
        square.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 255f)
            input.putFloat(((p shr 8) and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        input.rewind()
        val out = Array(1) { FloatArray(dim) }
        interpreter.run(input, out)
        val v = out[0]
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n).coerceAtLeast(1e-12f)
        for (i in v.indices) v[i] /= n
        if (square !== bitmap) square.recycle()
        return v
    }

    /** Crops of the user's photo. The original bitmap is not recycled here. */
    private fun queryCrops(bitmap: Bitmap): List<Bitmap> {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 2 || h < 2) return listOf(bitmap)
        val flip = Bitmap.createBitmap(bitmap, 0, 0, w, h, Matrix().apply { postScale(-1f, 1f, w / 2f, h / 2f) }, true)
        val upperH = (h * 0.72f).toInt().coerceIn(1, h)
        val upper = Bitmap.createBitmap(bitmap, 0, 0, w, upperH)
        val side = (minOf(w, h) * 0.72f).toInt().coerceAtLeast(1)
        val left = ((w - side) / 2).coerceAtLeast(0)
        val top = ((h - side) / 2).coerceAtLeast(0)
        val tight = Bitmap.createBitmap(bitmap, left, top, side.coerceAtMost(w - left), side.coerceAtMost(h - top))
        return listOf(bitmap, flip, upper, tight)
    }

    /** One dummy inference so the first real identification isn't slowed by lazy initialisation. */
    private fun warmUp() {
        runCatching { embed(Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888), careful = false) }
    }

    override fun close() = interpreter.close()
}

package org.openwild.app.ml

import android.content.Context
import android.graphics.Bitmap
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

    @Synchronized
    fun embed(bitmap: Bitmap): FloatArray {
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

    /** One dummy inference so the first real identification isn't slowed by lazy initialisation. */
    private fun warmUp() {
        runCatching { embed(Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)) }
    }

    override fun close() = interpreter.close()
}

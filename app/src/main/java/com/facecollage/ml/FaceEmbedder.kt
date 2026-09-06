package com.facecollage.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * On-device face embedding using MobileFaceNet.
 *
 * Model  : mobilefacenet.tflite (placed in assets/)
 * Source : https://github.com/sirius-ai/MobileFaceNet_TF (converted to TFLite)
 * Input  : 112×112 RGB, normalised to [-1, 1]  →  (pixel / 127.5) - 1.0
 * Output : 192-d L2-normalised embedding vector
 * Size   : ~1.9 MB
 * Speed  : <10 ms on mid-range devices (NNAPI accelerated)
 */
@Singleton
class FaceEmbedder @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val MODEL_FILE   = "mobilefacenet.tflite"
        const val INPUT_SIZE           = 160
        const val EMBEDDING_DIM        = 128
    }

    private val interpreter: Interpreter by lazy {
        val options = Interpreter.Options().apply {
            numThreads = 2
            useNNAPI   = true   // hardware accelerator when available
        }
        Interpreter(loadModelFile(), options)
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fd          = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel     = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    /**
     * Returns an L2-normalised 192-d embedding for the given face crop.
     * [faceCrop] is the expanded face region from the video frame.
     * It is resized to 112×112 internally.
     */
    fun embed(faceCrop: Bitmap): FloatArray {
        val resized     = Bitmap.createScaledBitmap(faceCrop, INPUT_SIZE, INPUT_SIZE, true)
        val inputBuffer = bitmapToByteBuffer(resized)
        val output      = Array(1) { FloatArray(EMBEDDING_DIM) }
        interpreter.run(inputBuffer, output)
        return l2Normalise(output[0])
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        // 1 batch × 112 × 112 × 3 channels × 4 bytes per float
        val buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8)  and 0xFF
            val b =  pixel         and 0xFF
            // Normalise to [-1, 1]
            buffer.putFloat((r - 127.5f) / 127.5f)
            buffer.putFloat((g - 127.5f) / 127.5f)
            buffer.putFloat((b - 127.5f) / 127.5f)
        }
        buffer.rewind()
        return buffer
    }

    private fun l2Normalise(vec: FloatArray): FloatArray {
        var norm = 0f
        for (v in vec) norm += v * v
        norm = sqrt(norm).coerceAtLeast(1e-10f)
        return FloatArray(vec.size) { vec[it] / norm }
    }

    fun close() {
        // interpreter is a lazy delegate; only close if it was initialised
        try { interpreter.close() } catch (_: Exception) {}
    }
}
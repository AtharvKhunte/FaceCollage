package com.facecollage.ml

import android.graphics.Bitmap
import android.graphics.RectF
import com.facecollage.domain.model.DetectedFace
import com.facecollage.domain.model.FaceQualityScore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Wraps ML Kit FaceDetector.
 *
 * Settings:
 *   - PERFORMANCE_MODE_ACCURATE   — best bounding boxes on portrait video
 *   - LANDMARK_MODE_ALL           — needed for eye/face landmark positions
 *   - CLASSIFICATION_MODE_ALL     — eyes-open + smile probabilities
 *   - CONTOUR_MODE_ALL            — tighter bounding boxes
 *   - minFaceSize = 0.08f         — ignore very tiny background faces
 *
 * For each detected face:
 *   1. Expands the bounding box by 30% in each direction (gives MobileFaceNet context)
 *   2. Computes a quality score (frontality, sharpness, eyes, coverage, smile)
 *   3. Runs MobileFaceNet on the expanded crop to get a 192-d embedding
 *   4. Filters out faces with composite quality < 0.15 (blurred / partial / bad angle)
 */
@Singleton
class FaceDetectorWrapper @Inject constructor(
    private val embedder: FaceEmbedder
) {
    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
             .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.03f)
            .enableTracking()
            .build()
    )

    /**
     * Detects all valid faces in [frame] at timestamp [frameMs].
     * Returns an empty list when the frame is a whip-pan or all faces score below 0.15.
     */
    suspend fun detectFaces(frame: Bitmap, frameMs: Long, rotation: Int = 0): List<DetectedFace> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(frame, rotation)
            detector.process(image)
                .addOnSuccessListener { faces ->

                    val results = faces.mapNotNull { face ->
                        buildDetectedFace(face, frame, frameMs)
                    }.filter { it.score.composite >= 0.05f }

                    cont.resume(results)
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    private fun buildDetectedFace(face: Face, frame: Bitmap, frameMs: Long): DetectedFace? {
        val box = face.boundingBox
        val w   = frame.width.toFloat()
        val h   = frame.height.toFloat()

        // Expand bounding box by 30% — gives MobileFaceNet more context
        val expandW = box.width()  * 0.30f
        val expandH = box.height() * 0.30f
        val left    = (box.left   - expandW).coerceAtLeast(0f)
        val top     = (box.top    - expandH).coerceAtLeast(0f)
        val right   = (box.right  + expandW).coerceAtMost(w)
        val bottom  = (box.bottom + expandH).coerceAtMost(h)

        if (right <= left || bottom <= top) return null
        val faceW = (right - left).toInt()
        val faceH = (bottom - top).toInt()
        if (faceW < 40 || faceH < 40) return null   // too small to embed reliably


        val faceCrop = try {
            Bitmap.createBitmap(frame, left.toInt(), top.toInt(), faceW, faceH)
        } catch (e: Exception) { return null }

        val quality   = scoreFace(face, frame, faceCrop)


        val embedding = embedder.embed(faceCrop)
        faceCrop.recycle()

        return DetectedFace(
            frameIndex  = frameMs,
            bitmap      = frame,           // full frame — NOT the crop
            boundingBox = RectF(left, top, right, bottom),
            embedding   = embedding,
            score       = quality,
        )
    }

    // ─── Quality scoring ──────────────────────────────────────────────────────

    private fun scoreFace(face: Face, frame: Bitmap, faceCrop: Bitmap): FaceQualityScore {
        // 1. Frontality — penalise yaw (left-right) and pitch (up-down)
        val yaw       = abs(face.headEulerAngleY)   // degrees left/right from camera axis
        val pitch     = abs(face.headEulerAngleX)   // degrees up/down
        val frontality = (1f - (yaw   / 45f).coerceIn(0f, 1f)) *
                (1f - (pitch / 30f).coerceIn(0f, 1f))

        // 2. Eyes open — average of left/right eye-open probability
        val leftEye  = face.leftEyeOpenProbability  ?: 0.5f
        val rightEye = face.rightEyeOpenProbability ?: 0.5f
        val eyesOpen  = (leftEye + rightEye) / 2f

        // 3. Smiling probability
        val smiling   = face.smilingProbability ?: 0.5f

        // 4. Frame coverage — penalise clipped faces
        val box       = face.boundingBox
        val faceArea  = box.width().toFloat() * box.height()
        val inFrame   = box.left >= 0 && box.top >= 0 &&
                box.right <= frame.width && box.bottom <= frame.height
        val frameCoverage = if (inFrame) {
            // Also reward faces that are a reasonable size relative to the frame
            val frameArea = frame.width.toFloat() * frame.height
            val relSize   = (faceArea / frameArea).coerceIn(0f, 1f)
            0.7f + 0.3f * (relSize / 0.04f).coerceIn(0f, 1f)
        } else {
            // Partially clipped — compute what fraction is visible
            val visibleW    = min(box.right, frame.width)  - box.left.coerceAtLeast(0)
            val visibleH    = min(box.bottom, frame.height) - box.top.coerceAtLeast(0)
            val visibleArea = (visibleW * visibleH).coerceAtLeast(0).toFloat()
            (visibleArea / faceArea).coerceIn(0f, 1f) * 0.7f
        }

        // 5. Sharpness — Laplacian variance on the face crop
        val sharpness = laplacianVariance(faceCrop)

        return FaceQualityScore(
            frontality    = frontality,
            sharpness     = sharpness,
            eyesOpen      = eyesOpen,
            smiling       = smiling,
            frameCoverage = frameCoverage,
        )
    }

    /**
     * Returns a 0-1 sharpness score via Laplacian variance.
     * Operates on a 64×64 grayscale version of [bitmap] for speed.
     * Variance >= 500 maps to score 1.0.
     *
     * Low variance = blurry (motion blur, whip-pan) → low score → face filtered out.
     */
    private fun laplacianVariance(bitmap: Bitmap): Float {
        val scale = 64
        val small  = Bitmap.createScaledBitmap(bitmap, scale, scale, true)
        val pixels = IntArray(scale * scale)
        small.getPixels(pixels, 0, scale, 0, 0, scale, scale)
        small.recycle()

        // Convert to grayscale luminance
        val gray = FloatArray(scale * scale) { i ->
            val p = pixels[i]
            0.299f  * ((p shr 16) and 0xFF) +
                    0.587f  * ((p shr 8)  and 0xFF) +
                    0.114f  * ( p         and 0xFF)
        }

        // Apply 3×3 Laplacian kernel; measure variance of the result
        var mean  = 0.0
        var count = 0
        val laplacian = FloatArray(scale * scale)

        for (y in 1 until scale - 1) {
            for (x in 1 until scale - 1) {
                val idx = y * scale + x
                val lap = -gray[idx - scale - 1] - gray[idx - scale] - gray[idx - scale + 1] +
                        -gray[idx - 1]          + 8f * gray[idx]   - gray[idx + 1] +
                        -gray[idx + scale - 1] - gray[idx + scale] - gray[idx + scale + 1]
                laplacian[count] = lap
                mean += lap
                count++
            }
        }
        mean /= count

        var variance = 0.0
        for (i in 0 until count) {
            variance += (laplacian[i] - mean).pow(2.0)
        }
        variance /= count

        // Normalise: variance of 500 → score 1.0
        return (variance / 500.0).toFloat().coerceIn(0f, 1f)
    }

    fun close() = detector.close()
}
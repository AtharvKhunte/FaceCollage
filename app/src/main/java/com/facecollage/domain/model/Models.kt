package com.facecollage.domain.model

import android.graphics.Bitmap
import android.graphics.RectF

/** A face detected in a single video frame. */
data class DetectedFace(
    val frameIndex: Long,          // video position in ms
    val bitmap: Bitmap,            // full video frame (not cropped)
    val boundingBox: RectF,        // face rect in the bitmap
    val embedding: FloatArray,     // 192-d from MobileFaceNet
    val score: FaceQualityScore,
) {
    override fun equals(other: Any?) = other is DetectedFace && frameIndex == other.frameIndex
    override fun hashCode() = frameIndex.hashCode()
}

data class FaceQualityScore(
    /** 0-1: 1 = fully frontal (eulerY ≈ 0, eulerX ≈ 0) */
    val frontality: Float,
    /** 0-1: estimated sharpness of the face crop via Laplacian variance */
    val sharpness: Float,
    /** 0-1: both eyes open probability from ML Kit */
    val eyesOpen: Float,
    /** 0-1: smiling probability from ML Kit */
    val smiling: Float,
    /** 0-1: penalises faces partially outside the frame */
    val frameCoverage: Float,
) {
    /**
     * Combined quality metric.
     * Weights: frontality 35%, sharpness 30%, eyes 20%, coverage 10%, smile 5%
     */
    val composite: Float
        get() = frontality      * 0.35f +
                sharpness       * 0.30f +
                eyesOpen        * 0.20f +
                frameCoverage   * 0.10f +
                smiling         * 0.05f
}

/** A cluster of faces across frames that belong to the same person. */
data class PersonCluster(
    val id: Int,
    val faces: List<DetectedFace>,
    /** Best face chosen by composite quality score. */
    val representative: DetectedFace,
    /** Appearance segments (start_ms..end_ms) */
    val appearances: List<LongRange>,
) {
    val appearanceCount: Int get() = appearances.size
}

/** Processing state emitted to the UI. */
sealed class ProcessingState {
    object Idle : ProcessingState()
    data class Extracting(val progress: Float, val frameCount: Int) : ProcessingState()
    data class Detecting(val progress: Float, val detectedCount: Int) : ProcessingState()
    data class Clustering(val progress: Float) : ProcessingState()
    data class BuildingCollage(val progress: Float) : ProcessingState()
    data class Done(val clusters: List<PersonCluster>, val collagePath: String) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}
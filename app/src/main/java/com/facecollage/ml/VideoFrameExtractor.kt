package com.facecollage.ml

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Extracts frames from a video at a fixed interval using [MediaMetadataRetriever].
 *
 * Sampling strategy:
 *   - Default 5 fps (every 200 ms) — 150 frames for a 30-second clip
 *   - Emits (bitmap, timestampMs) pairs as a cold Flow
 *   - Bitmaps are scaled to max 960px on the longest dimension to reduce memory
 *     while retaining enough resolution for ML Kit face detection
 *
 * The full-resolution frame is NOT kept. The scaled bitmap is stored in
 * DetectedFace.bitmap and used as the collage tile source.
 */
@Singleton
class VideoFrameExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /** Frames per second to sample. */
        const val SAMPLE_FPS = 5
        /** Maximum bitmap dimension (width or height) after scaling. */
        const val MAX_DIM    = 960
    }

    data class VideoInfo(val durationMs: Long, val width: Int, val height: Int)

    /** Returns basic metadata without decoding frames. */
    fun getVideoInfo(uri: Uri): VideoInfo {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val width  = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        val height = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0
        retriever.release()
        return VideoInfo(durationMs, width, height)
    }

    /**
     * Emits (Bitmap, timestampMs) pairs.
     * Runs on [Dispatchers.IO].
     * Respects coroutine cancellation — exits cleanly if the job is cancelled.
     */
    fun extractFrames(uri: Uri, samplingFps: Int = SAMPLE_FPS): Flow<Pair<Bitmap, Long>> =
        flow {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: return@flow

                val stepMs = 1000L / samplingFps
                var timeMs = 0L

                while (timeMs <= durationMs && coroutineContext.isActive) {
                    val frame = retriever.getFrameAtTime(
                        timeMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    if (frame != null) {
                        val scaled = scaleBitmap(frame, MAX_DIM)
                        if (scaled !== frame) frame.recycle()
                        emit(Pair(scaled, timeMs))
                    }
                    timeMs += stepMs
                }
            } finally {
                retriever.release()
            }
        }.flowOn(Dispatchers.IO)

    private fun scaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val scale = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(
            bitmap,
            (w * scale).toInt(),
            (h * scale).toInt(),
            true
        )
    }
}
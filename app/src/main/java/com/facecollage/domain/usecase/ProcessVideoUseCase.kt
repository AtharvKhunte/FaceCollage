    package com.facecollage.domain.usecase

    import android.content.Context
    import android.net.Uri
    import com.facecollage.domain.model.DetectedFace
    import com.facecollage.domain.model.ProcessingState
    import com.facecollage.ml.CollageRenderer
    import com.facecollage.ml.FaceClusterer
    import com.facecollage.ml.FaceDetectorWrapper
    import com.facecollage.ml.VideoFrameExtractor
    import com.facecollage.utils.ImageSaver
    import dagger.hilt.android.qualifiers.ApplicationContext
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.channelFlow
    import kotlinx.coroutines.flow.flowOn
    import kotlinx.coroutines.withContext
    import java.util.concurrent.atomic.AtomicInteger
    import javax.inject.Inject
    import javax.inject.Singleton

    /**
     * Orchestrates the full video → collage pipeline:
     *
     *  1. Extract frames at 5 fps  [VideoFrameExtractor]
     *  2. Detect faces + score quality  [FaceDetectorWrapper]
     *  3. Compute 192-d embeddings (done inside FaceDetectorWrapper → FaceEmbedder)
     *  4. DBSCAN cluster embeddings  [FaceClusterer]
     *  5. Choose representative frame per cluster (max composite quality score)
     *  6. Render Instagram-Story collage  [CollageRenderer]
     *  7. Save to disk  [ImageSaver]
     *
     * Emits [ProcessingState] at every stage. The Flow is cold — one subscription
     * drives the entire pipeline on Dispatchers.IO, keeping the main thread free.
     */
    @Singleton
    class ProcessVideoUseCase @Inject constructor(
        @ApplicationContext private val context: Context,
        private val frameExtractor: VideoFrameExtractor,
        private val faceDetector: FaceDetectorWrapper,
        private val faceClusterer: FaceClusterer,
        private val collageRenderer: CollageRenderer,
        private val imageSaver: ImageSaver,
    ) {
        /**
         * Execute the pipeline for [videoUri].
         * Call from a ViewModel coroutine scope.
         */
        fun execute(videoUri: Uri): Flow<ProcessingState> = channelFlow {
            send(ProcessingState.Extracting(0f, 0))

            // ── Phase 1 & 2: Frame extraction + detection ────────────────────────
            val allFaces      = mutableListOf<DetectedFace>()
            val videoInfo     = frameExtractor.getVideoInfo(videoUri)
            val totalFrames   = (videoInfo.durationMs / 200L).coerceAtLeast(1)  // 200 ms = 5 fps
            val processedFrames = AtomicInteger(0)

            frameExtractor.extractFrames(videoUri).collect { (frame, timestampMs, rotation) ->
                send(
                    ProcessingState.Detecting(
                        progress     = processedFrames.get().toFloat() / totalFrames,
                        detectedCount = allFaces.size
                    )
                )

                val faces = faceDetector.detectFaces(frame, timestampMs, rotation)
                if (faces.isNotEmpty()) {

                }

                allFaces.addAll(faces)

                // Recycle the frame bitmap if no face kept a reference to it
                if (faces.isEmpty()) frame.recycle()

                processedFrames.incrementAndGet()
            }

            send(ProcessingState.Detecting(1f, allFaces.size))


            if (allFaces.isEmpty()) {
                send(ProcessingState.Error("No faces detected in this video."))
                return@channelFlow
            }

            // ── Phase 3: Clustering ──────────────────────────────────────────────
            send(ProcessingState.Clustering(0f))
            val clusters = withContext(Dispatchers.Default) {
                faceClusterer.cluster(allFaces)
            }
            send(ProcessingState.Clustering(1f))

            clusters.forEach { c ->

            }

            if (clusters.isEmpty()) {
                send(ProcessingState.Error("Could not identify any distinct people."))
                return@channelFlow
            }

            // ── Phase 4: Collage ─────────────────────────────────────────────────
            send(ProcessingState.BuildingCollage(0f))
            val collageBitmap = withContext(Dispatchers.Default) {
                collageRenderer.render(clusters)
            }
            send(ProcessingState.BuildingCollage(0.7f))

            val collagePath = withContext(Dispatchers.IO) {
                imageSaver.saveCollage(collageBitmap, "collage_${System.currentTimeMillis()}")
            }
            collageBitmap.recycle()

            send(ProcessingState.BuildingCollage(1f))
            send(ProcessingState.Done(clusters, collagePath))

            // ── Cleanup ──────────────────────────────────────────────────────────
            // Recycle bitmaps that are no longer needed.
            // Representative bitmaps are still referenced by PersonCluster in the UI.
            val repBitmaps = clusters.map { it.representative.bitmap }.toSet()
            allFaces
                .map { it.bitmap }
                .distinct()
                .filter { it !in repBitmaps && !it.isRecycled }
                .forEach { it.recycle() }

        }.flowOn(Dispatchers.IO)
    }
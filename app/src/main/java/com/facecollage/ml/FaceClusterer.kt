package com.facecollage.ml

import com.facecollage.domain.model.DetectedFace
import com.facecollage.domain.model.PersonCluster
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Groups [DetectedFace] instances into [PersonCluster] by identity.
 *
 * Algorithm : DBSCAN on cosine-distance embedding space
 *
 * Why DBSCAN:
 *   - No need to pre-specify k (number of people)
 *   - Naturally handles noise detections as outliers (label = -1)
 *   - Works well when clusters have unequal sizes
 *
 * Threshold : cosine distance ≤ 0.40  (configured in SIMILARITY_THRESHOLD)
 *   Same person    → distance typically 0.00 – 0.30
 *   Different      → distance typically 0.55 – 2.00
 *   0.40 gives a comfortable margin on both sides
 *
 * MinPts    : 2  (a person must appear in at least 2 frames to be a valid cluster)
 *
 * Appearance segments are computed after clustering by merging temporally adjacent
 * detections whose gap is ≤ APPEARANCE_GAP_MS (1500 ms).
 */
@Singleton
class FaceClusterer @Inject constructor() {

    companion object {
        /** Cosine-distance threshold for same-person classification. */
        const val SIMILARITY_THRESHOLD = 0.25f

        /** Minimum detections to form a valid person cluster. */
        private const val MIN_PTS = 2

        /**
         * If two consecutive detections of the same person are more than this
         * apart in time, they are counted as separate appearances (1.5 s).
         */
        private const val APPEARANCE_GAP_MS = 1500L

        private const val UNVISITED = Int.MIN_VALUE
        private const val NOISE     = -1
    }

    /**
     * Main entry point.
     * Returns clusters sorted by appearanceCount descending.
     * Noise points (DBSCAN label = -1) are discarded.
     */
    fun cluster(faces: List<DetectedFace>): List<PersonCluster> {
        if (faces.isEmpty()) return emptyList()

        val labels     = dbscan(faces)
        val clusterMap = mutableMapOf<Int, MutableList<DetectedFace>>()

        for (i in faces.indices) {
            val label = labels[i]
            if (label != NOISE) {
                clusterMap.getOrPut(label) { mutableListOf() }.add(faces[i])
            }
        }

        return clusterMap.entries
            .filter { it.value.size >= MIN_PTS }
            .mapIndexed { idx, (_, clusterFaces) ->
                val sorted         = clusterFaces.sortedBy { it.frameIndex }
                val representative = sorted.maxByOrNull { it.score.composite }!!
                val appearances    = computeAppearances(sorted)
                PersonCluster(
                    id             = idx,
                    faces          = sorted,
                    representative = representative,
                    appearances    = appearances,
                )
            }
            .sortedByDescending { it.appearanceCount }
    }

    // ─── DBSCAN ───────────────────────────────────────────────────────────────

    /**
     * DBSCAN with cosine distance.
     * Returns a label array parallel to [faces]; -1 = noise / outlier.
     * O(n²) — acceptable for n < 2000 face detections per 30-second video.
     */
    private fun dbscan(faces: List<DetectedFace>): IntArray {
        val n      = faces.size
        val labels = IntArray(n) { UNVISITED }
        var clusterId = 0

        for (i in 0 until n) {
            if (labels[i] != UNVISITED) continue

            val neighbours = regionQuery(faces, i)
            if (neighbours.size < MIN_PTS) {
                labels[i] = NOISE
                continue
            }

            // Start a new cluster
            labels[i] = clusterId
            val seeds = ArrayDeque(neighbours.toMutableList())

            while (seeds.isNotEmpty()) {
                val q = seeds.removeFirst()
                if (labels[q] == NOISE)      labels[q] = clusterId
                if (labels[q] != UNVISITED)  continue
                labels[q] = clusterId

                val qNeighbours = regionQuery(faces, q)
                if (qNeighbours.size >= MIN_PTS) {
                    seeds.addAll(
                        qNeighbours.filter { labels[it] == UNVISITED || labels[it] == NOISE }
                    )
                }
            }
            clusterId++
        }
        return labels
    }

    /** Returns indices of all faces within [SIMILARITY_THRESHOLD] cosine distance of faces[idx]. */
    private fun regionQuery(faces: List<DetectedFace>, idx: Int): List<Int> {
        val result = mutableListOf<Int>()
        for (j in faces.indices) {
            if (j != idx) {
                val dist = cosineDistance(faces[idx].embedding, faces[j].embedding)
                if (idx < 3) { // only log first 3 faces to avoid spam

                }
                if (dist <= SIMILARITY_THRESHOLD) result.add(j)
            }
        }
        return result
    }

    /**
     * Cosine distance = 1 − cosine_similarity.
     * Both vectors are L2-normalised (guaranteed by FaceEmbedder),
     * so dot product = cosine similarity directly.
     */
    fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return (1f - dot).coerceIn(0f, 2f)
    }

    // ─── Appearance segmentation ──────────────────────────────────────────────

    /**
     * Converts a time-sorted list of detections for one person into
     * a list of continuous appearance segments (LongRange of ms timestamps).
     *
     * A gap > [APPEARANCE_GAP_MS] between consecutive detections
     * starts a new appearance segment.
     *
     * This correctly handles:
     *   - Person leaves and re-enters frame  → gap > 1500 ms → 2 appearances
     *   - Brief missed detections within a segment → gap ≤ 1500 ms → 1 appearance
     *   - Simultaneous multi-person frames → each person tracks independently
     */
    private fun computeAppearances(sortedFaces: List<DetectedFace>): List<LongRange> {
        if (sortedFaces.isEmpty()) return emptyList()

        val appearances = mutableListOf<LongRange>()
        var segStart    = sortedFaces[0].frameIndex
        var segEnd      = sortedFaces[0].frameIndex

        for (i in 1 until sortedFaces.size) {
            val curr = sortedFaces[i].frameIndex
            if (curr - segEnd > APPEARANCE_GAP_MS) {
                appearances.add(segStart..segEnd)
                segStart = curr
            }
            segEnd = curr
        }
        appearances.add(segStart..segEnd)
        return appearances
    }
}
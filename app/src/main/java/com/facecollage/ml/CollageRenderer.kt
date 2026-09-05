package com.facecollage.ml

import android.graphics.*
import android.text.TextPaint
import com.facecollage.domain.model.PersonCluster
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.min

/**
 * Renders a high-quality collage in Instagram Story format (9:16, 1080×1920 px).
 *
 * Layout:
 *   - Dark purple gradient background with subtle bokeh dots
 *   - Header: "FACE COLLAGE" title + person count
 *   - 2-column grid of tiles (rows scale with person count)
 *   - Each tile: center-cropped full-frame representative shot with rounded corners
 *                + dark vignette overlay + appearance-count badge (×N)
 *   - Footer: total appearance count
 *
 * Tiles use the FULL video frame center-cropped to the tile size.
 * This is NOT a tight face crop, so tiles retain high resolution and visual context.
 */
@Singleton
class CollageRenderer @Inject constructor() {

    companion object {
        private const val COLLAGE_W       = 1080
        private const val COLLAGE_H       = 1920
        private const val CORNER_RADIUS   = 24f
        private const val TILE_PADDING    = 20
        private const val HEADER_HEIGHT   = 160
        private const val FOOTER_HEIGHT   = 120
        private const val BADGE_PADDING_H = 28f
        private const val BADGE_PADDING_V = 14f
        private const val COLS            = 2

        // Dark purple night theme
        private val BG_TOP   = Color.parseColor("#0D0D1A")
        private val BG_BOT   = Color.parseColor("#1A0D2E")
        private val ACCENT   = Color.parseColor("#C77DFF")
        private val BADGE_BG = Color.parseColor("#CC000000")
        private val WHITE    = Color.WHITE
    }

    /**
     * Renders and returns a 1080×1920 Bitmap collage.
     * Each cluster contributes exactly one tile (its representative frame).
     * The caller is responsible for recycling the returned bitmap after saving.
     */
    fun render(clusters: List<PersonCluster>): Bitmap {
        val bitmap = Bitmap.createBitmap(COLLAGE_W, COLLAGE_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawBackground(canvas)
        drawHeader(canvas, clusters.size)
        drawTiles(canvas, clusters)
        drawFooter(canvas, clusters.sumOf { it.appearanceCount })

        return bitmap
    }

    // ─── Background ──────────────────────────────────────────────────────────

    private fun drawBackground(canvas: Canvas) {
        val paint = Paint()
        paint.shader = LinearGradient(
            0f, 0f, 0f, COLLAGE_H.toFloat(),
            BG_TOP, BG_BOT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, COLLAGE_W.toFloat(), COLLAGE_H.toFloat(), paint)

        // Subtle bokeh dots — fixed seed so the pattern is deterministic
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#30C77DFF")
            style = Paint.Style.FILL
        }
        val rng = java.util.Random(42)
        repeat(30) {
            canvas.drawCircle(
                rng.nextFloat() * COLLAGE_W,
                rng.nextFloat() * COLLAGE_H,
                rng.nextFloat() * 60 + 10,
                dotPaint
            )
        }
    }

    // ─── Header ──────────────────────────────────────────────────────────────

    private fun drawHeader(canvas: Canvas, personCount: Int) {
        val accentLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = ACCENT
            strokeWidth = 4f
            style       = Paint.Style.STROKE
        }
        canvas.drawLine(
            60f, HEADER_HEIGHT - 20f,
            COLLAGE_W - 60f, HEADER_HEIGHT - 20f,
            accentLinePaint
        )

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = WHITE
            textSize  = 52f
            typeface  = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.15f
        }
        val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = ACCENT
            textSize  = 30f
            typeface  = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            letterSpacing = 0.20f
        }

        canvas.drawText("FACE COLLAGE", 60f, 90f, titlePaint)
        canvas.drawText(
            "$personCount UNIQUE ${if (personCount == 1) "PERSON" else "PEOPLE"} DETECTED",
            60f, 135f, subtitlePaint
        )
    }

    // ─── Tiles ────────────────────────────────────────────────────────────────

    private fun drawTiles(canvas: Canvas, clusters: List<PersonCluster>) {
        val availableH = COLLAGE_H - HEADER_HEIGHT - FOOTER_HEIGHT - TILE_PADDING * 2
        val rows       = ceil(clusters.size.toFloat() / COLS).toInt()
        val tileW      = (COLLAGE_W - TILE_PADDING * (COLS + 1)) / COLS
        val tileH      = (availableH - TILE_PADDING * (rows + 1)) / rows

        for ((index, cluster) in clusters.withIndex()) {
            val col    = index % COLS
            val row    = index / COLS
            val left   = (TILE_PADDING * (col + 1) + tileW * col).toFloat()
            val top    = (HEADER_HEIGHT + TILE_PADDING * (row + 1) + tileH * row).toFloat()
            val right  = left + tileW
            val bottom = top  + tileH

            drawTile(canvas, cluster, left, top, right, bottom)
        }
    }

    private fun drawTile(
        canvas: Canvas,
        cluster: PersonCluster,
        left: Float, top: Float, right: Float, bottom: Float
    ) {
        val tileW     = (right - left).toInt()
        val tileH     = (bottom - top).toInt()
        val srcBitmap = cluster.representative.bitmap   // full video frame

        // Center-crop the full frame to the tile dimensions
        val cropped = centerCropBitmap(srcBitmap, tileW, tileH)

        // Clip canvas to rounded rect
        val path = Path().apply {
            addRoundRect(left, top, right, bottom, CORNER_RADIUS, CORNER_RADIUS, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(path)

        // Draw the frame tile
        canvas.drawBitmap(cropped, left, top, Paint(Paint.FILTER_BITMAP_FLAG))

        // Vignette at the bottom of the tile (helps badge readability)
        val vignettePaint = Paint().apply {
            shader = LinearGradient(
                left, bottom - tileH * 0.45f, left, bottom,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#CC000000")),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(left, bottom - tileH * 0.45f, right, bottom, vignettePaint)

        canvas.restore()
        cropped.recycle()

        // Thin accent border
        canvas.drawRoundRect(
            left, top, right, bottom,
            CORNER_RADIUS, CORNER_RADIUS,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color       = Color.parseColor("#66C77DFF")
                style       = Paint.Style.STROKE
                strokeWidth = 2.5f
            }
        )

        // Appearance count badge (bottom-right corner of tile)
        drawBadge(canvas, cluster.appearanceCount, right - 12f, bottom - 12f)
    }

    private fun drawBadge(canvas: Canvas, count: Int, anchorRight: Float, anchorBottom: Float) {
        val text      = "×$count"
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val tw = textPaint.measureText(text)
        val th = textPaint.textSize

        val bLeft   = anchorRight  - tw - BADGE_PADDING_H * 2
        val bTop    = anchorBottom - th - BADGE_PADDING_V * 2
        val bRight  = anchorRight
        val bBottom = anchorBottom

        // Badge background (semi-transparent black pill)
        canvas.drawRoundRect(
            bLeft, bTop, bRight, bBottom, 20f, 20f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BADGE_BG }
        )

        // Accent left stripe
        canvas.drawRoundRect(
            bLeft, bTop, bLeft + 6f, bBottom, 3f, 3f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        )

        // Badge text
        canvas.drawText(text, bLeft + BADGE_PADDING_H + 4, bBottom - BADGE_PADDING_V, textPaint)
    }

    // ─── Footer ──────────────────────────────────────────────────────────────

    private fun drawFooter(canvas: Canvas, totalAppearances: Int) {
        val y = COLLAGE_H - FOOTER_HEIGHT
        canvas.drawRect(
            0f, y.toFloat(), COLLAGE_W.toFloat(), COLLAGE_H.toFloat(),
            Paint().apply {
                shader = LinearGradient(
                    0f, y.toFloat(), 0f, COLLAGE_H.toFloat(),
                    Color.TRANSPARENT, Color.parseColor("#AA000000"),
                    Shader.TileMode.CLAMP
                )
            }
        )

        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color         = Color.parseColor("#BBFFFFFF")
            textSize      = 28f
            letterSpacing = 0.10f
        }
        val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = ACCENT
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val label  = "TOTAL APPEARANCES  "
        val value  = "$totalAppearances"
        val lw     = labelPaint.measureText(label)
        val totalW = lw + valuePaint.measureText(value)
        val startX = (COLLAGE_W - totalW) / 2f

        canvas.drawText(label, startX, COLLAGE_H - 44f, labelPaint)
        canvas.drawText(value, startX + lw, COLLAGE_H - 44f, valuePaint)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Center-crops [src] to exactly [targetW]×[targetH] pixels.
     * Scales up/down so that the shorter dimension fits, then crops the longer one.
     * This is the standard "cover" scaling — never stretches, never leaves letterbox bars.
     */
    private fun centerCropBitmap(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val srcW    = src.width.toFloat()
        val srcH    = src.height.toFloat()
        val scaleX  = targetW / srcW
        val scaleY  = targetH / srcH
        val scale   = maxOf(scaleX, scaleY)
        val scaledW = (srcW * scale).toInt()
        val scaledH = (srcH * scale).toInt()
        val scaled  = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val cropX   = ((scaledW - targetW) / 2).coerceAtLeast(0)
        val cropY   = ((scaledH - targetH) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(
            scaled, cropX, cropY,
            min(targetW, scaledW - cropX),
            min(targetH, scaledH - cropY)
        )
        if (scaled !== src) scaled.recycle()
        return cropped
    }
}
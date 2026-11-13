package com.pdfmaster.app.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * A line of existing page content that can be edited. Coordinates are normalised to the displayed page
 * (0..1 from top-left). [size] is the font size as a fraction of the page width.
 */
data class DetectedLine(
    val page: Int, val l: Float, val t: Float, val r: Float, val b: Float,
    val text: String, val size: Float, val bold: Boolean, val fromOcr: Boolean,
)

/** Finds editable text on pages: the PDF's own text layer when present, otherwise on-device OCR. */
object TextEdit {

    /** Text-layer lines for every page, keyed by page index. Pages without real text are absent. */
    fun pdfLines(file: File): Map<Int, List<DetectedLine>> {
        val out = HashMap<Int, List<DetectedLine>>()
        PdfOps.open(file).use { doc ->
            for (pt in TextExtract.pages(doc)) {
                if (pt.lines.isEmpty()) continue
                val v = PdfOps.visual(doc.getPage(pt.index))
                out[pt.index] = pt.lines.mapNotNull { line ->
                    if (line.text.isBlank() || line.boxes.isEmpty()) return@mapNotNull null
                    val h = line.bottom - line.top
                    val size = if (line.size in (h * 0.6f)..(h * 3f)) line.size else h * 1.15f
                    val right = line.boxes.maxOf { it.right }
                    val top = line.bottom - size * 0.92f
                    val bottom = line.bottom + size * 0.24f
                    val pts = listOf(line.x to top, right to top, line.x to bottom, right to bottom)
                        .map { (x, y) -> PdfOps.unrotatedToVisual(v.rot, pt.width, pt.height, x, y) }
                    DetectedLine(
                        pt.index,
                        (pts.minOf { it.first } / v.w).coerceIn(0f, 1f), (pts.minOf { it.second } / v.h).coerceIn(0f, 1f),
                        (pts.maxOf { it.first } / v.w).coerceIn(0f, 1f), (pts.maxOf { it.second } / v.h).coerceIn(0f, 1f),
                        line.text, size / v.w, line.bold, false,
                    )
                }
            }
        }
        return out
    }

    /** Recognises text lines on a rendered page image. */
    suspend fun ocrLines(page: Int, bmp: Bitmap): List<DetectedLine> {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val res = rec.process(InputImage.fromBitmap(bmp, 0)).await()
            val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
            return res.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                if (line.text.isBlank()) return@mapNotNull null
                DetectedLine(
                    page, box.left / w, box.top / h, box.right / w, box.bottom / h,
                    line.text, box.height() / 1.2f / w, false, true,
                )
            }
        } finally { rec.close() }
    }

    /** Estimates (background, text) colours inside a normalised box of a rendered page. */
    fun sampleColors(bmp: Bitmap, l: Float, t: Float, r: Float, b: Float): Pair<Int, Int> {
        val x0 = (l * bmp.width).toInt().coerceIn(0, bmp.width - 1); val x1 = (r * bmp.width).toInt().coerceIn(x0 + 1, bmp.width)
        val y0 = (t * bmp.height).toInt().coerceIn(0, bmp.height - 1); val y1 = (b * bmp.height).toInt().coerceIn(y0 + 1, bmp.height)
        val counts = HashMap<Int, Int>()
        val samples = ArrayList<Int>()
        val stepX = max(1, (x1 - x0) / 120); val stepY = max(1, (y1 - y0) / 30)
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = bmp.getPixel(x, y)
                samples += c
                val q = Color.rgb(Color.red(c) / 16 * 16, Color.green(c) / 16 * 16, Color.blue(c) / 16 * 16)
                counts[q] = (counts[q] ?: 0) + 1
                x += stepX
            }
            y += stepY
        }
        // also sample a ring just outside the box: the most reliable background
        for (xx in x0 until x1 step max(1, (x1 - x0) / 40)) for (yy in listOf(y0 - 2, y1 + 1)) if (yy in 0 until bmp.height) {
            val c = bmp.getPixel(xx, yy)
            val q = Color.rgb(Color.red(c) / 16 * 16, Color.green(c) / 16 * 16, Color.blue(c) / 16 * 16)
            counts[q] = (counts[q] ?: 0) + 3
        }
        val bgQ = counts.maxByOrNull { it.value }?.key ?: Color.WHITE
        val bgPixels = samples.filter { dist(it, bgQ) < 40 }
        val bg = if (bgPixels.isEmpty()) bgQ else avg(bgPixels)
        val ink = samples.filter { dist(it, bg) > 110 }
        val fg = if (ink.size < 3) (if (luma(bg) > 128) Color.BLACK else Color.WHITE) else avg(ink.sortedByDescending { dist(it, bg) }.take(max(3, ink.size / 3)))
        return bg to fg
    }

    private fun luma(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
    private fun dist(a: Int, b: Int): Int = kotlin.math.abs(Color.red(a) - Color.red(b)) + kotlin.math.abs(Color.green(a) - Color.green(b)) + kotlin.math.abs(Color.blue(a) - Color.blue(b))
    private fun avg(list: List<Int>): Int = Color.rgb(list.sumOf { Color.red(it) } / list.size, list.sumOf { Color.green(it) } / list.size, list.sumOf { Color.blue(it) } / list.size)

    @Suppress("unused") private fun clamp(v: Float) = min(1f, max(0f, v))
}

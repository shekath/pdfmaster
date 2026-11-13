package com.pdfmaster.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Content blocks for generating flowing PDF documents with Android's text engine (all scripts supported). */
sealed class Block {
    data class Para(val text: CharSequence, val size: Float = 11f, val bold: Boolean = false, val align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, val spaceBefore: Float = 0f, val spaceAfter: Float = 6f, val color: Int = Color.BLACK) : Block()
    data class Img(val bitmap: Bitmap) : Block()
    data class Table(val rows: List<List<String>>, val header: Boolean = false, val fontSize: Float = 9f) : Block()
    data object PageBreak : Block()
}

class TextPdfWriter(private val pageW: Int = 595, private val pageH: Int = 842, private val margin: Float = 50f) {
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = 0f
    private var pageNo = 0
    private val contentW get() = pageW - 2 * margin
    private val bottom get() = pageH - margin

    private fun newPage() {
        page?.let { doc.finishPage(it) }
        pageNo++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        page = p; canvas = p.canvas; y = margin
    }

    private fun ensurePage() { if (page == null) newPage() }

    private fun layout(text: CharSequence, size: Float, bold: Boolean, align: Layout.Alignment, width: Int, color: Int = Color.BLACK): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setAlignment(align).setLineSpacing(0f, 1.15f).setIncludePad(false).build()
    }

    fun add(b: Block) {
        ensurePage()
        when (b) {
            is Block.PageBreak -> newPage()
            is Block.Para -> para(b)
            is Block.Img -> img(b.bitmap)
            is Block.Table -> table(b)
        }
    }

    fun addAll(blocks: List<Block>) = blocks.forEach { add(it) }

    private fun para(b: Block.Para) {
        if (b.text.isEmpty()) { y += b.size * 0.8f; return }
        y += b.spaceBefore
        val l = layout(b.text, b.size, b.bold, b.align, contentW.toInt(), b.color)
        var line = 0
        while (line < l.lineCount) {
            if (y + (l.getLineBottom(line) - l.getLineTop(line)) > bottom) newPage()
            // how many lines fit on this page
            var end = line
            val startTop = l.getLineTop(line)
            while (end < l.lineCount && y + (l.getLineBottom(end) - startTop) <= bottom) end++
            if (end == line) end = line + 1
            val c = canvas!!
            c.save()
            c.translate(margin, y - startTop)
            c.clipRect(0f, startTop.toFloat(), contentW, l.getLineBottom(end - 1).toFloat())
            l.draw(c)
            c.restore()
            y += l.getLineBottom(end - 1) - startTop
            line = end
        }
        y += b.spaceAfter
    }

    private fun img(bmp: Bitmap) {
        var w = min(contentW, bmp.width * 0.75f)
        var h = w * bmp.height / bmp.width
        val maxH = bottom - margin
        if (h > maxH) { h = maxH; w = h * bmp.width / bmp.height }
        if (y + h > bottom) newPage()
        val left = margin + (contentW - w) / 2
        canvas!!.drawBitmap(bmp, null, RectF(left, y, left + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
        y += h + 8f
    }

    private fun table(t: Block.Table) {
        if (t.rows.isEmpty()) return
        val cols = t.rows.maxOf { it.size }
        if (cols == 0) return
        val weights = FloatArray(cols) { c ->
            t.rows.take(200).maxOf { r -> min(40, (r.getOrNull(c) ?: "").length).toFloat() }.coerceAtLeast(3f)
        }
        val total = weights.sum()
        val widths = weights.map { it / total * contentW }
        val pad = 3f
        val grid = Paint().apply { color = Color.rgb(190, 196, 206); style = Paint.Style.STROKE; strokeWidth = 0.5f }
        val headFill = Paint().apply { color = Color.rgb(236, 240, 247) }
        fun rowLayouts(r: List<String>, bold: Boolean) = (0 until cols).map { c ->
            layout(r.getOrNull(c) ?: "", t.fontSize, bold, Layout.Alignment.ALIGN_NORMAL, (widths[c] - 2 * pad).toInt())
        }
        t.rows.forEachIndexed { ri, r ->
            val isHead = t.header && ri == 0
            val ls = rowLayouts(r, isHead)
            val h = min(ls.maxOf { it.height }.toFloat() + 2 * pad, bottom - margin)
            if (y + h > bottom) newPage()
            val c = canvas!!
            var x = margin
            if (isHead) c.drawRect(margin, y, margin + contentW, y + h, headFill)
            ls.forEachIndexed { ci, l ->
                c.save(); c.clipRect(x, y, x + widths[ci], y + h); c.translate(x + pad, y + pad); l.draw(c); c.restore()
                c.drawRect(x, y, x + widths[ci], y + h, grid)
                x += widths[ci]
            }
            y += h
        }
        y += 10f
    }

    fun save(out: File) {
        ensurePage()
        page?.let { doc.finishPage(it) }
        page = null
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    companion object {
        fun a4Landscape() = TextPdfWriter(842, 595, 36f)
    }
}

/** Draws a bitmap page-filling into a PdfDocument page; used for slide rendering. */
internal fun Canvas.drawFit(bmp: Bitmap, dst: RectF) {
    drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), dst, Paint(Paint.FILTER_BITMAP_FLAG))
}

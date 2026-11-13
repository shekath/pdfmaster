package com.pdfmaster.app.engine

import android.content.Context
import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDOutputIntent
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.abs
import kotlin.math.max

/** Application context holder for engine code that needs assets. */
object AppCtx { lateinit var app: Context }

object SRGB {
    fun outputIntent(doc: PDDocument): PDOutputIntent? = runCatching {
        AppCtx.app.assets.open("srgb.icc").use { input ->
            PDOutputIntent(doc, input).apply {
                info = "sRGB IEC61966-2.1"
                outputCondition = "sRGB IEC61966-2.1"
                outputConditionIdentifier = "sRGB IEC61966-2.1"
                registryName = "http://www.color.org"
            }
        }
    }.getOrNull()
}

/** A cell is a run of text separated from its neighbours by a wide gap (used for table detection). */
data class Cell(val x0: Float, val x1: Float, val text: String)

/**
 * One visual line of text. Coordinates are unrotated page space with the origin top-left, in points.
 * [boxes] has one rectangle per character of [text].
 */
data class TextLine(val text: String, val boxes: List<RectF>, val x: Float, val top: Float, val bottom: Float, val size: Float, val bold: Boolean, val cells: List<Cell>)

data class PageText(val index: Int, val width: Float, val height: Float, val lines: List<TextLine>) {
    val plain: String get() = lines.joinToString("\n") { it.text }

    /** Lines grouped into paragraphs using vertical gaps. */
    fun paragraphs(): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var prev: TextLine? = null
        for (l in lines) {
            val p = prev
            val newPara = p == null || (l.top - p.bottom) > max(p.size, l.size) * 0.9f || abs(l.size - p.size) > 1.5f
            if (newPara && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            if (sb.isNotEmpty()) {
                if (sb.endsWith("-")) sb.setLength(sb.length - 1) else sb.append(' ')
            }
            sb.append(l.text)
            prev = l
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }
}

object TextExtract {

    private class Glyph(val ch: String, val x: Float, val y: Float, val w: Float, val h: Float, val size: Float, val bold: Boolean)

    private class Collector : PDFTextStripper() {
        val glyphs = ArrayList<Glyph>()
        init { sortByPosition = true }
        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            textPositions?.forEach { tp ->
                val u = tp.unicode ?: return@forEach
                if (u.isBlank()) return@forEach
                val size = if (tp.fontSizeInPt > 0) tp.fontSizeInPt else tp.heightDir
                val fontName = runCatching { tp.font?.name ?: "" }.getOrDefault("")
                glyphs += Glyph(u, tp.xDirAdj, tp.yDirAdj, tp.widthDirAdj, max(tp.heightDir, size * 0.7f), size,
                    fontName.contains("Bold", true) || fontName.contains("Black", true) || fontName.contains("Heavy", true))
            }
        }
    }

    fun pages(doc: PDDocument, range: IntRange? = null): List<PageText> {
        val c = Collector()
        val pages = range ?: (0 until doc.numberOfPages)
        return pages.map { i ->
            c.glyphs.clear()
            c.startPage = i + 1
            c.endPage = i + 1
            c.getText(doc)
            val box = doc.getPage(i).cropBox
            PageText(i, box.width, box.height, buildLines(c.glyphs))
        }
    }

    private fun buildLines(glyphs: List<Glyph>): List<TextLine> {
        if (glyphs.isEmpty()) return emptyList()
        val sorted = glyphs.sortedWith(compareBy({ it.y }, { it.x }))
        val groups = ArrayList<MutableList<Glyph>>()
        var cur = mutableListOf<Glyph>()
        var curY = sorted.first().y
        for (g in sorted) {
            val tol = max(2f, g.h * 0.45f)
            if (cur.isNotEmpty() && abs(g.y - curY) > tol) { groups += cur; cur = mutableListOf() }
            if (cur.isEmpty()) curY = g.y
            cur += g
        }
        if (cur.isNotEmpty()) groups += cur
        return groups.map { line(it.sortedBy { g -> g.x }) }
    }

    private fun line(gs: List<Glyph>): TextLine {
        val avgW = gs.map { it.w / max(1, it.ch.length) }.filter { it > 0 }.average().toFloat().takeIf { it > 0 } ?: 4f
        val size = gs.map { it.size }.sorted()[gs.size / 2]
        val sb = StringBuilder()
        val boxes = ArrayList<RectF>()
        val cells = ArrayList<Cell>()
        var cellStart = gs.first().x
        val cellSb = StringBuilder()
        var prev: Glyph? = null
        for (g in gs) {
            val p = prev
            if (p != null) {
                val gap = g.x - (p.x + p.w)
                if (gap > max(avgW * 2.2f, size * 1.1f)) {
                    cells += Cell(cellStart, p.x + p.w, cellSb.toString().trim()); cellSb.clear(); cellStart = g.x
                    sb.append(' '); boxes += RectF(p.x + p.w, p.y - p.h, g.x, p.y)
                } else if (gap > avgW * 0.28f) {
                    sb.append(' '); boxes += RectF(p.x + p.w, p.y - p.h, g.x, p.y); cellSb.append(' ')
                }
            }
            sb.append(g.ch); cellSb.append(g.ch)
            repeat(g.ch.length) { boxes += RectF(g.x, g.y - g.h, g.x + g.w, g.y + g.h * 0.2f) }
            prev = g
        }
        val last = gs.last()
        cells += Cell(cellStart, last.x + last.w, cellSb.toString().trim())
        val top = gs.minOf { it.y - it.h }
        val bottom = gs.maxOf { it.y }
        val bold = gs.count { it.bold } > gs.size / 2
        return TextLine(sb.toString(), boxes, gs.first().x, top, bottom, size, bold, cells)
    }

    fun plainText(doc: PDDocument): String = pages(doc).joinToString("\n\n") { it.plain }
}

package com.pdfmaster.app.engine

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * Removes real text from a page's content stream inside given boxes, so edited text is truly replaced
 * (not just covered). Works on the page's own content; text inside form XObjects is left alone.
 */
object TextRemover {

    /** A box in displayed, normalised page coordinates. */
    data class Area(val l: Float, val t: Float, val r: Float, val b: Float)

    private class Tracker(val page: PDPage, val areas: List<Area>) : PDFTextStripper() {
        var opIndex = -1
        var depth = 0
        val total = HashMap<Int, Int>()
        val inside = HashMap<Int, Int>()
        val hitAreas = HashMap<Int, MutableSet<Int>>()
        private val v = PdfOps.visual(page)
        private val box = page.cropBox

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            if (depth == 0) opIndex++
            super.processOperator(operator, operands)
        }

        override fun showForm(form: PDFormXObject) {
            depth++
            try { super.showForm(form) } finally { depth-- }
        }

        override fun processTextPosition(text: TextPosition) {
            if (depth == 0 && opIndex >= 0) {
                total[opIndex] = (total[opIndex] ?: 0) + 1
                val cx = text.xDirAdj + text.widthDirAdj / 2
                val cy = text.yDirAdj - text.heightDir / 2
                val (vx, vy) = PdfOps.unrotatedToVisual(v.rot, box.width, box.height, cx, cy)
                val nx = vx / v.w; val ny = vy / v.h
                areas.forEachIndexed { ai, a ->
                    if (nx in a.l - 0.004f..a.r + 0.004f && ny in a.t - 0.004f..a.b + 0.004f) {
                        inside[opIndex] = (inside[opIndex] ?: 0) + 1
                        hitAreas.getOrPut(opIndex) { HashSet() }.add(ai)
                    }
                }
            }
            super.processTextPosition(text)
        }
    }

    /**
     * Blanks every text-showing operator whose glyphs are mostly inside [areas].
     * @return indexes of areas where text was removed.
     */
    fun remove(doc: PDDocument, pageIndex: Int, areas: List<Area>): Set<Int> {
        if (areas.isEmpty()) return emptySet()
        val page = doc.getPage(pageIndex)
        val tracker = Tracker(page, areas).apply { sortByPosition = false; startPage = pageIndex + 1; endPage = pageIndex + 1 }
        tracker.getText(doc)
        val drop = tracker.total.keys.filter { op -> (tracker.inside[op] ?: 0) * 10 >= (tracker.total[op] ?: 1) * 6 }.toSet()
        if (drop.isEmpty()) return emptySet()

        val parser = PDFStreamParser(page)
        parser.parse()
        val tokens = parser.tokens
        var op = -1
        val pending = ArrayList<Int>() // token indexes of operands for the current operator
        var removedCount = 0
        for ((i, tok) in tokens.withIndex()) {
            if (tok is Operator) {
                op++
                if (op in drop) {
                    when (tok.name) {
                        "Tj", "'" -> pending.lastOrNull()?.let { tokens[it] = COSString(ByteArray(0)); removedCount++ }
                        "\"" -> pending.lastOrNull()?.let { tokens[it] = COSString(ByteArray(0)); removedCount++ }
                        "TJ" -> pending.lastOrNull()?.let { tokens[it] = COSArray(); removedCount++ }
                    }
                }
                pending.clear()
            } else pending += i
        }
        if (removedCount == 0) return emptySet()
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
        page.setContents(stream)
        return drop.flatMap { tracker.hitAreas[it] ?: emptySet() }.toSet()
    }
}

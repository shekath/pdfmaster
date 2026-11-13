package com.pdfmaster.app.engine

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** PDF -> Office conversions and Office -> PDF entry points. */
object Convert {

    private fun jpeg(bmp: Bitmap, q: Int = 85): ByteArray = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    fun pdfToWord(input: File, out: File, exactLook: Boolean, progress: (Float) -> Unit) {
        val w = Office.DocxWriter()
        if (exactLook) {
            Render.Doc(input).use { r ->
                for (i in 0 until r.pageCount) {
                    val size = r.pageSize(i)
                    val bmp = r.render(i, (size.width / 72f * 150).roundToInt())
                    // fit within A4 minus small margins
                    val maxW = 595f - 2 * 20f; val maxH = 842f - 2 * 20f - 4f
                    val s = minOf(maxW / size.width, maxH / size.height)
                    if (i > 0) w.pageBreak()
                    w.image(jpeg(bmp), size.width * s, size.height * s)
                    bmp.recycle()
                    progress((i + 1f) / r.pageCount)
                }
            }
            w.save(out, 20f)
            return
        }
        PdfOps.open(input).use { doc ->
            val pages = TextExtract.pages(doc)
            val body = pages.flatMap { it.lines }.map { it.size }.groupingBy { (it * 2).roundToInt() }.eachCount().maxByOrNull { it.value }?.key?.div(2f) ?: 11f
            pages.forEachIndexed { pi, pt ->
                if (pi > 0) w.pageBreak()
                var prev: TextLine? = null
                val sb = StringBuilder(); var sbSize = body; var sbBold = false
                fun flush() { if (sb.isNotEmpty()) { w.paragraph(sb.toString(), sbBold, if (kotlin.math.abs(sbSize - body) > 1f) sbSize else null); sb.clear() } }
                for (l in pt.lines) {
                    val p = prev
                    val newPara = p == null || (l.top - p.bottom) > max(p.size, l.size) * 0.9f || kotlin.math.abs(l.size - p.size) > 1.5f || l.bold != p.bold
                    if (newPara) { flush(); sbSize = l.size; sbBold = l.bold }
                    else if (sb.isNotEmpty()) { if (sb.endsWith("-")) sb.setLength(sb.length - 1) else sb.append(' ') }
                    sb.append(l.text)
                    prev = l
                }
                flush()
                if (pt.lines.isEmpty()) w.paragraph("(No text found on page ${pi + 1}. If this is a scan, run OCR first.)")
                progress((pi + 1f) / pages.size)
            }
        }
        w.save(out)
    }

    fun pdfToExcel(input: File, out: File, onePerPage: Boolean, progress: (Float) -> Unit) {
        val x = Office.XlsxWriter()
        PdfOps.open(input).use { doc ->
            val pages = TextExtract.pages(doc)
            val all = ArrayList<List<String>>()
            pages.forEachIndexed { i, pt ->
                val rows = tableRows(pt)
                if (onePerPage) x.sheet("Page ${i + 1}", rows) else { all.addAll(rows); if (rows.isNotEmpty()) all.add(emptyList()) }
                progress((i + 1f) / pages.size)
            }
            if (!onePerPage) x.sheet("Data", all)
        }
        x.save(out)
    }

    /** Aligns the cells of every line to shared column positions on the page. */
    fun tableRows(pt: PageText): List<List<String>> {
        if (pt.lines.isEmpty()) return emptyList()
        val starts = pt.lines.flatMap { l -> l.cells.map { it.x0 } }.sorted()
        val cols = ArrayList<Float>()
        for (s in starts) if (cols.isEmpty() || s - cols.last() > 12f) cols += s
        return pt.lines.map { l ->
            val row = MutableList(cols.size) { "" }
            for (c in l.cells) {
                var idx = cols.indexOfLast { it <= c.x0 + 6f }
                if (idx < 0) idx = 0
                row[idx] = if (row[idx].isEmpty()) c.text else row[idx] + " " + c.text
            }
            while (row.isNotEmpty() && row.last().isEmpty()) row.removeAt(row.size - 1)
            row
        }
    }

    fun pdfToPowerPoint(input: File, out: File, editable: Boolean, progress: (Float) -> Unit) {
        Render.Doc(input).use { r ->
            val first = r.pageSize(0)
            val emuPerPt = 12700f
            // PowerPoint slide limits: 1 - 56 inches per side
            val fit = minOf(1f, 4032f / max(first.width, first.height)).let { if (max(first.width, first.height) * it < 72f) 72f / max(first.width, first.height) else it }
            val cx = (first.width * fit * emuPerPt).toLong(); val cy = (first.height * fit * emuPerPt).toLong()
            val w = Office.PptxWriter(cx, cy)
            val textPages = if (editable) PdfOps.open(input).use { TextExtract.pages(it) } else emptyList()
            for (i in 0 until r.pageCount) {
                val size = r.pageSize(i)
                val s = minOf(cx / (size.width * emuPerPt), cy / (size.height * emuPerPt))
                val pw = (size.width * emuPerPt * s).toLong(); val ph = (size.height * emuPerPt * s).toLong()
                val ox = (cx - pw) / 2; val oy = (cy - ph) / 2
                if (editable) {
                    val pt = textPages.getOrNull(i)
                    val k = emuPerPt * s * (size.width / (pt?.width ?: size.width))
                    val items = pt?.lines?.map { l ->
                        val width = ((l.boxes.lastOrNull()?.right ?: (l.x + 10f)) - l.x).coerceAtLeast(10f)
                        Office.PptxWriter.Item.TextBox(l.text, ox + (l.x * k).toLong(), oy + (l.top * k).toLong(), (width * k * 1.08f).toLong(), ((l.bottom - l.top) * k * 1.3f).toLong().coerceAtLeast(12700), l.size * s, l.bold)
                    } ?: emptyList()
                    w.slide(items)
                } else {
                    val bmp = r.render(i, (size.width / 72f * 160).roundToInt())
                    w.slide(listOf(Office.PptxWriter.Item.Picture(jpeg(bmp), ox, oy, pw, ph)))
                    bmp.recycle()
                }
                progress((i + 1f) / r.pageCount)
            }
            w.save(out)
        }
    }

    fun wordToPdf(input: File, out: File) {
        val blocks = when (input.extension.lowercase()) {
            "docx", "docm", "dotx" -> Office.docxToBlocks(input)
            "txt", "md", "text", "log" -> Office.textToBlocks(input.readText())
            "rtf" -> Office.textToBlocks(stripRtf(input.readText()))
            "doc" -> throw IllegalArgumentException("Old .doc files aren't supported. Save the file as .docx and try again.")
            else -> throw IllegalArgumentException("Unsupported file type: .${input.extension}")
        }
        TextPdfWriter().apply { addAll(blocks.ifEmpty { listOf(Block.Para(" ")) }); save(out) }
    }

    fun excelToPdf(input: File, out: File) {
        val sheets = when (input.extension.lowercase()) {
            "xlsx", "xlsm" -> Office.xlsxSheets(input)
            "csv", "tsv" -> listOf(Office.Sheet(input.nameWithoutExtension, Office.csvRows(input.readText())))
            "xls" -> throw IllegalArgumentException("Old .xls files aren't supported. Save the file as .xlsx and try again.")
            else -> throw IllegalArgumentException("Unsupported file type: .${input.extension}")
        }
        Office.sheetsToPdf(sheets, out)
    }

    fun pptToPdf(input: File, out: File, progress: (Float) -> Unit) {
        when (input.extension.lowercase()) {
            "pptx", "ppsx", "pptm" -> Office.pptxToPdf(input, out, progress)
            "ppt" -> throw IllegalArgumentException("Old .ppt files aren't supported. Save the file as .pptx and try again.")
            else -> throw IllegalArgumentException("Unsupported file type: .${input.extension}")
        }
    }

    private fun stripRtf(rtf: String): String = rtf
        .replace(Regex("\\\\par[d]?"), "\n")
        .replace(Regex("\\{\\\\\\*[^{}]*\\}"), "")
        .replace(Regex("\\\\'[0-9a-fA-F]{2}"), "")
        .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
        .replace(Regex("[{}]"), "")
        .trim()
}

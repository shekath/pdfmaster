package com.pdfmaster.app.engine

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

/** Reading and writing Office Open XML (docx / xlsx / pptx) without external libraries. */
object Office {

    // ---------- shared helpers ----------

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(ch)
                ch < ' ' || ch == '￾' || ch == '￿' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun parser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(input, null)
    }

    private fun XmlPullParser.local(): String = name.substringAfter(':')
    private fun XmlPullParser.attr(local: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i).substringAfter(':') == local) return getAttributeValue(i)
        return null
    }

    private fun ZipFile.read(path: String): InputStream? = getEntry(path.removePrefix("/"))?.let { getInputStream(it) }

    /** Relationship id -> target path, resolved against [baseDir]. */
    private fun rels(zip: ZipFile, relsPath: String, baseDir: String): Map<String, String> {
        val map = HashMap<String, String>()
        val input = zip.read(relsPath) ?: return map
        input.use {
            val p = parser(it)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.local() == "Relationship") {
                    val id = p.attr("Id") ?: continue
                    val target = p.attr("Target") ?: continue
                    map[id] = resolve(baseDir, target)
                }
            }
        }
        return map
    }

    private fun resolve(baseDir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = (if (baseDir.isEmpty()) emptyList() else baseDir.split('/')).toMutableList()
        for (seg in target.split('/')) when (seg) { ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); ".", "" -> {}; else -> parts += seg }
        return parts.joinToString("/")
    }

    private fun bool(v: String?) = v == null || (v != "0" && v != "false" && v != "off")

    private fun color(hex: String?): Int? = hex?.takeIf { it.length == 6 && it != "auto" }?.let { runCatching { Color.parseColor("#$it") }.getOrNull() }

    // ---------- DOCX -> blocks ----------

    fun docxToBlocks(file: File): List<Block> {
        val blocks = ArrayList<Block>()
        ZipFile(file).use { zip ->
            val rel = rels(zip, "word/_rels/document.xml.rels", "word")
            val input = zip.read("word/document.xml") ?: throw IllegalArgumentException("This is not a valid Word (.docx) document")
            input.use {
                val p = parser(it)
                var para: SpannableStringBuilder? = null
                var paraStyle = ""
                var align = Layout.Alignment.ALIGN_NORMAL
                var list = false
                var bold = false; var italic = false; var underline = false; var size = 0f; var col: Int? = null
                var runStart = 0
                var inRunProps = false
                var table: MutableList<MutableList<String>>? = null
                var row: MutableList<String>? = null
                var cell: StringBuilder? = null
                var tableDepth = 0
                val pendingImages = ArrayList<String>()
                var pageBreakAfter = false

                fun flushPara() {
                    val sp = para ?: return
                    val c = cell
                    if (c != null) {
                        if (c.isNotEmpty()) c.append('\n'); c.append(sp.toString())
                    } else {
                        val (sz, b) = when {
                            paraStyle.contains("Title", true) -> 24f to true
                            paraStyle.matches(Regex("(?i).*heading ?1.*")) -> 18f to true
                            paraStyle.matches(Regex("(?i).*heading ?2.*")) -> 15f to true
                            paraStyle.matches(Regex("(?i).*heading ?[3-9].*")) -> 13f to true
                            else -> 11f to false
                        }
                        val text: CharSequence = if (list) SpannableStringBuilder("•  ").append(sp) else sp
                        blocks += Block.Para(text, sz, b, align, spaceBefore = if (b) 8f else 0f, spaceAfter = if (b) 6f else 5f)
                        pendingImages.forEach { path ->
                            zip.read(path)?.use { s -> BitmapFactory.decodeStream(s) }?.let { bmp -> blocks += Block.Img(bmp) }
                        }
                        if (pageBreakAfter) blocks += Block.PageBreak
                    }
                    pendingImages.clear()
                    pageBreakAfter = false
                    para = null
                }

                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> when (p.local()) {
                            "tbl" -> { tableDepth++; if (tableDepth == 1) { flushPara(); table = mutableListOf() } }
                            "tr" -> if (tableDepth == 1) row = mutableListOf()
                            "tc" -> if (tableDepth == 1) cell = StringBuilder()
                            "p" -> if (para == null) {
                                para = SpannableStringBuilder(); paraStyle = ""; align = Layout.Alignment.ALIGN_NORMAL; list = false
                            }
                            "tabs" -> skip(p)
                            "pStyle" -> paraStyle = p.attr("val") ?: ""
                            "numPr" -> list = true
                            "jc" -> if (!inRunProps) align = when (p.attr("val")) {
                                "center" -> Layout.Alignment.ALIGN_CENTER
                                "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
                                else -> Layout.Alignment.ALIGN_NORMAL
                            }
                            "r" -> { bold = false; italic = false; underline = false; size = 0f; col = null; runStart = para?.length ?: 0 }
                            "rPr" -> inRunProps = true
                            "b" -> if (inRunProps) bold = bool(p.attr("val"))
                            "i" -> if (inRunProps) italic = bool(p.attr("val"))
                            "u" -> if (inRunProps) underline = p.attr("val") != "none"
                            "sz" -> if (inRunProps) size = (p.attr("val")?.toFloatOrNull() ?: 0f) / 2f
                            "color" -> if (inRunProps) col = color(p.attr("val"))
                            "t" -> { val txt = p.nextText(); para?.append(txt) }
                            "tab" -> if (!inRunProps) para?.append('\t')
                            "br" -> if (p.attr("type") == "page") pageBreakAfter = true else para?.append('\n')
                            "blip" -> p.attr("embed")?.let { id -> rel[id]?.let { pendingImages += it } }
                            "imagedata" -> p.attr("id")?.let { id -> rel[id]?.let { pendingImages += it } }
                        }
                        XmlPullParser.END_TAG -> when (p.local()) {
                            "rPr" -> inRunProps = false
                            "r" -> para?.let { sp ->
                                val end = sp.length
                                if (end > runStart) {
                                    val style = (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
                                    if (style != 0) sp.setSpan(StyleSpan(style), runStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    if (underline) sp.setSpan(UnderlineSpan(), runStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    if (size > 0) sp.setSpan(AbsoluteSizeSpan(size.toInt()), runStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    col?.let { c -> sp.setSpan(ForegroundColorSpan(c), runStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                                }
                            }
                            "p" -> flushPara()
                            "tc" -> if (tableDepth == 1) { flushPara(); row?.add(cell?.toString() ?: ""); cell = null }
                            "tr" -> if (tableDepth == 1) { row?.let { table?.add(it) }; row = null }
                            "tbl" -> {
                                if (tableDepth == 1) { table?.takeIf { it.isNotEmpty() }?.let { blocks += Block.Table(it, header = true) }; table = null }
                                tableDepth--
                            }
                            "body" -> flushPara()
                        }
                    }
                }
            }
        }
        return blocks
    }

    fun textToBlocks(text: String): List<Block> =
        text.replace("\r\n", "\n").split(Regex("\n{2,}")).map { Block.Para(it.trim('\n'), 11f) }

    fun csvRows(text: String): List<List<String>> {
        val delim = listOf(',', ';', '\t').maxBy { d -> text.lineSequence().take(5).sumOf { l -> l.count { it == d } } }
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == delim -> { row += cell.toString(); cell.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += cell.toString(); cell.clear(); rows += row; row = ArrayList()
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }

    // ---------- XLSX -> sheets ----------

    data class Sheet(val name: String, val rows: List<List<String>>)

    fun xlsxSheets(file: File): List<Sheet> {
        ZipFile(file).use { zip ->
            val shared = ArrayList<String>()
            zip.read("xl/sharedStrings.xml")?.use {
                val p = parser(it); val sb = StringBuilder(); var inSi = false
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG) when (p.local()) {
                        "si" -> { inSi = true; sb.clear() }
                        "t" -> if (inSi) sb.append(p.nextText())
                        "rPh" -> skip(p)
                    } else if (p.eventType == XmlPullParser.END_TAG && p.local() == "si") { shared += sb.toString(); inSi = false }
                }
            }
            val rel = rels(zip, "xl/_rels/workbook.xml.rels", "xl")
            val sheets = ArrayList<Pair<String, String>>()
            zip.read("xl/workbook.xml")?.use {
                val p = parser(it)
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.local() == "sheet") {
                        val path = p.attr("id")?.let { id -> rel[id] } ?: continue
                        sheets += (p.attr("name") ?: "Sheet") to path
                    }
                }
            } ?: throw IllegalArgumentException("This is not a valid Excel (.xlsx) workbook")
            return sheets.map { (name, path) -> Sheet(name, readSheet(zip, path, shared)) }
        }
    }

    private fun skip(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) when (p.next()) { XmlPullParser.START_TAG -> depth++; XmlPullParser.END_TAG -> depth--; XmlPullParser.END_DOCUMENT -> return }
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break }
        return n - 1
    }

    private fun readSheet(zip: ZipFile, path: String, shared: List<String>): List<List<String>> {
        val rows = sortedMapOf<Int, MutableMap<Int, String>>()
        zip.read(path)?.use {
            val p = parser(it)
            var r = 0; var c = 0; var type: String? = null; var value: String? = null; var rowIdx = 0; var col = 0
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) when (p.local()) {
                    "row" -> { rowIdx = (p.attr("r")?.toIntOrNull() ?: (rowIdx + 1)); col = 0 }
                    "c" -> {
                        type = p.attr("t"); value = null
                        val ref = p.attr("r")
                        c = if (ref != null) colIndex(ref) else col
                        r = rowIdx
                    }
                    "v" -> value = p.nextText()
                    "t" -> value = (value ?: "") + p.nextText()
                } else if (p.eventType == XmlPullParser.END_TAG && p.local() == "c") {
                    val v = value
                    if (v != null) {
                        val text = when (type) {
                            "s" -> shared.getOrNull(v.trim().toIntOrNull() ?: -1) ?: ""
                            "b" -> if (v == "1") "TRUE" else "FALSE"
                            "str", "inlineStr", "e" -> v
                            else -> v.toDoubleOrNull()?.let { d -> if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toBigDecimal().stripTrailingZeros().toPlainString() } ?: v
                        }
                        if (text.isNotEmpty()) rows.getOrPut(r) { sortedMapOf() }[c] = text
                    }
                    col = c + 1
                }
            }
        }
        if (rows.isEmpty()) return emptyList()
        val maxCol = rows.values.maxOf { it.keys.maxOrNull() ?: 0 }
        val minCol = rows.values.minOf { it.keys.minOrNull() ?: 0 }
        return rows.values.map { m -> (minCol..maxCol).map { m[it] ?: "" } }
    }

    fun sheetsToPdf(sheets: List<Sheet>, out: File) {
        val w = TextPdfWriter.a4Landscape()
        var first = true
        for (s in sheets) {
            if (s.rows.isEmpty()) continue
            if (!first) w.add(Block.PageBreak)
            first = false
            w.add(Block.Para(s.name, 14f, bold = true, spaceAfter = 8f))
            s.rows.chunked(500).forEach { w.add(Block.Table(it, header = true, fontSize = 8f)) }
        }
        if (first) w.add(Block.Para("(The workbook is empty)"))
        w.save(out)
    }

    // ---------- PPTX -> PDF ----------

    fun pptxToPdf(file: File, out: File, progress: (Float) -> Unit) {
        ZipFile(file).use { zip ->
            var cx = 9144000L; var cy = 6858000L
            val ids = ArrayList<String>()
            zip.read("ppt/presentation.xml")?.use {
                val p = parser(it)
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG) when (p.local()) {
                        "sldSz" -> { cx = p.attr("cx")?.toLongOrNull() ?: cx; cy = p.attr("cy")?.toLongOrNull() ?: cy }
                        "sldId" -> p.attr("id")?.let { _ -> (0 until p.attributeCount).firstOrNull { i -> p.getAttributeName(i).endsWith(":id") }?.let { i -> ids += p.getAttributeValue(i) } }
                    }
                }
            } ?: throw IllegalArgumentException("This is not a valid PowerPoint (.pptx) file")
            val rel = rels(zip, "ppt/_rels/presentation.xml.rels", "ppt")
            val pw = (cx / 12700f).toInt().coerceAtLeast(72); val ph = (cy / 12700f).toInt().coerceAtLeast(72)
            val scale = pw / (cx.toFloat())
            val pdf = PdfDocument()
            ids.forEachIndexed { idx, id ->
                val path = rel[id] ?: return@forEachIndexed
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, idx + 1).create())
                page.canvas.drawColor(Color.WHITE)
                drawSlide(zip, path, page.canvas, scale, pw.toFloat(), ph.toFloat())
                pdf.finishPage(page)
                progress((idx + 1f) / ids.size)
            }
            if (ids.isEmpty()) { val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create()); pdf.finishPage(page) }
            out.outputStream().use { pdf.writeTo(it) }
            pdf.close()
        }
    }

    private class Shape {
        var x = -1L; var y = -1L; var w = 0L; var h = 0L
        var fill: Int? = null
        var ph: String? = null
        var image: String? = null
        val paras = ArrayList<Pair<SpannableStringBuilder, Layout.Alignment>>()
        var maxSize = 0f
    }

    private fun drawSlide(zip: ZipFile, path: String, canvas: android.graphics.Canvas, scale: Float, pw: Float, ph: Float) {
        val dir = path.substringBeforeLast('/')
        val rel = rels(zip, "$dir/_rels/${path.substringAfterLast('/')}.rels", dir)
        val shapes = ArrayList<Shape>()
        zip.read(path)?.use {
            val p = parser(it)
            var s: Shape? = null
            var para: SpannableStringBuilder? = null
            var align = Layout.Alignment.ALIGN_NORMAL
            var runStart = 0; var bold = false; var italic = false; var size = 0f; var col: Int? = null
            var inSpPr = false; var inRPr = false; var depth = 0
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) {
                    when (p.local()) {
                        "sp", "pic", "cxnSp" -> if (s == null) { s = Shape(); depth = p.depth }
                        "ph" -> s?.ph = p.attr("type") ?: "body"
                        "spPr" -> inSpPr = true
                        "off" -> if (inSpPr && s != null && s.x < 0) { s.x = p.attr("x")?.toLongOrNull() ?: 0; s.y = p.attr("y")?.toLongOrNull() ?: 0 }
                        "ext" -> if (inSpPr && s != null && s.w == 0L) { s.w = p.attr("cx")?.toLongOrNull() ?: 0; s.h = p.attr("cy")?.toLongOrNull() ?: 0 }
                        "srgbClr" -> if (inSpPr && !inRPr && s != null && s.fill == null) s.fill = color(p.attr("val"))
                                     else if (inRPr) col = color(p.attr("val"))
                        "ln" -> if (inSpPr) skip(p)
                        "blip" -> s?.image = p.attr("embed")?.let { rel[it] }
                        "p" -> { para = SpannableStringBuilder(); align = Layout.Alignment.ALIGN_NORMAL }
                        "pPr" -> align = when (p.attr("algn")) { "ctr" -> Layout.Alignment.ALIGN_CENTER; "r" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
                        "r", "fld" -> { runStart = para?.length ?: 0; bold = false; italic = false; size = 0f; col = null }
                        "rPr", "endParaRPr" -> {
                            inRPr = true
                            bold = p.attr("b") == "1"; italic = p.attr("i") == "1"
                            size = (p.attr("sz")?.toFloatOrNull() ?: 0f) / 100f
                        }
                        "t" -> para?.append(p.nextText())
                        "br" -> para?.append('\n')
                    }
                } else if (p.eventType == XmlPullParser.END_TAG) {
                    when (p.local()) {
                        "spPr" -> inSpPr = false
                        "rPr", "endParaRPr" -> inRPr = false
                        "r", "fld" -> para?.let { sp ->
                            val end = sp.length
                            if (end > runStart) {
                                val st = (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
                                if (st != 0) sp.setSpan(StyleSpan(st), runStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                if (size > 0) { sp.setSpan(AbsoluteSizeSpan(max(1, (size * scale * 12700f).toInt())), runStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); s?.let { it.maxSize = max(it.maxSize, size) } }
                                col?.let { c -> sp.setSpan(ForegroundColorSpan(c), runStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                            }
                        }
                        "p" -> { para?.let { s?.paras?.add(it to align) }; para = null }
                        "sp", "pic", "cxnSp" -> if (s != null && p.depth == depth) { shapes += s; s = null }
                    }
                }
            }
        }
        val emu = scale
        for (s in shapes) {
            val isTitle = s.ph == "title" || s.ph == "ctrTitle"
            val rect = if (s.x >= 0 && s.w > 0) RectF(s.x * emu, s.y * emu, (s.x + s.w) * emu, (s.y + s.h) * emu)
            else if (isTitle) RectF(pw * 0.06f, ph * 0.05f, pw * 0.94f, ph * 0.22f)
            else RectF(pw * 0.06f, ph * 0.25f, pw * 0.94f, ph * 0.92f)
            s.fill?.let { c -> canvas.drawRect(rect, Paint().apply { color = c }) }
            s.image?.let { img -> zip.read(img)?.use { BitmapFactory.decodeStream(it) }?.let { bmp -> canvas.drawFit(bmp, rect) } }
            if (s.paras.isEmpty()) continue
            val defaultSize = if (isTitle) ph * 0.075f else ph * 0.042f
            var y = rect.top + 4f
            for ((sp, al) in s.paras) {
                val text: CharSequence = if (sp.isEmpty()) " " else sp
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = defaultSize; color = Color.BLACK; if (isTitle) typeface = Typeface.DEFAULT_BOLD }
                val l = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, rect.width().toInt() - 8)).setAlignment(al).setIncludePad(false).build()
                canvas.save(); canvas.translate(rect.left + 4f, y); l.draw(canvas); canvas.restore()
                y += l.height + defaultSize * 0.25f
            }
        }
    }

    // ---------- writers ----------

    private fun ZipOutputStream.put(name: String, content: String) { putNextEntry(ZipEntry(name)); write(content.toByteArray(Charsets.UTF_8)); closeEntry() }
    private fun ZipOutputStream.put(name: String, content: ByteArray) { putNextEntry(ZipEntry(name)); write(content); closeEntry() }

    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val PKG_RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    class DocxWriter {
        private val body = StringBuilder()
        private val media = ArrayList<ByteArray>()

        fun paragraph(text: String, bold: Boolean = false, sizePt: Float? = null, center: Boolean = false) {
            body.append("<w:p>")
            if (center) body.append("<w:pPr><w:jc w:val=\"center\"/></w:pPr>")
            val rpr = buildString {
                if (bold || sizePt != null) {
                    append("<w:rPr>"); if (bold) append("<w:b/>")
                    if (sizePt != null) append("<w:sz w:val=\"${(sizePt * 2).toInt()}\"/>")
                    append("</w:rPr>")
                }
            }
            text.split('\n').forEachIndexed { i, line ->
                body.append("<w:r>").append(rpr)
                if (i > 0) body.append("<w:br/>")
                body.append("<w:t xml:space=\"preserve\">").append(esc(line)).append("</w:t></w:r>")
            }
            body.append("</w:p>")
        }

        fun pageBreak() { body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>") }

        fun image(jpeg: ByteArray, widthPt: Float, heightPt: Float) {
            media += jpeg
            val n = media.size
            val cx = (widthPt * 12700).toLong(); val cy = (heightPt * 12700).toLong()
            body.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\"/><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$n\" name=\"Page $n\"/>")
            body.append("<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"image$n.jpeg\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rIdImg$n\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
        }

        /** Margins in points. */
        fun save(out: File, marginPt: Float = 56f) {
            val m = (marginPt * 20).toInt()
            ZipOutputStream(out.outputStream().buffered()).use { z ->
                z.put("[Content_Types].xml", XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>")
                z.put("_rels/.rels", XML + "<Relationships xmlns=\"$PKG_RELS\"><Relationship Id=\"rId1\" Type=\"$R_NS/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
                z.put("word/_rels/document.xml.rels", XML + "<Relationships xmlns=\"$PKG_RELS\"><Relationship Id=\"rIdStyles\" Type=\"$R_NS/styles\" Target=\"styles.xml\"/>" +
                    media.indices.joinToString("") { "<Relationship Id=\"rIdImg${it + 1}\" Type=\"$R_NS/image\" Target=\"media/image${it + 1}.jpeg\"/>" } + "</Relationships>")
                z.put("word/styles.xml", XML + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style></w:styles>")
                media.forEachIndexed { i, b -> z.put("word/media/image${i + 1}.jpeg", b) }
                z.put("word/document.xml", XML + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"$R_NS\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"><w:body>" +
                    body + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"$m\" w:right=\"$m\" w:bottom=\"$m\" w:left=\"$m\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>")
            }
        }
    }

    class XlsxWriter {
        private val sheets = ArrayList<Pair<String, List<List<String>>>>()

        fun sheet(name: String, rows: List<List<String>>) {
            var n = name.replace(Regex("[\\[\\]:*?/\\\\]"), " ").take(31).ifBlank { "Sheet${sheets.size + 1}" }
            var i = 2
            while (sheets.any { it.first.equals(n, true) }) { n = "${n.take(27)} ($i)"; i++ }
            sheets += n to rows
        }

        private fun colName(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }; return sb.toString() }

        fun save(out: File) {
            if (sheets.isEmpty()) sheet("Sheet1", emptyList())
            ZipOutputStream(out.outputStream().buffered()).use { z ->
                z.put("[Content_Types].xml", XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                    sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } + "</Types>")
                z.put("_rels/.rels", XML + "<Relationships xmlns=\"$PKG_RELS\"><Relationship Id=\"rId1\" Type=\"$R_NS/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
                z.put("xl/workbook.xml", XML + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"$R_NS\"><sheets>" +
                    sheets.mapIndexed { i, s -> "<sheet name=\"${esc(s.first)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>")
                z.put("xl/_rels/workbook.xml.rels", XML + "<Relationships xmlns=\"$PKG_RELS\">" +
                    sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"$R_NS/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
                    "<Relationship Id=\"rIdStyles\" Type=\"$R_NS/styles\" Target=\"styles.xml\"/></Relationships>")
                z.put("xl/styles.xml", XML + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>")
                sheets.forEachIndexed { si, (_, rows) ->
                    val sb = StringBuilder(XML + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                    rows.forEachIndexed { ri, row ->
                        sb.append("<row r=\"${ri + 1}\">")
                        row.forEachIndexed { ci, v ->
                            if (v.isEmpty()) return@forEachIndexed
                            val ref = "${colName(ci)}${ri + 1}"
                            val num = v.replace(",", "").takeIf { Regex("^-?\\d+(\\.\\d+)?$").matches(it) && it.length < 15 }
                            if (num != null) sb.append("<c r=\"$ref\"><v>$num</v></c>")
                            else sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(esc(v)).append("</t></is></c>")
                        }
                        sb.append("</row>")
                    }
                    sb.append("</sheetData></worksheet>")
                    z.put("xl/worksheets/sheet${si + 1}.xml", sb.toString())
                }
            }
        }
    }

    class PptxWriter(private val cx: Long, private val cy: Long) {
        sealed class Item {
            class Picture(val jpeg: ByteArray, val x: Long, val y: Long, val w: Long, val h: Long) : Item()
            class TextBox(val text: String, val x: Long, val y: Long, val w: Long, val h: Long, val sizePt: Float, val bold: Boolean) : Item()
        }
        private val slides = ArrayList<List<Item>>()
        fun slide(items: List<Item>) { slides += items }

        fun save(out: File) {
            if (slides.isEmpty()) slides.add(emptyList())
            val ns = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"$R_NS\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
            val grp = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
            ZipOutputStream(out.outputStream().buffered()).use { z ->
                z.put("[Content_Types].xml", XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
                    "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
                    "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
                    "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
                    "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
                    slides.indices.joinToString("") { "<Override PartName=\"/ppt/slides/slide${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>" } + "</Types>")
                z.put("_rels/.rels", XML + "<Relationships xmlns=\"$PKG_RELS\"><Relationship Id=\"rId1\" Type=\"$R_NS/officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>")
                z.put("ppt/presentation.xml", XML + "<p:presentation $ns saveSubsetFonts=\"1\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>" +
                    slides.indices.joinToString("") { "<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 3}\"/>" } +
                    "</p:sldIdLst><p:sldSz cx=\"$cx\" cy=\"$cy\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/><p:defaultTextStyle><a:defPPr><a:defRPr lang=\"en-US\"/></a:defPPr></p:defaultTextStyle></p:presentation>")
                z.put("ppt/_rels/presentation.xml.rels", XML + "<Relationships xmlns=\"$PKG_RELS\"><Relationship Id=\"rId1\" Type=\"$R_NS/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/><Relationship Id=\"rId2\" Type=\"$R_NS/theme\" Target=\"theme/theme1.xml\"/>" +
                    slides.indices.joinToString("") { "<Relationship Id=\"rId${it + 3}\" Type=\"$R_NS/slide\" Target=\"slides/slide${it + 1}.xml\"/>" } + "</Relationships>")
                z.put("ppt/slideMasters/slideMaster1.xml", XML + "<p:sldMaster $ns><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>$grp</p:spTree></p:cSld><p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/><p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz=\"4400\"/></a:lvl1pPr></p:titleStyle><p:bodyStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:bodyStyle><p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>")
                z.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", XML + "<Relationships xmlns=\"$PKG_RELS\"><Relationship Id=\"rId1\" Type=\"$R_NS/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/><Relationship Id=\"rId2\" Type=\"$R_NS/theme\" Target=\"../theme/theme1.xml\"/></Relationships>")
                z.put("ppt/slideLayouts/slideLayout1.xml", XML + "<p:sldLayout $ns type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree>$grp</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>")
                z.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", XML + "<Relationships xmlns=\"$PKG_RELS\"><Relationship Id=\"rId1\" Type=\"$R_NS/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>")
                z.put("ppt/theme/theme1.xml", XML + THEME)
                var mediaN = 0
                slides.forEachIndexed { si, items ->
                    val sb = StringBuilder(XML + "<p:sld $ns><p:cSld><p:spTree>$grp")
                    val rels = StringBuilder(XML + "<Relationships xmlns=\"$PKG_RELS\"><Relationship Id=\"rId1\" Type=\"$R_NS/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>")
                    var id = 2
                    items.forEach { item ->
                        when (item) {
                            is Item.Picture -> {
                                mediaN++
                                z.put("ppt/media/image$mediaN.jpeg", item.jpeg)
                                rels.append("<Relationship Id=\"rIdImg$mediaN\" Type=\"$R_NS/image\" Target=\"../media/image$mediaN.jpeg\"/>")
                                sb.append("<p:pic><p:nvPicPr><p:cNvPr id=\"$id\" name=\"Picture $id\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed=\"rIdImg$mediaN\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x=\"${item.x}\" y=\"${item.y}\"/><a:ext cx=\"${item.w}\" cy=\"${item.h}\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>")
                            }
                            is Item.TextBox -> {
                                sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Text $id\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"${item.x}\" y=\"${item.y}\"/><a:ext cx=\"${item.w}\" cy=\"${item.h}\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" rtlCol=\"0\"><a:noAutofit/></a:bodyPr><a:lstStyle/>")
                                item.text.split('\n').forEach { line ->
                                    sb.append("<a:p><a:r><a:rPr lang=\"en-US\" sz=\"${(item.sizePt * 100).toInt().coerceIn(100, 400000)}\"${if (item.bold) " b=\"1\"" else ""} dirty=\"0\"/><a:t>").append(esc(line)).append("</a:t></a:r></a:p>")
                                }
                                sb.append("</p:txBody></p:sp>")
                            }
                        }
                        id++
                    }
                    sb.append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
                    rels.append("</Relationships>")
                    z.put("ppt/slides/slide${si + 1}.xml", sb.toString())
                    z.put("ppt/slides/_rels/slide${si + 1}.xml.rels", rels.toString())
                }
            }
        }

        private companion object {
            val THEME = "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Office Theme\"><a:themeElements>" +
                "<a:clrScheme name=\"Office\"><a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1><a:dk2><a:srgbClr val=\"44546A\"/></a:dk2><a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2><a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2><a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4><a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6><a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>" +
                "<a:fontScheme name=\"Office\"><a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
                "<a:fmtScheme name=\"Office\"><a:fillStyleLst>" + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>".repeat(3) + "</a:fillStyleLst><a:lnStyleLst>" +
                "<a:ln w=\"6350\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/><a:miter lim=\"800000\"/></a:ln>".repeat(3) +
                "</a:lnStyleLst><a:effectStyleLst>" + "<a:effectStyle><a:effectLst/></a:effectStyle>".repeat(3) + "</a:effectStyleLst><a:bgFillStyleLst>" +
                "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>".repeat(3) + "</a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"
        }
    }
}

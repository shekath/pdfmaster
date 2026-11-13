package com.pdfmaster.app.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PdfPasswordException(msg: String = "This PDF is protected with a password. Use \"Unlock PDF\" first.") : Exception(msg)

/** All PdfBox-based page and document operations. */
object PdfOps {

    fun mem(): MemoryUsageSetting = MemoryUsageSetting.setupMixed(64L * 1024 * 1024)

    /** Opens a document. Owner-restrictions are dropped so the document can be modified. */
    fun open(f: File, password: String? = null): PDDocument {
        val doc = try {
            if (password.isNullOrEmpty()) PDDocument.load(f, mem()) else PDDocument.load(f, password, mem())
        } catch (e: InvalidPasswordException) {
            throw PdfPasswordException(if (password.isNullOrEmpty()) "This PDF is protected with a password. Use \"Unlock PDF\" first." else "Incorrect password.")
        }
        if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
        return doc
    }

    fun pageCount(f: File): Int = open(f).use { it.numberOfPages }

    /** Copies a page into [dest], keeping inherited attributes. */
    fun copyPage(dest: PDDocument, page: PDPage): PDPage {
        val p = dest.importPage(page)
        p.mediaBox = page.mediaBox
        p.cropBox = page.cropBox
        p.resources = page.resources
        p.rotation = page.rotation
        return p
    }

    // ---------- coordinate helpers ----------

    /** Displayed page size and the matrix mapping displayed coordinates (origin bottom-left) to user space. */
    class Visual(val w: Float, val h: Float, val m: Matrix, val rot: Int)

    fun visual(page: PDPage): Visual {
        val b = page.cropBox
        val llx = b.lowerLeftX; val lly = b.lowerLeftY; val w = b.width; val h = b.height
        return when (val rot = ((page.rotation % 360) + 360) % 360) {
            90 -> Visual(h, w, Matrix(0f, 1f, -1f, 0f, llx + w, lly), rot)
            180 -> Visual(w, h, Matrix(-1f, 0f, 0f, -1f, llx + w, lly + h), rot)
            270 -> Visual(h, w, Matrix(0f, -1f, 1f, 0f, llx, lly + h), rot)
            else -> Visual(w, h, Matrix(1f, 0f, 0f, 1f, llx, lly), 0)
        }
    }

    /** Runs [block] with a content stream whose coordinates are the displayed page (origin bottom-left). */
    inline fun drawVisual(doc: PDDocument, page: PDPage, block: (PDPageContentStream, Float, Float) -> Unit) {
        val v = visual(page)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.saveGraphicsState()
            cs.transform(v.m)
            block(cs, v.w, v.h)
            cs.restoreGraphicsState()
        }
    }

    /** Converts an unrotated top-left-origin point (as used by text extraction) to displayed top-left coordinates. */
    fun unrotatedToVisual(rot: Int, w: Float, h: Float, ux: Float, uy: Float): Pair<Float, Float> = when (rot) {
        90 -> Pair(h - uy, ux)
        180 -> Pair(w - ux, h - uy)
        270 -> Pair(uy, w - ux)
        else -> Pair(ux, uy)
    }

    private val fontFiles = listOf(
        "/system/fonts/Roboto-Regular.ttf", "/system/fonts/RobotoStatic-Regular.ttf",
        "/system/fonts/NotoSans-Regular.ttf", "/system/fonts/DroidSans.ttf"
    )

    /** A Unicode-capable embedded font when the system provides one, else Helvetica. */
    fun loadFont(doc: PDDocument): PDFont {
        for (path in fontFiles) {
            val f = File(path)
            if (f.exists()) {
                val font = runCatching { PDType0Font.load(doc, f) }.getOrNull()
                if (font != null) return font
            }
        }
        return PDType1Font.HELVETICA
    }

    /** Replaces characters the font can't encode with '?'. */
    fun safeText(font: PDFont, s: String): String {
        val sb = StringBuilder()
        s.codePoints().forEach { cp ->
            val str = when (cp) { '\t'.code -> " "; '\n'.code, '\r'.code -> ""; else -> String(Character.toChars(cp)) }
            if (str.isEmpty()) return@forEach
            if (runCatching { font.encode(str) }.isSuccess) sb.append(str) else sb.append('?')
        }
        return sb.toString()
    }

    fun ascent(font: PDFont): Float =
        (font.fontDescriptor?.ascent?.takeIf { it > 0 } ?: 800f) / 1000f

    private fun rgb(color: Int) = floatArrayOf(
        ((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f
    )

    private fun PDPageContentStream.fill(color: Int) { val c = rgb(color); setNonStrokingColor(c[0], c[1], c[2]) }
    private fun PDPageContentStream.stroke(color: Int) { val c = rgb(color); setStrokingColor(c[0], c[1], c[2]) }

    private fun alpha(cs: PDPageContentStream, a: Float) {
        val gs = PDExtendedGraphicsState()
        gs.nonStrokingAlphaConstant = a
        gs.strokingAlphaConstant = a
        cs.setGraphicsStateParameters(gs)
    }

    // ---------- organise ----------

    fun merge(inputs: List<File>, out: File, progress: (Float) -> Unit) {
        require(inputs.size >= 2) { "Select at least two PDF files" }
        val opened = ArrayList<PDDocument>()
        PDDocument(mem()).use { dest ->
            try {
                val util = PDFMergerUtility()
                inputs.forEachIndexed { i, f ->
                    val src = open(f); opened += src
                    util.appendDocument(dest, src)
                    progress((i + 1f) / inputs.size)
                }
                dest.save(out)
            } finally { opened.forEach { runCatching { it.close() } } }
        }
    }

    /** Builds one output document per group of 0-based page indexes. */
    fun extractGroups(input: File, groups: List<List<Int>>, outs: List<File>, progress: (Float) -> Unit) {
        open(input).use { doc ->
            groups.forEachIndexed { gi, g ->
                PDDocument(mem()).use { nd ->
                    g.forEach { copyPage(nd, doc.getPage(it)) }
                    nd.save(outs[gi])
                }
                progress((gi + 1f) / groups.size)
            }
        }
    }

    fun rotate(input: File, out: File, degrees: Int, pages: Set<Int>?) {
        open(input).use { doc ->
            doc.pages.forEachIndexed { i, p -> if (pages == null || i in pages) p.rotation = ((p.rotation + degrees) % 360 + 360) % 360 }
            doc.save(out)
        }
    }

    /** A page in an organised document: source index, page index (-1 = blank page), extra rotation. */
    data class PageRef(val src: Int, val index: Int, val rotation: Int)

    fun organize(sources: List<File>, refs: List<PageRef>, out: File) {
        require(refs.isNotEmpty()) { "The document has no pages left" }
        val docs = sources.map { open(it) }
        try {
            PDDocument(mem()).use { nd ->
                var lastSize = PDRectangle.A4
                for (r in refs) {
                    if (r.index < 0) {
                        val p = PDPage(PDRectangle(lastSize.width, lastSize.height))
                        p.rotation = r.rotation
                        nd.addPage(p)
                    } else {
                        val src = docs[r.src].getPage(r.index)
                        val p = copyPage(nd, src)
                        p.rotation = ((src.rotation + r.rotation) % 360 + 360) % 360
                        lastSize = src.mediaBox
                    }
                }
                nd.save(out)
            }
        } finally { docs.forEach { runCatching { it.close() } } }
    }

    // ---------- security ----------

    fun protect(input: File, out: File, userPassword: String, ownerPassword: String?, allowPrint: Boolean, allowCopy: Boolean, allowEdit: Boolean) {
        require(userPassword.isNotEmpty()) { "Enter a password" }
        open(input).use { doc ->
            val ap = AccessPermission()
            ap.setCanPrint(allowPrint)
            ap.setCanPrintFaithful(allowPrint)
            ap.setCanExtractContent(allowCopy)
            ap.setCanExtractForAccessibility(true)
            ap.setCanModify(allowEdit)
            ap.setCanModifyAnnotations(allowEdit)
            ap.setCanFillInForm(allowEdit)
            ap.setCanAssembleDocument(allowEdit)
            val policy = StandardProtectionPolicy(ownerPassword?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString(), userPassword, ap)
            policy.encryptionKeyLength = 256
            doc.protect(policy)
            doc.save(out)
        }
    }

    fun unlock(input: File, out: File, password: String) {
        open(input, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
    }

    /** True if the document needs a password to open. */
    fun needsPassword(f: File): Boolean = try {
        PDDocument.load(f, mem()).close(); false
    } catch (e: InvalidPasswordException) { true }

    // ---------- edit ----------

    fun watermarkText(input: File, out: File, text: String, size: Float, opacity: Float, angle: Float, color: Int, tile: Boolean, pages: Set<Int>?) {
        require(text.isNotBlank()) { "Enter the watermark text" }
        open(input).use { doc ->
            val font = loadFont(doc)
            val t = safeText(font, text)
            val tw = font.getStringWidth(t) / 1000f * size
            doc.pages.forEachIndexed { i, page ->
                if (pages != null && i !in pages) return@forEachIndexed
                drawVisual(doc, page) { cs, w, h ->
                    alpha(cs, opacity)
                    cs.fill(color)
                    val centers = if (!tile) listOf(w / 2 to h / 2) else buildList {
                        val stepX = tw + size * 3; val stepY = size * 5
                        var y = stepY / 2; var row = 0
                        while (y < h + stepY) {
                            var x = if (row % 2 == 0) stepX / 4 else stepX * 3 / 4
                            while (x < w + stepX) { add(x to y); x += stepX }
                            y += stepY; row++
                        }
                    }
                    for ((cx, cy) in centers) {
                        cs.beginText()
                        cs.setFont(font, size)
                        val m = Matrix.getRotateInstance(Math.toRadians(angle.toDouble()), cx, cy)
                        m.translate(-tw / 2, -size / 3)
                        cs.setTextMatrix(m)
                        cs.showText(t)
                        cs.endText()
                    }
                }
            }
            doc.save(out)
        }
    }

    fun watermarkImage(input: File, out: File, image: File, widthFrac: Float, opacity: Float, tile: Boolean, pages: Set<Int>?) {
        open(input).use { doc ->
            val bmp = BitmapFactory.decodeFile(image.path) ?: throw IllegalArgumentException("The watermark image could not be read")
            val img = LosslessFactory.createFromImage(doc, bmp)
            val aspect = bmp.height.toFloat() / bmp.width
            doc.pages.forEachIndexed { i, page ->
                if (pages != null && i !in pages) return@forEachIndexed
                drawVisual(doc, page) { cs, w, h ->
                    alpha(cs, opacity)
                    val iw = w * widthFrac; val ih = iw * aspect
                    if (!tile) cs.drawImage(img, (w - iw) / 2, (h - ih) / 2, iw, ih)
                    else {
                        var y = ih * 0.5f
                        while (y < h) { var x = iw * 0.25f; while (x < w) { cs.drawImage(img, x, y, iw, ih); x += iw * 1.6f }; y += ih * 1.8f }
                    }
                }
            }
            doc.save(out)
        }
    }

    /**
     * @param position one of tl, tc, tr, bl, bc, br
     * @param format "n" | "page_n" | "n_of_t" | "page_n_of_t"
     */
    fun pageNumbers(input: File, out: File, position: String, format: String, start: Int, size: Float, skipFirst: Boolean, color: Int = 0x333333) {
        open(input).use { doc ->
            val font = loadFont(doc)
            val total = doc.numberOfPages - if (skipFirst) 1 else 0
            var n = start
            doc.pages.forEachIndexed { i, page ->
                if (skipFirst && i == 0) return@forEachIndexed
                val label = when (format) {
                    "page_n" -> "Page $n"
                    "n_of_t" -> "$n / ${total + start - 1}"
                    "page_n_of_t" -> "Page $n of ${total + start - 1}"
                    else -> "$n"
                }
                n++
                drawVisual(doc, page) { cs, w, h ->
                    val tw = font.getStringWidth(label) / 1000f * size
                    val margin = max(18f, min(w, h) * 0.04f)
                    val x = when (position[1]) { 'l' -> margin; 'r' -> w - margin - tw; else -> (w - tw) / 2 }
                    val y = if (position[0] == 't') h - margin - size * 0.8f else margin
                    cs.fill(color)
                    cs.beginText(); cs.setFont(font, size); cs.newLineAtOffset(x, y); cs.showText(label); cs.endText()
                }
            }
            doc.save(out)
        }
    }

    /** Crops using a normalised displayed-page rectangle, on one page or all pages. */
    fun crop(input: File, out: File, l: Float, t: Float, r: Float, b: Float, pageIndex: Int?) {
        require(r - l > 0.01f && b - t > 0.01f) { "Draw a larger crop area" }
        open(input).use { doc ->
            doc.pages.forEachIndexed { i, page ->
                if (pageIndex != null && i != pageIndex) return@forEachIndexed
                val v = visual(page)
                val p1 = v.m.transformPoint(l * v.w, v.h - t * v.h)
                val p2 = v.m.transformPoint(r * v.w, v.h - b * v.h)
                val rect = PDRectangle(min(p1.x, p2.x), min(p1.y, p2.y), kotlin.math.abs(p1.x - p2.x), kotlin.math.abs(p1.y - p2.y))
                page.cropBox = rect
                page.mediaBox = rect
            }
            doc.save(out)
        }
    }

    /** Applies text, ink, rectangle and image overlays from the editor. */
    fun applyEdits(input: File, out: File, items: List<EditItem>) {
        open(input).use { doc ->
            val font by lazy { loadFont(doc) }
            val images = HashMap<File, PDImageXObject>()
            items.groupBy { it.page }.forEach { (pi, list) ->
                if (pi !in 0 until doc.numberOfPages) return@forEach
                val page = doc.getPage(pi)
                drawVisual(doc, page) { cs, w, h ->
                    for (item in list) {
                        cs.saveGraphicsState()
                        when (item) {
                            is EditItem.Box -> {
                                if (item.alpha < 1f) alpha(cs, item.alpha)
                                cs.fill(item.color)
                                cs.addRect(item.l * w, h - item.b * h, (item.r - item.l) * w, (item.b - item.t) * h)
                                cs.fill()
                            }
                            is EditItem.Ink -> {
                                if (item.points.size >= 2) {
                                    cs.stroke(item.color)
                                    cs.setLineWidth(item.width * w)
                                    cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
                                    val (x0, y0) = item.points.first()
                                    cs.moveTo(x0 * w, h - y0 * h)
                                    item.points.drop(1).forEach { (x, y) -> cs.lineTo(x * w, h - y * h) }
                                    cs.stroke()
                                }
                            }
                            is EditItem.Text -> {
                                val size = item.size * w
                                cs.fill(item.color)
                                val asc = ascent(font) * size
                                item.text.split('\n').forEachIndexed { li, line ->
                                    val s = safeText(font, line)
                                    if (s.isEmpty()) return@forEachIndexed
                                    cs.beginText(); cs.setFont(font, size)
                                    cs.newLineAtOffset(item.x * w, h - item.y * h - asc - li * size * 1.2f)
                                    cs.showText(s); cs.endText()
                                }
                            }
                            is EditItem.Picture -> {
                                val img = images.getOrPut(item.file) {
                                    val bmp = BitmapFactory.decodeFile(item.file.path) ?: return@getOrPut null
                                    LosslessFactory.createFromImage(doc, bmp)
                                } ?: continue
                                cs.drawImage(img, item.x * w, h - (item.y + item.h) * h, item.w * w, item.h * h)
                            }
                        }
                        cs.restoreGraphicsState()
                    }
                }
            }
            doc.save(out)
        }
    }

    private fun <K, V> HashMap<K, V>.getOrPut(key: K, default: () -> V?): V? {
        get(key)?.let { return it }
        val v = default() ?: return null
        put(key, v); return v
    }

    /**
     * True redaction: pages with redaction boxes are re-rendered as images with the boxes burned in,
     * so the hidden text and graphics are removed from the file, not just covered.
     */
    fun redact(input: File, out: File, boxes: List<EditItem.Box>, dpi: Int = 200, progress: (Float) -> Unit) {
        require(boxes.isNotEmpty()) { "Mark at least one area to redact" }
        val byPage = boxes.groupBy { it.page }
        Render.Doc(input).use { r ->
            open(input).use { doc ->
                PDDocument(mem()).use { nd ->
                    for (i in 0 until doc.numberOfPages) {
                        val list = byPage[i]
                        if (list == null) { copyPage(nd, doc.getPage(i)); continue }
                        val size = r.pageSize(i)
                        val bmp = r.render(i, (size.width / 72f * dpi).roundToInt()).copy(Bitmap.Config.ARGB_8888, true)
                        val c = Canvas(bmp)
                        val paint = Paint().apply { color = android.graphics.Color.BLACK }
                        list.forEach { c.drawRect(RectF(it.l * bmp.width, it.t * bmp.height, it.r * bmp.width, it.b * bmp.height), paint) }
                        val page = PDPage(PDRectangle(size.width, size.height))
                        nd.addPage(page)
                        val img = JPEGFactory.createFromImage(nd, bmp, 0.9f)
                        PDPageContentStream(nd, page).use { it.drawImage(img, 0f, 0f, size.width, size.height) }
                        bmp.recycle()
                        progress((i + 1f) / doc.numberOfPages)
                    }
                    nd.documentInformation = doc.documentInformation.also { it.author = null; it.creator = null; it.producer = null; it.keywords = null; it.subject = null }
                    nd.save(out)
                }
            }
        }
    }

    /** Finds all occurrences of [term] (case-insensitive) and returns normalised display boxes. */
    fun findText(input: File, term: String): List<EditItem.Box> {
        if (term.isBlank()) return emptyList()
        val needle = term.trim().lowercase()
        val result = ArrayList<EditItem.Box>()
        open(input).use { doc ->
            TextExtract.pages(doc).forEach { pt ->
                val page = doc.getPage(pt.index)
                val v = visual(page)
                for (line in pt.lines) {
                    val hay = line.text.lowercase()
                    var idx = hay.indexOf(needle)
                    while (idx >= 0) {
                        val bs = line.boxes.subList(idx, min(idx + needle.length, line.boxes.size))
                        if (bs.isNotEmpty()) {
                            val l = bs.minOf { it.left }; val t = bs.minOf { it.top }
                            val rr = bs.maxOf { it.right }; val bb = bs.maxOf { it.bottom }
                            val pts = listOf(l to t, rr to t, l to bb, rr to bb).map { (x, y) -> unrotatedToVisual(v.rot, pt.width, pt.height, x, y) }
                            val pad = 1.5f
                            result += EditItem.Box(
                                pt.index,
                                ((pts.minOf { it.first } - pad) / v.w).coerceIn(0f, 1f),
                                ((pts.minOf { it.second } - pad) / v.h).coerceIn(0f, 1f),
                                ((pts.maxOf { it.first } + pad) / v.w).coerceIn(0f, 1f),
                                ((pts.maxOf { it.second } + pad) / v.h).coerceIn(0f, 1f),
                                android.graphics.Color.BLACK, 1f
                            )
                        }
                        idx = hay.indexOf(needle, idx + needle.length)
                    }
                }
            }
        }
        return result
    }

    // ---------- optimise ----------

    enum class Level(val quality: Float, val maxDim: Int) { LOW(0.82f, 2400), RECOMMENDED(0.65f, 1600), EXTREME(0.45f, 1100) }

    /** Recompresses raster images. Returns false if the result would not be smaller (input is copied instead). */
    fun compress(input: File, out: File, level: Level, progress: (Float) -> Unit): Boolean {
        open(input).use { doc ->
            val replaced = HashMap<COSBase, PDImageXObject>()
            val visited = HashSet<COSBase>()
            fun process(res: PDResources?, depth: Int) {
                if (res == null || depth > 10) return
                for (name in res.xObjectNames.toList()) {
                    val xo = runCatching { res.getXObject(name) }.getOrNull() ?: continue
                    val key = xo.cosObject
                    val rep = replaced[key]
                    if (rep != null) { res.put(name, rep); continue }
                    if (!visited.add(key)) continue
                    when (xo) {
                        is PDFormXObject -> process(xo.resources, depth + 1)
                        is PDImageXObject -> {
                            if (xo.isStencil || xo.cosObject.containsKey(COSName.MASK)) continue
                            if (xo.width * xo.height < 128 * 128) continue
                            val smask = xo.cosObject.getItem(COSName.SMASK)
                            val raw = runCatching { xo.image }.getOrNull() ?: continue
                            // The soft mask (transparency) is kept as-is; only the colour data is recompressed.
                            val bmp = if (raw.hasAlpha()) Render.flattenOnWhite(raw) else raw
                            val long = max(bmp.width, bmp.height)
                            val scaled = if (long > level.maxDim) {
                                val s = level.maxDim.toFloat() / long
                                Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true)
                            } else bmp
                            val newImg = runCatching { JPEGFactory.createFromImage(doc, scaled, level.quality) }.getOrNull() ?: continue
                            val oldLen = xo.cosObject.length
                            if (newImg.cosObject.length < oldLen * 0.9) {
                                if (smask != null) newImg.cosObject.setItem(COSName.SMASK, smask)
                                res.put(name, newImg)
                                replaced[key] = newImg
                            }
                        }
                    }
                }
            }
            val n = doc.numberOfPages
            doc.pages.forEachIndexed { i, p -> process(p.resources, 0); progress((i + 1f) / n * 0.9f) }
            // Drop embedded page thumbnails and document-level metadata streams that aren't needed for viewing.
            doc.pages.forEach { it.cosObject.removeItem(COSName.getPDFName("Thumb")) }
            doc.save(out)
        }
        if (out.length() >= input.length()) { input.copyTo(out, overwrite = true); return false }
        return true
    }

    /** Re-parses and rewrites a damaged file. Falls back to re-rendering every page if parsing fails. */
    fun repair(input: File, out: File, progress: (Float) -> Unit): String {
        val first = runCatching {
            PDDocument.load(input, mem()).use { doc ->
                if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
                // touch every page so broken objects surface now
                doc.pages.forEach { it.resources; it.mediaBox }
                doc.save(out)
            }
        }
        if (first.isSuccess && runCatching { Render.Doc(out).use { it.pageCount } }.getOrDefault(0) > 0) {
            return "Rebuilt the document structure."
        }
        Render.Doc(input).use { r ->
            PDDocument(mem()).use { nd ->
                for (i in 0 until r.pageCount) {
                    val size = r.pageSize(i)
                    val bmp = runCatching { r.render(i, (size.width / 72f * 200).roundToInt()) }.getOrNull() ?: continue
                    val page = PDPage(PDRectangle(size.width, size.height))
                    nd.addPage(page)
                    val img = JPEGFactory.createFromImage(nd, bmp, 0.9f)
                    PDPageContentStream(nd, page).use { it.drawImage(img, 0f, 0f, size.width, size.height) }
                    progress((i + 1f) / r.pageCount)
                }
                require(nd.numberOfPages > 0) { "The file is too damaged to recover." }
                nd.save(out)
            }
        }
        return "The structure couldn't be rebuilt, so the readable pages were recovered as images."
    }

    // ---------- PDF/A ----------

    fun pdfA(input: File, out: File, part: Int, flatten: Boolean, progress: (Float) -> Unit) {
        val src = if (flatten) {
            val tmp = File(out.parentFile, "flat_${System.nanoTime()}.pdf")
            Render.Doc(input).use { r ->
                PDDocument(mem()).use { nd ->
                    for (i in 0 until r.pageCount) {
                        val size = r.pageSize(i)
                        val bmp = r.render(i, (size.width / 72f * 200).roundToInt())
                        val page = PDPage(PDRectangle(size.width, size.height)); nd.addPage(page)
                        val img = JPEGFactory.createFromImage(nd, bmp, 0.9f)
                        PDPageContentStream(nd, page).use { it.drawImage(img, 0f, 0f, size.width, size.height) }
                        progress((i + 1f) / r.pageCount * 0.8f)
                    }
                    nd.save(tmp)
                }
            }
            tmp
        } else input
        open(src).use { doc ->
            val info = doc.documentInformation
            val title = info.title ?: Work.baseName(input)
            info.title = title
            info.producer = "PDF Master"
            val now = java.util.Calendar.getInstance()
            info.creationDate = info.creationDate ?: now
            info.modificationDate = now
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date())
            val xmp = """<?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/"><pdfaid:part>$part</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance></rdf:Description>
  <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title><rdf:Alt><rdf:li xml:lang="x-default">${Office.esc(title)}</rdf:li></rdf:Alt></dc:title></rdf:Description>
  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/"><xmp:CreateDate>$iso</xmp:CreateDate><xmp:ModifyDate>$iso</xmp:ModifyDate><xmp:CreatorTool>PDF Master</xmp:CreatorTool></rdf:Description>
  <rdf:Description rdf:about="" xmlns:pdf="http://ns.adobe.com/pdf/1.3/"><pdf:Producer>PDF Master</pdf:Producer></rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
            val meta = PDMetadata(doc)
            meta.importXMPMetadata(xmp.toByteArray(Charsets.UTF_8))
            doc.documentCatalog.metadata = meta
            SRGB.outputIntent(doc)?.let { doc.documentCatalog.addOutputIntent(it) }
            doc.save(out)
        }
        if (src != input) src.delete()
        progress(1f)
    }
}

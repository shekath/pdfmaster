package com.pdfmaster.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import java.util.WeakHashMap
import kotlin.math.ceil

/**
 * Script-aware fonts for writing text into PDFs: Latin, Indian scripts and Arabic/Urdu.
 * PdfBox doesn't shape complex scripts, so visible Indic/Arabic text is drawn as a crisp image
 * (shaped by Android) with an invisible text layer on top for search and copy.
 */
object Fonts {
    enum class Script(val asset: String?) {
        LATIN(null), DEVANAGARI("NotoSansDevanagari"), BENGALI("NotoSansBengali"), GURMUKHI("NotoSansGurmukhi"),
        GUJARATI("NotoSansGujarati"), ORIYA("NotoSansOriya"), TAMIL("NotoSansTamil"), TELUGU("NotoSansTelugu"),
        KANNADA("NotoSansKannada"), MALAYALAM("NotoSansMalayalam"), ARABIC("NotoNaskhArabic"), NEUTRAL(null)
    }

    fun script(cp: Int): Script = when (cp) {
        in 0x0900..0x097F, in 0xA8E0..0xA8FF, in 0x1CD0..0x1CFF -> Script.DEVANAGARI
        in 0x0980..0x09FF -> Script.BENGALI
        in 0x0A00..0x0A7F -> Script.GURMUKHI
        in 0x0A80..0x0AFF -> Script.GUJARATI
        in 0x0B00..0x0B7F -> Script.ORIYA
        in 0x0B80..0x0BFF -> Script.TAMIL
        in 0x0C00..0x0C7F -> Script.TELUGU
        in 0x0C80..0x0CFF -> Script.KANNADA
        in 0x0D00..0x0D7F -> Script.MALAYALAM
        in 0x0600..0x06FF, in 0x0750..0x077F, in 0xFB50..0xFDFF, in 0xFE70..0xFEFF -> Script.ARABIC
        in 0x0000..0x0040, in 0x005B..0x0060, in 0x007B..0x00BF, 0x200C, 0x200D, 0x25CC -> Script.NEUTRAL
        else -> Script.LATIN
    }

    /** True if the text needs OpenType shaping (Indic, Arabic, …) to display correctly. */
    fun needsShaping(text: String): Boolean = text.codePoints().anyMatch { script(it) !in setOf(Script.LATIN, Script.NEUTRAL) }

    private val cache = WeakHashMap<PDDocument, HashMap<Script, PDFont>>()

    fun font(doc: PDDocument, s: Script): PDFont {
        val m = synchronized(cache) { cache.getOrPut(doc) { HashMap() } }
        m[s]?.let { return it }
        val f = s.asset?.let { name -> runCatching { AppCtx.app.assets.open("fonts/$name-Regular.ttf").use { PDType0Font.load(doc, it) } }.getOrNull() }
            ?: latin(doc)
        m[s] = f
        return f
    }

    private fun latin(doc: PDDocument): PDFont {
        val m = synchronized(cache) { cache.getOrPut(doc) { HashMap() } }
        m[Script.LATIN]?.let { return it }
        val sys = PdfOps.loadFont(doc)
        val f = if (sys is PDType0Font) sys else runCatching { AppCtx.app.assets.open("fonts/NotoSans-Regular.ttf").use { PDType0Font.load(doc, it) } }.getOrDefault(sys)
        m[Script.LATIN] = f
        return f
    }

    /** Splits text into runs that each use one font. */
    fun runs(doc: PDDocument, text: String): List<Pair<PDFont, String>> {
        val out = ArrayList<Pair<PDFont, String>>()
        var cur: Script? = null
        val sb = StringBuilder()
        text.codePoints().forEach { cp ->
            var s = script(cp)
            if (s == Script.NEUTRAL) s = cur ?: Script.LATIN
            if (cur != null && s != cur && sb.isNotEmpty()) { out += font(doc, cur!!) to sb.toString(); sb.clear() }
            cur = s
            sb.appendCodePoint(if (cp == '\t'.code) ' '.code else cp)
        }
        if (sb.isNotEmpty()) out += font(doc, cur ?: Script.LATIN) to sb.toString()
        return out.map { (f, t) -> f to PdfOps.safeText(f, t) }
    }

    /** Width of [text] at 1pt, using per-script fonts. */
    fun unitWidth(doc: PDDocument, text: String): Float = runs(doc, text).sumOf { (f, t) -> runCatching { f.getStringWidth(t) / 1000.0 }.getOrDefault(0.0) }.toFloat()

    /** Writes text (any script) starting at baseline (x, y). Caller sets colour and rendering mode. */
    fun show(cs: PDPageContentStream, doc: PDDocument, text: String, x: Float, y: Float, size: Float) {
        var cx = x
        for ((f, t) in runs(doc, text)) {
            if (t.isEmpty()) continue
            cs.beginText(); cs.setFont(f, size); cs.newLineAtOffset(cx, y); cs.showText(t); cs.endText()
            cx += runCatching { f.getStringWidth(t) / 1000f * size }.getOrDefault(0f)
        }
    }

    /**
     * Draws correctly shaped text as an image whose top-left is at (x, top) in PDF space (y up),
     * plus an invisible searchable text layer. Returns the drawn width in points.
     */
    fun drawShaped(doc: PDDocument, cs: PDPageContentStream, text: String, x: Float, top: Float, size: Float, color: Int, bold: Boolean): Float {
        val scale = 4f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color; textSize = size * scale; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val fm = paint.fontMetrics
        val wPx = ceil(paint.measureText(text)).toInt().coerceAtLeast(1) + 4
        val hPx = ceil(fm.descent - fm.ascent).toInt().coerceAtLeast(1) + 2
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, 2f, -fm.ascent + 1, paint)
        val img = LosslessFactory.createFromImage(doc, bmp)
        val wPt = wPx / scale; val hPt = hPx / scale
        // Paint ascent sits at the top of the image, so align the image top with the line top.
        val ascentRatio = -fm.ascent / paint.textSize
        val imgTop = top + (ascentRatio * size - PdfOps.ascentFor(size))
        cs.drawImage(img, x - 2 / scale, imgTop - hPt + 1 / scale, wPt, hPt)
        cs.saveGraphicsState()
        cs.setRenderingMode(RenderingMode.NEITHER)
        val unit = unitWidth(doc, text)
        val fs = if (unit > 0) (wPt - 1f) / unit else size
        show(cs, doc, text, x, top - PdfOps.ascentFor(size), minOf(fs, size * 1.6f))
        cs.restoreGraphicsState()
        bmp.recycle()
        return wPt
    }
}

package com.pdfmaster.app.engine

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** On-device OCR with ML Kit: adds an invisible, searchable text layer behind each page. */
object Ocr {
    suspend fun run(input: File, outPdf: File?, outTxt: File?, progress: (Float) -> Unit): Int {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var lines = 0
        val text = StringBuilder()
        try {
            Render.Doc(input).use { r ->
                PdfOps.open(input).use { doc ->
                    val font = PdfOps.loadFont(doc)
                    for (i in 0 until r.pageCount) {
                        val size = r.pageSize(i)
                        val targetW = (size.width * 3f).roundToInt().coerceIn(1200, 3000)
                        val bmp = r.render(i, targetW)
                        val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
                        val sx = size.width / bmp.width; val sy = size.height / bmp.height
                        val page = doc.getPage(i)
                        if (i > 0) text.append("\n\n")
                        if (outPdf != null) PdfOps.drawVisual(doc, page) { cs, _, h ->
                            cs.setRenderingMode(RenderingMode.NEITHER)
                            for (block in result.textBlocks) for (line in block.lines) {
                                val box = line.boundingBox ?: continue
                                val s = PdfOps.safeText(font, line.text)
                                if (s.isBlank()) continue
                                val bw = box.width() * sx; val bh = box.height() * sy
                                val unitW = font.getStringWidth(s) / 1000f
                                if (unitW <= 0f) continue
                                val fs = (bw / unitW).coerceIn(1f, max(2f, bh * 1.6f))
                                cs.beginText(); cs.setFont(font, fs)
                                cs.newLineAtOffset(box.left * sx, h - box.bottom * sy + bh * 0.2f)
                                cs.showText(s); cs.endText()
                                lines++
                            }
                        } else lines += result.textBlocks.sumOf { it.lines.size }
                        text.append(result.text)
                        bmp.recycle()
                        progress((i + 1f) / r.pageCount)
                    }
                    if (outPdf != null) doc.save(outPdf)
                }
            }
        } finally { recognizer.close() }
        outTxt?.writeText(text.toString())
        return lines
    }
}

/** On-device translation with ML Kit (language models download once, then work offline). */
object Translator {
    val languages: List<Pair<String, String>> by lazy {
        TranslateLanguage.getAllLanguages().map { it to Locale.forLanguageTag(it).getDisplayName(Locale.getDefault()).replaceFirstChar { c -> c.titlecase() } }.sortedBy { it.second }
    }

    suspend fun detect(sample: String): String? {
        val client = LanguageIdentification.getClient()
        return try {
            val tag = client.identifyLanguage(sample.take(2000)).await()
            if (tag == "und") null else TranslateLanguage.fromLanguageTag(tag)
        } finally { client.close() }
    }

    suspend fun translatePdf(input: File, out: File, source: String, target: String, status: (Float, String) -> Unit): String {
        val pages = PdfOps.open(input).use { doc -> TextExtract.pages(doc) }
        val all = pages.joinToString("\n") { it.plain }
        require(all.isNotBlank()) { "No text found. If this is a scanned document, run OCR first." }
        val src = if (source == "auto") detect(all) ?: throw IllegalArgumentException("Couldn't detect the language. Choose the source language.") else source
        require(src != target) { "The document is already in the target language." }
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(target).build())
        try {
            status(0f, "Downloading language model (first time only)…")
            client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            val w = TextPdfWriter()
            val total = pages.sumOf { it.paragraphs().size }.coerceAtLeast(1)
            var done = 0
            pages.forEachIndexed { pi, pt ->
                if (pi > 0) w.add(Block.PageBreak)
                for (para in pt.paragraphs()) {
                    val parts = ArrayList<String>()
                    for (chunk in para.chunkedBySentence(900)) parts += client.translate(chunk).awaitString()
                    val translated = parts.joinToString(" ")
                    w.add(Block.Para(translated, 11f))
                    done++
                    status(done.toFloat() / total, "Translating… ${done * 100 / total}%")
                }
            }
            w.save(out)
        } finally { client.close() }
        return src
    }

    private suspend fun com.google.android.gms.tasks.Task<String>.awaitString(): String = await()

    private fun String.chunkedBySentence(max: Int): List<String> {
        if (length <= max) return listOf(this)
        val out = ArrayList<String>(); val sb = StringBuilder()
        val it = BreakIterator.getSentenceInstance(); it.setText(this)
        var start = it.first(); var end = it.next()
        while (end != BreakIterator.DONE) {
            val s = substring(start, end)
            if (sb.length + s.length > max && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            sb.append(s); start = end; end = it.next()
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out.flatMap { if (it.length > max) it.chunked(max) else listOf(it) }
    }
}

/** Offline extractive summariser: scores sentences by word importance and position. */
object Summarizer {
    private val stop = ("a about above after again against all am an and any are as at be because been before being below between both but by can could did do does doing down during each few for from further had has have having he her here hers herself him himself his how i if in into is it its itself just me more most my myself no nor not now of off on once only or other our ours ourselves out over own same she should so some such than that the their theirs them themselves then there these they this those through to too under until up very was we were what when where which while who whom why will with would you your yours yourself yourselves also may might must shall one two us via per page").split(' ').toHashSet()

    data class Summary(val sentences: List<String>, val keywords: List<String>, val wordCount: Int)

    fun summarize(text: String, length: String): Summary {
        val clean = text.replace(Regex("-\\n(\\p{Ll})"), "$1").replace(Regex("\\s+"), " ").trim()
        require(clean.length > 40) { "Not enough text to summarise. If this is a scan, run OCR first." }
        val it = BreakIterator.getSentenceInstance(Locale.getDefault()); it.setText(clean)
        val sentences = ArrayList<String>()
        var s = it.first(); var e = it.next()
        while (e != BreakIterator.DONE) { clean.substring(s, e).trim().takeIf { it.length in 25..600 }?.let { sentences += it }; s = e; e = it.next() }
        val words = Regex("\\p{L}[\\p{L}\\p{N}'-]+").findAll(clean.lowercase()).map { it.value }.toList()
        val freq = HashMap<String, Int>()
        words.filter { it.length > 2 && it !in stop }.forEach { freq[it] = (freq[it] ?: 0) + 1 }
        val maxF = (freq.values.maxOrNull() ?: 1).toDouble()
        val keywords = freq.entries.sortedByDescending { it.value }.take(10).map { it.key }
        if (sentences.isEmpty()) return Summary(listOf(clean.take(600)), keywords, words.size)
        val scores = sentences.mapIndexed { idx, sent ->
            val ws = Regex("\\p{L}[\\p{L}\\p{N}'-]+").findAll(sent.lowercase()).map { it.value }.filter { it !in stop && it.length > 2 }.toList()
            val base = if (ws.isEmpty()) 0.0 else ws.sumOf { (freq[it] ?: 0) / maxF } / sqrt(ws.size.toDouble())
            val position = 1.0 + 0.4 * (1.0 - min(1.0, idx / max(1.0, sentences.size * 0.2)))
            base * position * (1.0 + 0.1 * ln(1.0 + ws.size))
        }
        val ratio = when (length) { "short" -> 0.08; "long" -> 0.3; else -> 0.16 }
        val maxN = when (length) { "short" -> 5; "long" -> 25; else -> 12 }
        val n = (sentences.size * ratio).roundToInt().coerceIn(min(3, sentences.size), maxN)
        val chosen = scores.indices.sortedByDescending { scores[it] }.take(n).sorted().map { sentences[it] }
        return Summary(chosen, keywords, words.size)
    }

    fun toPdf(title: String, s: Summary, out: File) {
        val w = TextPdfWriter()
        w.add(Block.Para("Summary of $title", 18f, bold = true, spaceAfter = 4f))
        w.add(Block.Para("${s.wordCount} words in the original · ${s.sentences.size} key sentences", 9f, color = Color.GRAY, spaceAfter = 12f))
        s.sentences.forEach { w.add(Block.Para("•  $it", 11f, spaceAfter = 6f)) }
        if (s.keywords.isNotEmpty()) {
            w.add(Block.Para("Key terms", 13f, bold = true, spaceBefore = 10f))
            w.add(Block.Para(s.keywords.joinToString(" · "), 11f))
        }
        w.save(out)
    }
}

/** Line-based document comparison (LCS). */
object Differ {
    enum class Kind { SAME, ADDED, REMOVED }
    data class Line(val kind: Kind, val text: String, val page: Int)
    data class Result(val lines: List<Line>, val added: Int, val removed: Int)

    fun compare(a: File, b: File): Result {
        val la = PdfOps.open(a).use { pagedLines(it) }
        val lb = PdfOps.open(b).use { pagedLines(it) }
        val out = ArrayList<Line>()
        if (la.size.toLong() * lb.size > 6_000_000L) {
            // too big for one table: compare page by page
            val pa = la.groupBy { it.second }; val pb = lb.groupBy { it.second }
            val pages = (pa.keys + pb.keys).sorted()
            for (p in pages) out += diff(pa[p] ?: emptyList(), pb[p] ?: emptyList())
        } else out += diff(la, lb)
        return Result(out, out.count { it.kind == Kind.ADDED }, out.count { it.kind == Kind.REMOVED })
    }

    private fun pagedLines(doc: PDDocument) = TextExtract.pages(doc).flatMap { p -> p.lines.map { it.text.trim() to p.index + 1 }.filter { it.first.isNotEmpty() } }

    private fun diff(a: List<Pair<String, Int>>, b: List<Pair<String, Int>>): List<Line> {
        val n = a.size; val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            dp[i][j] = if (norm(a[i].first) == norm(b[j].first)) dp[i + 1][j + 1] + 1 else max(dp[i + 1][j], dp[i][j + 1])
        val out = ArrayList<Line>()
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                norm(a[i].first) == norm(b[j].first) -> { out += Line(Kind.SAME, b[j].first, b[j].second); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out += Line(Kind.REMOVED, a[i].first, a[i].second); i++ }
                else -> { out += Line(Kind.ADDED, b[j].first, b[j].second); j++ }
            }
        }
        while (i < n) { out += Line(Kind.REMOVED, a[i].first, a[i].second); i++ }
        while (j < m) { out += Line(Kind.ADDED, b[j].first, b[j].second); j++ }
        return out
    }

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    fun reportPdf(nameA: String, nameB: String, r: Result, out: File) {
        val w = TextPdfWriter()
        w.add(Block.Para("Comparison report", 18f, bold = true, spaceAfter = 4f))
        w.add(Block.Para("Original: $nameA\nChanged: $nameB\n${r.removed} line(s) removed · ${r.added} line(s) added", 10f, color = Color.DKGRAY, spaceAfter = 12f))
        if (r.added == 0 && r.removed == 0) w.add(Block.Para("No text differences found.", 12f))
        for (l in r.lines) {
            if (l.kind == Differ.Kind.SAME) continue
            val prefix = if (l.kind == Kind.ADDED) "+ " else "− "
            val sp = SpannableStringBuilder("$prefix${l.text}   (p. ${l.page})")
            sp.setSpan(BackgroundColorSpan(if (l.kind == Kind.ADDED) Color.rgb(214, 245, 222) else Color.rgb(253, 222, 222)), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            w.add(Block.Para(sp, 10f, spaceAfter = 3f))
        }
        w.save(out)
    }
}

package com.pdfmaster.app.tools

import android.content.Context
import com.pdfmaster.app.engine.Convert
import com.pdfmaster.app.engine.Differ
import com.pdfmaster.app.engine.EditItem
import com.pdfmaster.app.engine.HtmlToPdf
import com.pdfmaster.app.engine.Ocr
import com.pdfmaster.app.engine.PdfOps
import com.pdfmaster.app.engine.Render
import com.pdfmaster.app.engine.Summarizer
import com.pdfmaster.app.engine.TextExtract
import com.pdfmaster.app.engine.Translator
import com.pdfmaster.app.engine.Work
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class RunResult(
    val files: List<File>,
    val message: String,
    val text: String? = null,
    val diff: Differ.Result? = null,
)

typealias Progress = (Float, String) -> Unit

/** Runs a tool's work. Everything happens on the device. */
object ToolRunner {

    suspend fun run(ctx: Context, tool: Tool, inputs: List<File>, o: Map<String, String>, progress: Progress): RunResult {
        val first = inputs.firstOrNull()
        val base = first?.let { Work.baseName(it) } ?: "document"
        fun out(suffix: String, ext: String = "pdf") = Work.outFile(ctx, if (suffix.isEmpty()) base else "${base}_$suffix", ext)
        fun p(label: String): (Float) -> Unit = { f -> progress(f, label) }
        fun pages(f: File) = PdfOps.pageCount(f)

        return when (tool.id) {
            "merge" -> {
                val f = Work.outFile(ctx, "merged", "pdf")
                PdfOps.merge(inputs, f, p("Merging…"))
                RunResult(listOf(f), "Merged ${inputs.size} files into one PDF.")
            }
            "split" -> {
                val n = pages(first!!)
                val groups: List<List<Int>> = when (o["mode"]) {
                    "every" -> (0 until n).map { listOf(it) }
                    "fixed" -> {
                        val k = o["n"]?.toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("Enter how many pages each file should have")
                        (0 until n).chunked(k)
                    }
                    else -> Work.parseRanges(o["ranges"] ?: "", n).map { r -> r.map { it - 1 } }
                }
                val outs = groups.map { g -> out(if (g.size == 1) "page_${g[0] + 1}" else "pages_${g.first() + 1}-${g.last() + 1}") }
                PdfOps.extractGroups(first, groups, outs, p("Splitting…"))
                zipIfMany(ctx, outs, "${base}_split", "Split into ${outs.size} files.")
            }
            "remove" -> {
                val n = pages(first!!)
                val remove = Work.pagesFromSpec(o["pages"].orEmpty().also { require(it.isNotBlank()) { "Enter the pages to remove" } }, n)
                val keep = (0 until n).filter { it !in remove }
                require(keep.isNotEmpty()) { "You can't remove every page" }
                val f = out("edited")
                PdfOps.extractGroups(first, listOf(keep), listOf(f), p("Removing pages…"))
                RunResult(listOf(f), "Removed ${remove.size} page(s). ${keep.size} left.")
            }
            "extract" -> {
                val n = pages(first!!)
                val spec = o["pages"].orEmpty().also { require(it.isNotBlank()) { "Enter the pages to extract" } }
                val sel = Work.parseRanges(spec, n).flatMap { r -> r.map { it - 1 } }.distinct()
                if (o["output"] == "separate") {
                    val outs = sel.map { out("page_${it + 1}") }
                    PdfOps.extractGroups(first, sel.map { listOf(it) }, outs, p("Extracting…"))
                    zipIfMany(ctx, outs, "${base}_pages", "Extracted ${sel.size} page(s).")
                } else {
                    val f = out("extracted")
                    PdfOps.extractGroups(first, listOf(sel), listOf(f), p("Extracting…"))
                    RunResult(listOf(f), "Extracted ${sel.size} page(s) into a new PDF.")
                }
            }
            "compress" -> {
                val level = when (o["level"]) { "extreme" -> PdfOps.Level.EXTREME; "low" -> PdfOps.Level.LOW; else -> PdfOps.Level.RECOMMENDED }
                val f = out("compressed")
                val before = first!!.length()
                val smaller = PdfOps.compress(first, f, level, p("Compressing…"))
                val after = f.length()
                val msg = if (smaller) "Reduced from ${Work.humanSize(before)} to ${Work.humanSize(after)} (−${100 - after * 100 / before}%)."
                else "This PDF is already well optimised; it couldn't be made smaller."
                RunResult(listOf(f), msg)
            }
            "repair" -> {
                val f = out("repaired")
                RunResult(listOf(f), PdfOps.repair(first!!, f, p("Repairing…")))
            }
            "ocr" -> {
                val mode = o["output"] ?: "pdf"
                val pdf = if (mode != "txt") out("ocr") else null
                val txt = if (mode != "pdf") out("text", "txt") else null
                val lines = Ocr.run(first!!, pdf, txt, o["lang"] ?: "latin", progress)
                RunResult(listOfNotNull(pdf, txt), if (lines == 0) "No text was recognised." else "Recognised $lines lines of text.")
            }
            "jpg2pdf" -> {
                val margin = o["margin"]?.toFloatOrNull() ?: 0f
                if (o["single"] != "false") {
                    val f = Work.outFile(ctx, if (inputs.size == 1) base else "images", "pdf")
                    Render.imagesToPdf(inputs, f, o["size"] ?: "a4", o["orientation"] ?: "auto", margin, p("Creating PDF…"))
                    RunResult(listOf(f), "Created a ${inputs.size}-page PDF.")
                } else {
                    val outs = inputs.mapIndexed { i, img ->
                        val f = Work.outFile(ctx, Work.baseName(img), "pdf")
                        Render.imagesToPdf(listOf(img), f, o["size"] ?: "a4", o["orientation"] ?: "auto", margin) { progress((i + it) / inputs.size, "Creating PDFs…") }
                        f
                    }
                    zipIfMany(ctx, outs, "images_pdf", "Created ${outs.size} PDFs.")
                }
            }
            "word2pdf" -> perFile(ctx, inputs, progress, "Converting…") { f, o2 -> Convert.wordToPdf(f, o2) }
            "excel2pdf" -> perFile(ctx, inputs, progress, "Converting…") { f, o2 -> Convert.excelToPdf(f, o2) }
            "ppt2pdf" -> perFile(ctx, inputs, progress, "Converting…") { f, o2 -> Convert.pptToPdf(f, o2) {} }
            "html2pdf" -> {
                val url = o["url"].orEmpty().trim()
                require(first != null || url.isNotEmpty()) { "Enter a web address or choose an HTML file" }
                val name = first?.let { Work.baseName(it) } ?: url.removePrefix("https://").removePrefix("http://").substringBefore('/').ifBlank { "webpage" }
                val f = Work.outFile(ctx, name, "pdf")
                progress(-1f, "Loading page…")
                withContext(Dispatchers.Main) { HtmlToPdf.convert(ctx, url.ifEmpty { null }, first, f, o["layout"] != "mobile", o["paper"] == "letter") }
                RunResult(listOf(f), "Saved the web page as a ${Render.pageCount(f)}-page PDF.")
            }
            "pdf2jpg" -> {
                val png = o["format"] == "png"
                val dir = File(Work.tmpDir(ctx), "img_${System.nanoTime()}").apply { mkdirs() }
                val imgs = if (o["mode"] == "extract") Render.extractImages(first!!, dir, png, p("Extracting images…"))
                else Render.pdfToImages(first!!, dir, o["quality"]?.toIntOrNull() ?: 150, png, p("Rendering pages…"))
                require(imgs.isNotEmpty()) { "No images found in this PDF." }
                val moved = imgs.map { val t = Work.outFile(ctx, it.nameWithoutExtension, it.extension); it.renameTo(t); t }
                zipIfMany(ctx, moved, "${base}_images", "Created ${moved.size} image(s).")
            }
            "pdf2word" -> { val f = out("", "docx"); Convert.pdfToWord(first!!, f, o["mode"] == "layout", p("Converting to Word…")); RunResult(listOf(f), "Converted to Word.") }
            "pdf2ppt" -> { val f = out("", "pptx"); Convert.pdfToPowerPoint(first!!, f, o["mode"] == "text", p("Converting to PowerPoint…")); RunResult(listOf(f), "Converted to PowerPoint.") }
            "pdf2excel" -> { val f = out("", "xlsx"); Convert.pdfToExcel(first!!, f, o["sheets"] != "single", p("Converting to Excel…")); RunResult(listOf(f), "Converted to Excel. Check the columns, as complex tables may need tidying.") }
            "pdfa" -> {
                val f = out("pdfa")
                PdfOps.pdfA(first!!, f, o["part"]?.toIntOrNull() ?: 2, o["flatten"] == "true", p("Converting…"))
                RunResult(listOf(f), "Added PDF/A identification, metadata and an sRGB output intent. For guaranteed compliance, check it with a PDF/A validator.")
            }
            "rotate" -> {
                val n = pages(first!!)
                val sel = o["pages"].orEmpty().takeIf { it.isNotBlank() }?.let { Work.pagesFromSpec(it, n) }
                val f = out("rotated")
                PdfOps.rotate(first, f, o["angle"]?.toIntOrNull() ?: 90, sel)
                RunResult(listOf(f), "Rotated ${sel?.size ?: n} page(s).")
            }
            "numbers" -> {
                val f = out("numbered")
                PdfOps.pageNumbers(first!!, f, o["position"] ?: "bc", o["format"] ?: "n", o["start"]?.toIntOrNull() ?: 1, o["size"]?.toFloatOrNull() ?: 11f, o["skipFirst"] == "true")
                RunResult(listOf(f), "Added page numbers.")
            }
            "watermark" -> {
                val n = pages(first!!)
                val sel = o["pages"].orEmpty().takeIf { it.isNotBlank() }?.let { Work.pagesFromSpec(it, n) }
                val f = out("watermarked")
                val opacity = (o["opacity"]?.toFloatOrNull() ?: 25f) / 100f
                val tile = o["layout"] == "tile"
                if (o["type"] == "image") {
                    val img = o["image"].orEmpty().takeIf { it.isNotEmpty() }?.let { File(it) } ?: throw IllegalArgumentException("Choose an image for the watermark")
                    PdfOps.watermarkImage(first, f, img, (o["scale"]?.toFloatOrNull() ?: 50f) / 100f, opacity, tile, sel)
                } else {
                    val color = (o["color"] ?: "888888").toInt(16)
                    PdfOps.watermarkText(first, f, o["text"].orEmpty(), o["size"]?.toFloatOrNull() ?: 54f, opacity, o["angle"]?.toFloatOrNull() ?: 45f, color, tile, sel)
                }
                RunResult(listOf(f), "Added the watermark.")
            }
            "unlock" -> {
                val f = out("unlocked")
                PdfOps.unlock(first!!, f, o["password"].orEmpty())
                RunResult(listOf(f), "Password removed.")
            }
            "protect" -> {
                val pw = o["password"].orEmpty()
                require(pw.isNotEmpty()) { "Enter a password" }
                require(pw == o["confirm"]) { "The passwords don't match" }
                val f = out("protected")
                PdfOps.protect(first!!, f, pw, null, o["print"] != "false", o["copy"] != "false", o["modify"] == "true")
                RunResult(listOf(f), "Protected with AES-256 encryption. Keep your password safe: it can't be recovered.")
            }
            "compare" -> {
                require(inputs.size == 2) { "Choose exactly two PDF files" }
                progress(-1f, "Comparing…")
                val r = Differ.compare(inputs[0], inputs[1])
                val f = Work.outFile(ctx, "comparison_report", "pdf")
                Differ.reportPdf(inputs[0].name, inputs[1].name, r, f)
                val msg = if (r.added + r.removed == 0) "No text differences found." else "${r.removed} line(s) removed and ${r.added} line(s) added."
                RunResult(listOf(f), msg, diff = r)
            }
            "summarize" -> {
                progress(-1f, "Reading…")
                val text = PdfOps.open(first!!).use { TextExtract.plainText(it) }
                val s = Summarizer.summarize(text, o["length"] ?: "medium")
                val f = out("summary")
                Summarizer.toPdf(first.name, s, f)
                val body = s.sentences.joinToString("\n\n") { "• $it" } + if (s.keywords.isNotEmpty()) "\n\nKey terms: " + s.keywords.joinToString(", ") else ""
                RunResult(listOf(f), "Picked the ${s.sentences.size} most important sentences from ${s.wordCount} words.", text = body)
            }
            "translate" -> {
                val target = o["target"] ?: "en"
                val f = out("translated_$target")
                val src = Translator.translatePdf(first!!, f, o["source"] ?: "auto", target, progress)
                val name = Translator.languages.firstOrNull { it.first == src }?.second ?: src
                RunResult(listOf(f), "Translated from $name. Layout is simplified to flowing text.")
            }
            else -> throw IllegalStateException("Unknown tool ${tool.id}")
        }
    }

    private suspend fun perFile(ctx: Context, inputs: List<File>, progress: Progress, label: String, block: suspend (File, File) -> Unit): RunResult {
        val outs = inputs.mapIndexed { i, f ->
            progress(i.toFloat() / inputs.size, label)
            val o = Work.outFile(ctx, Work.baseName(f), "pdf")
            block(f, o)
            o
        }
        progress(1f, label)
        return RunResult(outs, if (outs.size == 1) "Converted to PDF." else "Converted ${outs.size} files to PDF.")
    }

    private fun zipIfMany(ctx: Context, outs: List<File>, zipName: String, msg: String): RunResult {
        if (outs.size <= 1) return RunResult(outs, msg)
        val z = Work.zip(outs, Work.outFile(ctx, zipName, "zip"))
        outs.forEach { it.delete() }
        return RunResult(listOf(z), "$msg Packed into a ZIP file.")
    }

    // ----- editor-based tools -----

    fun runEditor(ctx: Context, tool: Tool, input: File, items: List<EditItem>, cropAll: Boolean, progress: Progress): RunResult {
        val base = Work.baseName(input)
        return when (tool.id) {
            "redact" -> {
                val boxes = items.filterIsInstance<EditItem.Box>()
                val f = Work.outFile(ctx, "${base}_redacted", "pdf")
                PdfOps.redact(input, f, boxes) { progress(it, "Redacting…") }
                RunResult(listOf(f), "Redacted ${boxes.size} area(s) on ${boxes.map { it.page }.distinct().size} page(s). The covered content has been removed from the file.")
            }
            "crop" -> {
                val box = items.filterIsInstance<EditItem.Box>().lastOrNull() ?: throw IllegalArgumentException("Drag on the page to select the area to keep")
                val f = Work.outFile(ctx, "${base}_cropped", "pdf")
                PdfOps.crop(input, f, box.l, box.t, box.r, box.b, if (cropAll) null else box.page)
                RunResult(listOf(f), if (cropAll) "Cropped every page." else "Cropped page ${box.page + 1}.")
            }
            else -> {
                require(items.isNotEmpty()) { if (tool.id == "sign") "Add your signature to the page first" else "Add something to the page first" }
                val f = Work.outFile(ctx, "${base}_${if (tool.id == "sign") "signed" else "edited"}", "pdf")
                progress(-1f, "Saving…")
                PdfOps.applyEdits(input, f, items)
                RunResult(listOf(f), if (tool.id == "sign") "Signed and saved." else "Saved your changes.")
            }
        }
    }
}

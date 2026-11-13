package com.pdfmaster.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.pdfmaster.app.engine.AppCtx
import com.pdfmaster.app.engine.Convert
import com.pdfmaster.app.engine.Differ
import com.pdfmaster.app.engine.EditItem
import com.pdfmaster.app.engine.Office
import com.pdfmaster.app.engine.PdfOps
import com.pdfmaster.app.engine.PdfPasswordException
import com.pdfmaster.app.engine.Render
import com.pdfmaster.app.engine.Summarizer
import com.pdfmaster.app.engine.TextExtract
import com.pdfmaster.app.engine.Work
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class EngineTest {
    private lateinit var dir: File
    private val outDir = File(System.getProperty("pdfmaster.testOut") ?: "build/test-artifacts").apply { mkdirs() }

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppCtx.app = ctx
        PDFBoxResourceLoader.init(ctx)
        dir = File(ctx.cacheDir, "t${System.nanoTime()}").apply { mkdirs() }
    }

    private fun samplePdf(name: String, pages: Int, linesFor: (Int) -> List<String> = { p -> listOf("Quarterly report page ${p + 1}", "Revenue grew strongly this quarter in every region.", "Name    Amount    Region", "Alice    1200    North", "Bob    950    South") }, withImage: Boolean = false): File {
        val f = File(dir, name)
        PDDocument().use { doc ->
            repeat(pages) { p ->
                val page = PDPage(PDRectangle.A4); doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    var y = 780f
                    linesFor(p).forEachIndexed { i, line ->
                        cs.beginText(); cs.setFont(if (i == 0) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA, if (i == 0) 18f else 11f)
                        // put table-like rows in columns
                        val cells = line.split(Regex(" {3,}"))
                        if (cells.size > 1) {
                            cells.forEachIndexed { ci, c -> if (ci > 0) { cs.endText(); cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 11f) }; cs.newLineAtOffset(60f + ci * 150f, y); cs.showText(c) }
                        } else { cs.newLineAtOffset(60f, y); cs.showText(line) }
                        cs.endText()
                        y -= if (i == 0) 30f else 18f
                    }
                    if (withImage) {
                        val bmp = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
                        val rnd = java.util.Random(7)
                        val px = IntArray(1600 * 1200) { i -> val x = i % 1600; val yy = i / 1600; val n = rnd.nextInt(24); Color.rgb((x / 7 + n) % 256, (yy / 5 + n) % 256, ((x + yy) / 11 + n) % 256) }
                        bmp.setPixels(px, 0, 1600, 0, 0, 1600, 1200)
                        val img = LosslessFactory.createFromImage(doc, bmp)
                        cs.drawImage(img, 60f, 200f, 400f, 300f)
                    }
                }
            }
            doc.save(f)
        }
        return f
    }

    /** Robolectric has no native android.graphics.pdf.PdfDocument; those paths are verified on device. */
    private val pdfDocumentWorks: Boolean by lazy {
        runCatching { val d = android.graphics.pdf.PdfDocument(); val p = d.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(10, 10, 1).create()); d.finishPage(p); d.close() }.isSuccess
    }

    private fun out(name: String) = File(outDir, name)
    private fun pages(f: File) = PDDocument.load(f).use { it.numberOfPages }
    private fun text(f: File) = PDDocument.load(f).use { TextExtract.plainText(it) }

    @Test fun ranges() {
        assertEquals(listOf(1..3, 5..5, 8..10), Work.parseRanges("1-3, 5, 8-", 10))
        try { Work.parseRanges("12", 10); fail() } catch (e: IllegalArgumentException) {}
    }

    @Test fun mergeSplitExtractRotateOrganize() {
        val a = samplePdf("a.pdf", 3); val b = samplePdf("b.pdf", 2)
        val m = out("merged.pdf"); PdfOps.merge(listOf(a, b), m) {}
        assertEquals(5, pages(m))
        val o1 = File(dir, "s1.pdf"); val o2 = File(dir, "s2.pdf")
        PdfOps.extractGroups(m, listOf(listOf(0, 1), listOf(4)), listOf(o1, o2)) {}
        assertEquals(2, pages(o1)); assertEquals(1, pages(o2))
        val r = File(dir, "r.pdf"); PdfOps.rotate(a, r, 90, setOf(1))
        PDDocument.load(r).use { assertEquals(0, it.getPage(0).rotation); assertEquals(90, it.getPage(1).rotation) }
        val org = out("organized.pdf")
        PdfOps.organize(listOf(a, b), listOf(PdfOps.PageRef(1, 1, 0), PdfOps.PageRef(0, 0, 180), PdfOps.PageRef(0, -1, 0)), org)
        PDDocument.load(org).use { assertEquals(3, it.numberOfPages); assertEquals(180, it.getPage(1).rotation) }
        assertTrue(text(org).contains("page 2"))
    }

    @Test fun protectUnlock() {
        val a = samplePdf("a.pdf", 1)
        val p = out("protected.pdf"); PdfOps.protect(a, p, "s3cret", null, true, false, false)
        assertTrue(PdfOps.needsPassword(p))
        try { PdfOps.open(p).close(); fail() } catch (e: PdfPasswordException) {}
        try { PdfOps.unlock(p, File(dir, "x.pdf"), "wrong"); fail() } catch (e: PdfPasswordException) { assertEquals("Incorrect password.", e.message) }
        val u = out("unlocked.pdf"); PdfOps.unlock(p, u, "s3cret")
        assertTrue(!PdfOps.needsPassword(u))
        PDDocument.load(u).use { assertTrue(!it.isEncrypted) }
    }

    @Test fun stampsAndEdits() {
        val a = samplePdf("a.pdf", 2)
        val w = out("watermarked.pdf"); PdfOps.watermarkText(a, w, "CONFIDENTIAL", 48f, 0.3f, 0f, 0x888888, false, null)
        PdfOps.watermarkText(a, File(dir, "diag.pdf"), "CONFIDENTIAL", 48f, 0.3f, 45f, 0x888888, false, null)
        assertTrue(text(w).contains("CONFIDENTIAL"))
        val wt = File(dir, "wt.pdf"); PdfOps.watermarkText(a, wt, "DRAFT", 30f, 0.2f, 0f, 0xff0000, true, setOf(0))
        val n = out("numbered.pdf"); PdfOps.pageNumbers(a, n, "br", "page_n_of_t", 1, 11f, false)
        assertTrue(text(n).contains("Page 2 of 2"))
        val sig = File(dir, "sig.png"); Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }.compress(Bitmap.CompressFormat.PNG, 100, sig.outputStream())
        val e = out("edited.pdf")
        PdfOps.applyEdits(a, e, listOf(
            EditItem.Text(0, 0.1f, 0.5f, "Added note", 0.03f, Color.RED),
            EditItem.Ink(0, listOf(0.1f to 0.6f, 0.3f to 0.65f, 0.5f to 0.6f), 0.004f, Color.BLACK),
            EditItem.Box(1, 0.1f, 0.1f, 0.4f, 0.2f, Color.YELLOW, 0.4f),
            EditItem.Picture(1, 0.5f, 0.8f, 0.3f, 0.08f, sig),
        ))
        assertTrue(text(e).contains("Added note"))
        // rotated page: overlay must still land on the page
        val rot = File(dir, "rot.pdf"); PdfOps.rotate(a, rot, 90, null)
        val er = File(dir, "er.pdf"); PdfOps.applyEdits(rot, er, listOf(EditItem.Text(0, 0.1f, 0.1f, "Rotated text", 0.03f, Color.BLACK)))
        assertTrue(text(er).contains("Rotated text"))
        val c = out("cropped.pdf"); PdfOps.crop(a, c, 0.1f, 0.1f, 0.9f, 0.5f, null)
        PDDocument.load(c).use { val b = it.getPage(0).cropBox; assertEquals(595f * 0.8f, b.width, 1f); assertEquals(842f * 0.4f, b.height, 1f) }
    }

    @Test fun findAndRedact() {
        val a = samplePdf("a.pdf", 2)
        val boxes = PdfOps.findText(a, "revenue")
        assertEquals(2, boxes.size)
        boxes.forEach { assertTrue(it.l in 0f..1f && it.r > it.l && it.b > it.t) }
        val r = out("redacted.pdf"); PdfOps.redact(a, r, boxes, dpi = 72) {}
        assertEquals(2, pages(r))
        assertTrue("redacted text must be gone", !text(r).contains("Revenue"))
    }

    @Test fun compressRepairPdfA() {
        val a = samplePdf("img.pdf", 2, withImage = true)
        val c = out("compressed.pdf")
        val smaller = PdfOps.compress(a, c, PdfOps.Level.EXTREME) {}
        println("compress: ${a.length()} -> ${c.length()} smaller=$smaller")
        assertTrue("compression should shrink a photo-heavy PDF", smaller && c.length() < a.length() / 2)
        val rep = out("repaired.pdf"); println(PdfOps.repair(a, rep) {}); assertEquals(2, pages(rep))
        // truncated file
        val broken = File(dir, "broken.pdf"); broken.writeBytes(a.readBytes().copyOf((a.length() * 0.97).toInt()))
        val rep2 = File(dir, "rep2.pdf"); println(PdfOps.repair(broken, rep2) {}); assertTrue(pages(rep2) >= 1)
        val pa = out("pdfa.pdf"); PdfOps.pdfA(samplePdf("t.pdf", 1), pa, 2, false) {}
        PDDocument.load(pa).use { d ->
            assertTrue(String(d.documentCatalog.metadata.toByteArray()).contains("<pdfaid:part>2</pdfaid:part>"))
            assertEquals(1, d.documentCatalog.outputIntents.size)
        }
    }

    @Test fun renderAndImages() {
        val a = samplePdf("a.pdf", 2, withImage = true)
        Render.Doc(a).use { d -> assertEquals(2, d.pageCount); val b = d.render(0, 300); assertEquals(300, b.width) }
        val imgs = Render.pdfToImages(a, dir, 72, false) {}
        assertEquals(2, imgs.size)
        val ex = Render.extractImages(a, dir, true) {}
        assertEquals(2, ex.size) // one image per page
        val pdf = out("images.pdf"); Render.imagesToPdf(imgs, pdf, "a4", "auto", 18f) {}
        assertEquals(2, pages(pdf))
        val fit = File(dir, "fit.pdf"); Render.imagesToPdf(imgs.take(1), fit, "fit", "auto", 0f) {}
        assertEquals(1, pages(fit))
    }

    @Test fun pdfToOffice() {
        val a = samplePdf("report.pdf", 2)
        val docx = out("report.docx"); Convert.pdfToWord(a, docx, false) {}
        val docxImg = out("report_layout.docx"); Convert.pdfToWord(a, docxImg, true) {}
        val xlsx = out("report.xlsx"); Convert.pdfToExcel(a, xlsx, true) {}
        val pptx = out("report.pptx"); Convert.pdfToPowerPoint(a, pptx, true) {}
        val pptxImg = out("report_images.pptx"); Convert.pdfToPowerPoint(a, pptxImg, false) {}
        val pt = PDDocument.load(a).use { TextExtract.pages(it) }
        val rows = Convert.tableRows(pt[0])
        println(rows.joinToString("\n"))
        assertTrue(rows.any { it.size >= 3 && it[0] == "Alice" && it[1] == "1200" })
        // round-trip: our docx/xlsx/pptx readers
        val blocks = Office.docxToBlocks(docx)
        assertTrue(blocks.isNotEmpty())
        val sheets = Office.xlsxSheets(xlsx)
        assertEquals(2, sheets.size)
        assertTrue(sheets[0].rows.any { it.contains("1200") })
    }

    @Test fun officeToPdf() {
        val docx = out("report.docx").takeIf { it.exists() } ?: run { pdfToOffice(); out("report.docx") }
        val csvRows = Office.csvRows("Name,Amount\n\"Smith, J\",10\nLee,20\n")
        assertEquals(listOf("Smith, J", "10"), csvRows[1])
        val blocks = Office.docxToBlocks(docx)
        assertTrue(blocks.any { it is com.pdfmaster.app.engine.Block.Para && it.text.toString().contains("Revenue") })
        assumeTrue("PdfDocument unavailable under Robolectric", pdfDocumentWorks)
        val p1 = out("word2pdf.pdf"); Convert.wordToPdf(docx, p1); assertTrue(pages(p1) >= 1)
        assertTrue(text(p1).contains("Revenue"))
        val csv = File(dir, "data.csv"); csv.writeText("Name,Amount\n\"Smith, J\",10\nLee,20\n")
        val p2 = out("csv2pdf.pdf"); Convert.excelToPdf(csv, p2); assertTrue(text(p2).contains("Smith, J"))
        val p3 = out("xlsx2pdf.pdf"); Convert.excelToPdf(out("report.xlsx"), p3); assertTrue(text(p3).contains("Alice"))
        val p4 = out("pptx2pdf.pdf"); Convert.pptToPdf(out("report.pptx"), p4) {}; assertEquals(2, pages(p4))
        val txt = File(dir, "notes.txt"); txt.writeText("Hello world\n\nSecond paragraph")
        val p5 = File(dir, "t.pdf"); Convert.wordToPdf(txt, p5); assertTrue(text(p5).contains("Second paragraph"))
    }

    @Test fun summarizeAndCompare() {
        val long = (1..40).map { i -> listOf("Solar energy adoption increased by $i percent in the region this year.", "Battery storage costs fell sharply, making solar energy more competitive.", "Local councils approved new solar farms near the river.", "The weather was mild.").shuffled(java.util.Random(i.toLong())).first() }
        val s = Summarizer.summarize(long.joinToString(" "), "short")
        assertTrue(s.sentences.isNotEmpty() && s.sentences.size <= 5)
        assertTrue(s.keywords.contains("solar"))
        val a = samplePdf("v1.pdf", 1, linesFor = { listOf("Title", "Line one", "Line two", "Line three") })
        val b = samplePdf("v2.pdf", 1, linesFor = { listOf("Title", "Line one", "Line 2 changed", "Line three", "Line four") })
        val r = Differ.compare(a, b)
        assertEquals(2, r.added); assertEquals(1, r.removed)
        assumeTrue("PdfDocument unavailable under Robolectric", pdfDocumentWorks)
        val rep = out("compare.pdf"); Differ.reportPdf("v1.pdf", "v2.pdf", r, rep)
        assertTrue(text(rep).contains("Line four"))
    }
}

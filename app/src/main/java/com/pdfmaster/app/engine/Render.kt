package com.pdfmaster.app.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.SizeF
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Page rasterisation (Android's PDFium renderer, with a PdfBox fallback) and image conversions. */
object Render {

    /** An open document for rendering. Thread-safe; close when done. */
    class Doc(val file: File, password: String? = null) : Closeable {
        private var pfd: ParcelFileDescriptor? = null
        private var renderer: PdfRenderer? = null
        private var box: PDDocument? = null
        private var boxRenderer: PDFRenderer? = null

        init {
            try {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = fd
                val r = PdfRenderer(fd)
                renderer = r
                if (r.pageCount <= 0) throw IllegalStateException("no pages")
            } catch (e: Throwable) {
                runCatching { renderer?.close() }
                runCatching { pfd?.close() }
                pfd = null
                renderer = null
                val d = PdfOps.open(file, password)
                box = d
                boxRenderer = PDFRenderer(d)
            }
        }

        val pageCount: Int get() = renderer?.pageCount ?: box!!.numberOfPages

        /** Page size in points as displayed (rotation applied). */
        @Synchronized
        fun pageSize(i: Int): SizeF {
            renderer?.let { r -> r.openPage(i).use { p -> return SizeF(p.width.toFloat(), p.height.toFloat()) } }
            val page = box!!.getPage(i)
            val b = page.cropBox
            val rot = ((page.rotation % 360) + 360) % 360
            return if (rot == 90 || rot == 270) SizeF(b.height, b.width) else SizeF(b.width, b.height)
        }

        /** Renders page [i] at [widthPx] wide on white (capped to ~40 MP). */
        @Synchronized
        fun render(i: Int, widthPx: Int): Bitmap {
            val size = pageSize(i)
            var w = widthPx.coerceAtLeast(16)
            var h = (w * size.height / size.width).roundToInt().coerceAtLeast(16)
            val maxPx = 40_000_000f
            if (w.toFloat() * h > maxPx) {
                val s = sqrt(maxPx / (w.toFloat() * h)); w = (w * s).toInt(); h = (h * s).toInt()
            }
            renderer?.let { r ->
                r.openPage(i).use { p ->
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
            return boxRenderer!!.renderImage(i, w / size.width, ImageType.RGB)
        }

        override fun close() {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            runCatching { box?.close() }
        }
    }

    fun pageCount(f: File): Int = Doc(f).use { it.pageCount }

    // ---------- PDF -> images ----------

    fun pdfToImages(file: File, outDir: File, dpi: Int, png: Boolean, progress: (Float) -> Unit): List<File> {
        val base = Work.baseName(file)
        val out = ArrayList<File>()
        Doc(file).use { d ->
            for (i in 0 until d.pageCount) {
                val size = d.pageSize(i)
                val bmp = d.render(i, (size.width / 72f * dpi).roundToInt())
                val f = File(outDir, "${base}_page_${i + 1}.${if (png) "png" else "jpg"}")
                f.outputStream().use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
                bmp.recycle()
                out += f
                progress((i + 1f) / d.pageCount)
            }
        }
        return out
    }

    /** Extracts embedded raster images. */
    fun extractImages(file: File, outDir: File, png: Boolean, progress: (Float) -> Unit): List<File> {
        val base = Work.baseName(file)
        val out = ArrayList<File>()
        val seen = HashSet<Any>()
        PdfOps.open(file).use { doc ->
            fun walk(res: PDResources?, depth: Int) {
                if (res == null || depth > 8) return
                for (name in res.xObjectNames) {
                    val xo = runCatching { res.getXObject(name) }.getOrNull() ?: continue
                    if (!seen.add(xo.cosObject)) continue
                    when (xo) {
                        is PDImageXObject -> {
                            val bmp = runCatching { xo.image }.getOrNull() ?: continue
                            if (bmp.width < 16 || bmp.height < 16) continue
                            val f = File(outDir, "${base}_image_${out.size + 1}.${if (png) "png" else "jpg"}")
                            val toSave = if (!png && bmp.hasAlpha()) flattenOnWhite(bmp) else bmp
                            f.outputStream().use { toSave.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
                            out += f
                        }
                        is PDFormXObject -> walk(xo.resources, depth + 1)
                    }
                }
            }
            val n = doc.numberOfPages
            doc.pages.forEachIndexed { i, page -> walk(page.resources, 0); progress((i + 1f) / n) }
        }
        return out
    }

    fun flattenOnWhite(src: Bitmap): Bitmap {
        val b = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return b
    }

    // ---------- images -> PDF ----------

    /** Decodes an image file, downsampled so the long side is <= [maxDim], with EXIF orientation applied. */
    fun decodeImage(file: File, maxDim: Int = 3000): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0) { "\"${file.name}\" is not a supported image" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim * 2) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("\"${file.name}\" could not be decoded")
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val longSide = max(bmp.width, bmp.height)
        if (longSide > maxDim) { val s = maxDim.toFloat() / longSide; m.postScale(s, s) }
        val result = if (m.isIdentity) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return if (result.hasAlpha()) flattenOnWhite(result) else result
    }

    /**
     * @param pageSize "fit" | "a4" | "letter"
     * @param orientation "auto" | "portrait" | "landscape"
     * @param margin points
     */
    fun imagesToPdf(images: List<File>, out: File, pageSize: String, orientation: String, margin: Float, progress: (Float) -> Unit) {
        PDDocument(PdfOps.mem()).use { doc ->
            images.forEachIndexed { idx, f ->
                val bmp = decodeImage(f)
                val img = JPEGFactory.createFromImage(doc, bmp, 0.9f)
                val imgW = bmp.width.toFloat(); val imgH = bmp.height.toFloat()
                bmp.recycle()
                val rect = when (pageSize) {
                    "a4", "letter" -> {
                        val base = if (pageSize == "a4") PDRectangle.A4 else PDRectangle.LETTER
                        val landscape = when (orientation) { "landscape" -> true; "portrait" -> false; else -> imgW > imgH }
                        if (landscape) PDRectangle(base.height, base.width) else PDRectangle(base.width, base.height)
                    }
                    else -> { // fit: 150 dpi, at most 14 inches on the long side
                        var w = imgW * 72f / 150f; var h = imgH * 72f / 150f
                        val s = min(1f, 1008f / max(w, h)); w *= s; h *= s
                        PDRectangle(w + 2 * margin, h + 2 * margin)
                    }
                }
                val page = PDPage(rect)
                doc.addPage(page)
                val availW = rect.width - 2 * margin; val availH = rect.height - 2 * margin
                val s = min(availW / imgW, availH / imgH)
                val dw = imgW * s; val dh = imgH * s
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(img, (rect.width - dw) / 2, (rect.height - dh) / 2, dw, dh)
                }
                progress((idx + 1f) / images.size)
            }
            doc.save(out)
        }
    }
}

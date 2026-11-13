package com.pdfmaster.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A language the OCR can read. */
data class OcrLang(val code: String, val name: String, val engine: Engine, val tess: String?) {
    enum class Engine { MLKIT_LATIN, MLKIT_DEVANAGARI, TESSERACT }
}

object OcrLangs {
    val all = listOf(
        OcrLang("latin", "English & other Latin-script languages", OcrLang.Engine.MLKIT_LATIN, null),
        OcrLang("hin", "Hindi · हिन्दी", OcrLang.Engine.MLKIT_DEVANAGARI, "hin"),
        OcrLang("mar", "Marathi · मराठी", OcrLang.Engine.MLKIT_DEVANAGARI, "mar"),
        OcrLang("nep", "Nepali · नेपाली", OcrLang.Engine.MLKIT_DEVANAGARI, "nep"),
        OcrLang("san", "Sanskrit · संस्कृतम्", OcrLang.Engine.MLKIT_DEVANAGARI, "san"),
        OcrLang("ben", "Bengali · বাংলা", OcrLang.Engine.TESSERACT, "ben"),
        OcrLang("asm", "Assamese · অসমীয়া", OcrLang.Engine.TESSERACT, "asm"),
        OcrLang("guj", "Gujarati · ગુજરાતી", OcrLang.Engine.TESSERACT, "guj"),
        OcrLang("pan", "Punjabi · ਪੰਜਾਬੀ", OcrLang.Engine.TESSERACT, "pan"),
        OcrLang("ori", "Odia · ଓଡ଼ିଆ", OcrLang.Engine.TESSERACT, "ori"),
        OcrLang("tam", "Tamil · தமிழ்", OcrLang.Engine.TESSERACT, "tam"),
        OcrLang("tel", "Telugu · తెలుగు", OcrLang.Engine.TESSERACT, "tel"),
        OcrLang("kan", "Kannada · ಕನ್ನಡ", OcrLang.Engine.TESSERACT, "kan"),
        OcrLang("mal", "Malayalam · മലയാളം", OcrLang.Engine.TESSERACT, "mal"),
        OcrLang("urd", "Urdu · اردو", OcrLang.Engine.TESSERACT, "urd"),
    )
    fun get(code: String?) = all.firstOrNull { it.code == code } ?: all.first()
    val choices: List<Pair<String, String>> get() = all.map { it.code to it.name }
}

data class OcrLine(val text: String, val box: Rect)

/** One OCR session for a language; reuse it for every page of a document, then close it. */
class OcrSession private constructor(private val ml: TextRecognizer?, private val tess: TessBaseAPI?) : Closeable {

    fun lines(bmp: Bitmap): List<OcrLine> = throw UnsupportedOperationException()

    suspend fun recognize(bmp: Bitmap): List<OcrLine> {
        ml?.let { r ->
            val res = r.process(InputImage.fromBitmap(bmp, 0)).await()
            return res.textBlocks.flatMap { it.lines }.mapNotNull { l -> l.boundingBox?.let { OcrLine(l.text, it) } }.filter { it.text.isNotBlank() }
        }
        val t = tess!!
        return withContext(Dispatchers.Default) {
            synchronized(t) {
                t.setImage(bmp)
                t.getUTF8Text() // runs recognition
                val out = ArrayList<OcrLine>()
                val it = t.resultIterator ?: return@synchronized out
                try {
                    it.begin()
                    do {
                        val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.replace(Regex("[\u200C\u200D](?=\\s|$)"), "")?.trim().orEmpty()
                        val rect = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                        if (text.isNotEmpty() && rect != null && it.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE) > 20f) out += OcrLine(text, rect)
                    } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                } finally { it.delete() }
                out
            }
        }
    }

    override fun close() { runCatching { ml?.close() }; runCatching { tess?.recycle() } }

    companion object {
        /** Opens a session, downloading Tesseract language data the first time a language is used. */
        suspend fun open(ctx: Context, code: String, status: (String) -> Unit = {}): OcrSession {
            val lang = OcrLangs.get(code)
            when (lang.engine) {
                OcrLang.Engine.MLKIT_LATIN -> return OcrSession(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS), null)
                OcrLang.Engine.MLKIT_DEVANAGARI -> {
                    val r = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                    // The Devanagari model comes from Google Play services; check it is usable, else fall back to Tesseract.
                    val ok = runCatching { r.process(InputImage.fromBitmap(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888), 0)).await() }.isSuccess
                    if (ok) return OcrSession(r, null)
                    r.close()
                }
                else -> {}
            }
            return withContext(Dispatchers.IO) {
                val base = File(ctx.filesDir, "tesseract")
                TessData.ensure(base, listOf(lang.tess!!, "eng"), status)
                val api = TessBaseAPI()
                if (!api.init(base.path, "${lang.tess}+eng", TessBaseAPI.OEM_LSTM_ONLY)) {
                    api.recycle(); throw IllegalStateException("Couldn't load the ${lang.name} OCR data")
                }
                api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                OcrSession(null, api)
            }
        }
    }
}

/** Downloads Tesseract trained models (once) into app storage. */
object TessData {
    private fun url(code: String) = if (code == "eng") "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/eng.traineddata"
    else "https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main/$code.traineddata"

    fun installed(base: File, code: String) = File(base, "tessdata/$code.traineddata").let { it.exists() && it.length() > 100_000 }

    fun ensure(base: File, codes: List<String>, status: (String) -> Unit) {
        val dir = File(base, "tessdata").apply { mkdirs() }
        for (code in codes) {
            if (installed(base, code)) continue
            val name = OcrLangs.all.firstOrNull { it.tess == code }?.name?.substringBefore(" ·") ?: "English"
            status("Downloading $name language data (one time)…")
            val tmp = File(dir, "$code.part")
            try {
                var conn = URL(url(code)).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000; conn.readTimeout = 60_000; conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var done = 0L; var n: Int; var lastPct = -1
                        while (input.read(buf).also { n = it } >= 0) {
                            out.write(buf, 0, n); done += n
                            if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != lastPct && pct % 5 == 0) { lastPct = pct; status("Downloading $name language data… $pct%") } }
                        }
                    }
                }
                conn.disconnect()
                if (!tmp.renameTo(File(dir, "$code.traineddata"))) throw IllegalStateException("Couldn't save the language data")
            } catch (e: Exception) {
                tmp.delete()
                throw IllegalStateException("Couldn't download the $name language data. Connect to the internet once and try again. (${e.message})")
            }
        }
    }
}

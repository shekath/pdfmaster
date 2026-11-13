package com.pdfmaster.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

/** Renders a web page or HTML file into paginated PDF pages using an off-screen WebView. Must be called on the main thread. */
object HtmlToPdf {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun convert(ctx: Context, url: String?, htmlFile: File?, out: File, desktop: Boolean, letter: Boolean): Unit = suspendCancellableCoroutine { cont ->
        val density = ctx.resources.displayMetrics.density
        val cssWidth = if (desktop) 1100 else 420
        val widthPx = (cssWidth * density).toInt()
        val wv = WebView(ctx)
        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadWithOverviewMode = false
        wv.settings.useWideViewPort = desktop
        wv.settings.allowFileAccess = htmlFile != null
        wv.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
        wv.layout(0, 0, widthPx, 2000)
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        val timeout = Runnable {
            if (!finished) { finished = true; wv.destroy(); if (cont.isActive) cont.resumeWithException(IllegalStateException("The page took too long to load.")) }
        }
        handler.postDelayed(timeout, 45_000)
        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame && !finished) {
                    finished = true; handler.removeCallbacks(timeout); wv.destroy()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Couldn't load the page: ${error.description}"))
                }
            }
            override fun onPageFinished(view: WebView, u: String?) {
                if (finished) return
                handler.postDelayed({
                    if (finished) return@postDelayed
                    finished = true
                    handler.removeCallbacks(timeout)
                    try {
                        val contentH = (wv.contentHeight * density).toInt().coerceIn(100, 60_000)
                        wv.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(contentH, View.MeasureSpec.EXACTLY))
                        wv.layout(0, 0, widthPx, contentH)
                        val pageW = if (letter) 612 else 595; val pageH = if (letter) 792 else 842
                        val margin = 24f
                        val scale = (pageW - 2 * margin) / widthPx
                        val sliceH = (pageH - 2 * margin) / scale
                        val pages = ceil(contentH / sliceH).toInt().coerceAtLeast(1)
                        val pdf = PdfDocument()
                        for (i in 0 until pages) {
                            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create())
                            val c = page.canvas
                            c.translate(margin, margin)
                            c.clipRect(0f, 0f, pageW - 2 * margin, pageH - 2 * margin)
                            c.scale(scale, scale)
                            c.translate(0f, -i * sliceH)
                            wv.draw(c)
                            pdf.finishPage(page)
                        }
                        out.outputStream().use { pdf.writeTo(it) }
                        pdf.close()
                        wv.destroy()
                        if (cont.isActive) cont.resume(Unit)
                    } catch (e: Throwable) {
                        wv.destroy()
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }, 1500)
            }
        }
        if (htmlFile != null) wv.loadUrl("file://${htmlFile.absolutePath}")
        else {
            var u = url!!.trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            wv.loadUrl(u)
        }
        cont.invokeOnCancellation { handler.post { if (!finished) { finished = true; wv.destroy() } } }
    }
}

package com.pdfmaster.app

import android.app.Application
import android.webkit.WebView
import com.pdfmaster.app.engine.AppCtx
import com.pdfmaster.app.engine.Work
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PdfMasterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCtx.app = this
        PDFBoxResourceLoader.init(this)
        runCatching { WebView.enableSlowWholeDocumentDraw() }
        Work.clearAll(this)
    }
}

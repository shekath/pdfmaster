package com.pdfmaster.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfmaster.app.engine.EditItem
import com.pdfmaster.app.engine.Ocr
import com.pdfmaster.app.engine.PdfOps
import com.pdfmaster.app.engine.PdfPasswordException
import com.pdfmaster.app.engine.Work
import com.pdfmaster.app.tools.Input
import com.pdfmaster.app.tools.RunResult
import com.pdfmaster.app.tools.Tool
import com.pdfmaster.app.tools.ToolRunner
import com.pdfmaster.app.tools.Tools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

sealed interface Screen {
    data object Home : Screen
    data class Form(val tool: Tool) : Screen
    data class Editor(val tool: Tool, val file: File) : Screen
    data class Organize(val file: File) : Screen
    data class Done(val tool: Tool, val result: RunResult) : Screen
}

data class Picked(val file: File, val pages: Int?, val locked: Boolean) {
    val name: String get() = file.name
    val size: Long get() = file.length()
}

class MainVm(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()

    val files = mutableStateListOf<Picked>()
    val options = mutableStateMapOf<String, String>()

    var importing by mutableStateOf(false); private set
    var running by mutableStateOf(false); private set
    var progress by mutableStateOf(-1f); private set
    var progressText by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null)

    /** Files handed to the app by other apps, or carried over from a previous result. */
    val carried = mutableStateListOf<File>()

    private var job: Job? = null

    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    /** "system", "light" or "dark". */
    var themeMode by mutableStateOf(prefs.getString("theme", "system") ?: "system"); private set
    fun setTheme(mode: String) { themeMode = mode; prefs.edit().putString("theme", mode).apply() }

    fun open(tool: Tool) {
        files.clear(); options.clear(); error = null
        tool.options.forEach { options[it.key] = it.defaultValue() }
        stack += Screen.Form(tool)
        val usable = carried.filter { accepts(tool.input, it) }
        if (usable.isNotEmpty()) {
            val take = if (tool.input.multiple) usable else usable.take(1)
            carried.clear()
            addFiles(take)
        }
    }

    fun back(): Boolean {
        if (running) { cancel(); return true }
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        return true
    }

    fun home() { while (stack.size > 1) stack.removeAt(stack.size - 1) }

    private fun accepts(input: Input, f: File): Boolean {
        val ext = f.extension.lowercase()
        return when (input) {
            Input.PDF, Input.PDFS, Input.TWO_PDFS -> ext == "pdf"
            Input.IMAGES -> ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif")
            Input.WORD -> ext in setOf("docx", "txt", "rtf", "md")
            Input.POWERPOINT -> ext in setOf("pptx", "ppsx")
            Input.EXCEL -> ext in setOf("xlsx", "csv", "tsv")
            Input.HTML -> ext in setOf("html", "htm")
            Input.NONE -> false
        }
    }

    fun receiveShared(uris: List<Uri>) {
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { Work.importUri(ctx, it) }.getOrNull() } }
            carried.clear(); carried += imported
            home()
        }
    }

    fun clearCarried() = carried.clear()

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            importing = true
            try {
                val imported = withContext(Dispatchers.IO) { uris.map { Work.importUri(ctx, it) } }
                addFiles(imported)
            } catch (e: Exception) {
                error = "Couldn't open the file: ${e.message}"
            } finally { importing = false }
        }
    }

    private fun addFiles(list: List<File>) {
        val tool = (screen as? Screen.Form)?.tool
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) {
                list.map { f ->
                    if (f.extension.equals("pdf", true)) {
                        val locked = runCatching { PdfOps.needsPassword(f) }.getOrDefault(false)
                        val pages = if (locked) null else runCatching { PdfOps.pageCount(f) }.getOrNull()
                        Picked(f, pages, locked)
                    } else Picked(f, null, false)
                }
            }
            if (tool != null && !tool.input.multiple) files.clear()
            files += picked
            if (tool?.input == Input.TWO_PDFS && files.size > 2) files.removeRange(0, files.size - 2)
        }
    }

    fun setImageOption(key: String, uri: Uri) {
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { Work.importUri(ctx, uri) }.getOrNull() }
            if (f != null) options[key] = f.absolutePath
        }
    }

    fun remove(p: Picked) { files.remove(p) }
    fun move(from: Int, to: Int) {
        if (to !in files.indices || from !in files.indices) return
        val item = files.removeAt(from); files.add(to, item)
    }

    fun cancel() { job?.cancel(); running = false }

    private fun launchJob(tool: Tool, block: suspend (progress: (Float, String) -> Unit) -> RunResult) {
        if (running) return
        error = null; running = true; progress = -1f; progressText = "Working…"
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val myCtx = coroutineContext
                    block { f, s -> myCtx.ensureActive(); progress = f; progressText = s }
                }
                stack += Screen.Done(tool, result)
            } catch (e: CancellationException) {
                // user cancelled
            } catch (e: PdfPasswordException) {
                error = e.message
            } catch (e: OutOfMemoryError) {
                error = "This file is too large to process on this device."
            } catch (e: Exception) {
                error = e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong (${e.javaClass.simpleName})."
            } finally {
                running = false
            }
        }
    }

    fun run(tool: Tool) {
        val inputs = files.map { it.file }
        if (tool.input != Input.HTML && tool.input != Input.NONE) {
            if (inputs.size < tool.input.min) { error = "Select ${if (tool.input.min > 1) "at least ${tool.input.min} " else "a "}${tool.input.noun}"; return }
            if (tool.id != "unlock" && files.any { it.locked }) { error = "\"${files.first { it.locked }.name}\" is password protected. Unlock it first."; return }
        }
        val opts = options.toMap()
        launchJob(tool) { p -> ToolRunner.run(ctx, tool, inputs, opts, p) }
    }

    fun openEditor(tool: Tool) {
        val f = files.firstOrNull() ?: run { error = "Select a PDF file"; return }
        if (f.locked) { error = "This PDF is password protected. Unlock it first."; return }
        error = null
        stack += if (tool.id == "organize") Screen.Organize(f.file) else Screen.Editor(tool, f.file)
    }

    fun runEditor(tool: Tool, file: File, items: List<EditItem>, cropAll: Boolean) =
        launchJob(tool) { p -> ToolRunner.runEditor(ctx, tool, file, items, cropAll, p) }

    fun runOrganize(sources: List<File>, refs: List<PdfOps.PageRef>) =
        launchJob(Tools.byId("organize")) { p ->
            p(-1f, "Saving…")
            val out = Work.outFile(ctx, "${Work.baseName(sources.first())}_organized", "pdf")
            PdfOps.organize(sources, refs, out)
            RunResult(listOf(out), "Saved ${refs.size} page(s) in the new order.")
        }

    fun onScanned(uri: Uri, ocr: Boolean) =
        launchJob(Tools.byId("scan")) { p ->
            p(-1f, "Saving scan…")
            val scanned = Work.importUri(ctx, uri)
            val out = Work.outFile(ctx, "scan_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date()), "pdf")
            if (ocr) Ocr.run(scanned, out, null) { f -> p(f, "Recognising text…") } else scanned.copyTo(out, true)
            RunResult(listOf(out), "Scanned ${PdfOps.pageCount(out)} page(s)${if (ocr) " with searchable text" else ""}.")
        }

    suspend fun findText(file: File, term: String): List<EditItem.Box> = withContext(Dispatchers.IO) { PdfOps.findText(file, term) }

    fun importSync(uri: Uri): File? = runCatching { Work.importUri(ctx, uri) }.getOrNull()

    /** Use a result file as the input for another tool. */
    fun continueWith(file: File) { carried.clear(); carried += file; home() }
}

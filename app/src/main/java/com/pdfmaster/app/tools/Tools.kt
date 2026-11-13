package com.pdfmaster.app.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.pdfmaster.app.engine.OcrLangs
import com.pdfmaster.app.engine.Translator

enum class Category(val title: String, val tint: Color, val icon: ImageVector) {
    ORGANIZE("Organize", Color(0xFF4F6BED), Icons.Outlined.FolderOpen),
    OPTIMIZE("Optimize", Color(0xFF1E9E6A), Icons.Outlined.Speed),
    TO_PDF("Convert to PDF", Color(0xFFE08A1E), Icons.Outlined.PictureAsPdf),
    FROM_PDF("Convert from PDF", Color(0xFF1A9BB0), Icons.Outlined.SwapHoriz),
    EDIT("Edit", Color(0xFF8B5CF6), Icons.Outlined.Edit),
    SECURITY("Security", Color(0xFFE0475B), Icons.Outlined.Security),
    SMART("Smart tools", Color(0xFF2E7CF6), Icons.Outlined.AutoAwesome),
}

/** What a tool takes as input. */
enum class Input(val mimes: Array<String>, val multiple: Boolean, val min: Int, val noun: String) {
    PDF(arrayOf("application/pdf"), false, 1, "PDF file"),
    PDFS(arrayOf("application/pdf"), true, 2, "PDF files"),
    TWO_PDFS(arrayOf("application/pdf"), true, 2, "two PDF files"),
    IMAGES(arrayOf("image/*"), true, 1, "images"),
    WORD(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword", "text/plain", "application/rtf", "text/rtf"), true, 1, "Word documents"),
    POWERPOINT(arrayOf("application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-powerpoint"), true, 1, "PowerPoint files"),
    EXCEL(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/csv", "text/comma-separated-values", "text/tab-separated-values"), true, 1, "Excel or CSV files"),
    HTML(arrayOf("text/html"), false, 0, "web page"),
    NONE(arrayOf(), false, 0, ""),
}

enum class Mode { FORM, EDITOR, ORGANIZE, SCAN }

sealed class Opt(val key: String, val label: String, val visibleIf: ((Map<String, String>) -> Boolean)?) {
    class Choice(key: String, label: String, val choices: List<Pair<String, String>>, val default: String, visibleIf: ((Map<String, String>) -> Boolean)? = null) : Opt(key, label, visibleIf)
    class Dropdown(key: String, label: String, val choices: () -> List<Pair<String, String>>, val default: String, visibleIf: ((Map<String, String>) -> Boolean)? = null) : Opt(key, label, visibleIf)
    class Text(key: String, label: String, val hint: String = "", val default: String = "", val password: Boolean = false, val number: Boolean = false, visibleIf: ((Map<String, String>) -> Boolean)? = null) : Opt(key, label, visibleIf)
    class Toggle(key: String, label: String, val default: Boolean, visibleIf: ((Map<String, String>) -> Boolean)? = null) : Opt(key, label, visibleIf)
    class Slider(key: String, label: String, val min: Float, val max: Float, val default: Float, val suffix: String = "", visibleIf: ((Map<String, String>) -> Boolean)? = null) : Opt(key, label, visibleIf)
    class ImageFile(key: String, label: String, visibleIf: ((Map<String, String>) -> Boolean)? = null) : Opt(key, label, visibleIf)

    fun defaultValue(): String = when (this) {
        is Choice -> default; is Dropdown -> default; is Text -> default
        is Toggle -> default.toString(); is Slider -> default.toString(); is ImageFile -> ""
    }
}

data class Tool(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val category: Category,
    val input: Input,
    val action: String,
    val mode: Mode = Mode.FORM,
    val options: List<Opt> = emptyList(),
    val reorder: Boolean = false,
)

private val pagesHint = "e.g. 1-3, 5, 8-"

object Tools {
    val all: List<Tool> = listOf(
        // Organize
        Tool("merge", "Merge PDF", "Combine PDFs in the order you want", Icons.Outlined.CallMerge, Category.ORGANIZE, Input.PDFS, "Merge PDFs", reorder = true),
        Tool("split", "Split PDF", "Separate pages into independent files", Icons.Outlined.CallSplit, Category.ORGANIZE, Input.PDF, "Split PDF", options = listOf(
            Opt.Choice("mode", "Split by", listOf("ranges" to "Custom ranges", "every" to "Every page", "fixed" to "Every N pages"), "ranges"),
            Opt.Text("ranges", "Ranges (each becomes a file)", pagesHint, "1-2", visibleIf = { it["mode"] == "ranges" }),
            Opt.Text("n", "Pages per file", "2", "2", number = true, visibleIf = { it["mode"] == "fixed" }),
        )),
        Tool("remove", "Remove pages", "Delete the pages you don't need", Icons.Outlined.DeleteSweep, Category.ORGANIZE, Input.PDF, "Remove pages", options = listOf(
            Opt.Text("pages", "Pages to remove", pagesHint),
        )),
        Tool("extract", "Extract pages", "Pull selected pages into a new PDF", Icons.Outlined.FileCopy, Category.ORGANIZE, Input.PDF, "Extract pages", options = listOf(
            Opt.Text("pages", "Pages to extract", pagesHint),
            Opt.Choice("output", "Save as", listOf("one" to "One PDF", "separate" to "One PDF per page"), "one"),
        )),
        Tool("organize", "Organize PDF", "Reorder, rotate, delete or add pages", Icons.Outlined.GridView, Category.ORGANIZE, Input.PDF, "Open organizer", Mode.ORGANIZE),
        Tool("scan", "Scan to PDF", "Capture paper documents with your camera", Icons.Outlined.DocumentScanner, Category.ORGANIZE, Input.NONE, "Start scanning", Mode.SCAN, options = listOf(
            Opt.Toggle("ocr", "Make text searchable (OCR)", false),
            Opt.Dropdown("lang", "Document language", { OcrLangs.choices }, "latin", visibleIf = { it["ocr"] == "true" }),
        )),

        // Optimize
        Tool("compress", "Compress PDF", "Reduce file size, keep good quality", Icons.Outlined.Compress, Category.OPTIMIZE, Input.PDF, "Compress PDF", options = listOf(
            Opt.Choice("level", "Compression", listOf("extreme" to "Extreme", "recommended" to "Recommended", "low" to "Light"), "recommended"),
        )),
        Tool("repair", "Repair PDF", "Recover data from a damaged PDF", Icons.Outlined.Healing, Category.OPTIMIZE, Input.PDF, "Repair PDF"),
        Tool("ocr", "OCR PDF", "Searchable text from scans · English, Hindi & 12 Indian languages", Icons.Outlined.TextFields, Category.OPTIMIZE, Input.PDF, "Run OCR", options = listOf(
            Opt.Dropdown("lang", "Document language", { OcrLangs.choices }, "latin"),
            Opt.Choice("output", "Output", listOf("pdf" to "Searchable PDF", "txt" to "Text file", "both" to "Both"), "pdf"),
        )),

        // Convert to PDF
        Tool("jpg2pdf", "JPG to PDF", "Turn photos and images into a PDF", Icons.Outlined.Image, Category.TO_PDF, Input.IMAGES, "Convert to PDF", reorder = true, options = listOf(
            Opt.Choice("size", "Page size", listOf("fit" to "Fit image", "a4" to "A4", "letter" to "US Letter"), "a4"),
            Opt.Choice("orientation", "Orientation", listOf("auto" to "Auto", "portrait" to "Portrait", "landscape" to "Landscape"), "auto", visibleIf = { it["size"] != "fit" }),
            Opt.Choice("margin", "Margin", listOf("0" to "None", "18" to "Small", "40" to "Big"), "0"),
            Opt.Toggle("single", "Combine into one PDF", true),
        )),
        Tool("word2pdf", "Word to PDF", "Convert DOCX and text documents to PDF", Icons.Outlined.Description, Category.TO_PDF, Input.WORD, "Convert to PDF"),
        Tool("ppt2pdf", "PowerPoint to PDF", "Convert PPTX slideshows to PDF", Icons.Outlined.Slideshow, Category.TO_PDF, Input.POWERPOINT, "Convert to PDF"),
        Tool("excel2pdf", "Excel to PDF", "Convert XLSX and CSV spreadsheets to PDF", Icons.Outlined.TableChart, Category.TO_PDF, Input.EXCEL, "Convert to PDF"),
        Tool("html2pdf", "HTML to PDF", "Save any web page as a PDF", Icons.Outlined.Language, Category.TO_PDF, Input.HTML, "Convert to PDF", options = listOf(
            Opt.Text("url", "Web address", "https://example.com"),
            Opt.Choice("layout", "Layout", listOf("mobile" to "Mobile", "desktop" to "Desktop"), "desktop"),
            Opt.Choice("paper", "Page size", listOf("a4" to "A4", "letter" to "US Letter"), "a4"),
        )),

        // Convert from PDF
        Tool("pdf2jpg", "PDF to JPG", "Save pages or embedded images as pictures", Icons.Outlined.PhotoLibrary, Category.FROM_PDF, Input.PDF, "Convert to images", options = listOf(
            Opt.Choice("mode", "Convert", listOf("pages" to "Every page", "extract" to "Extract images"), "pages"),
            Opt.Choice("quality", "Quality", listOf("150" to "Normal", "300" to "High"), "150", visibleIf = { it["mode"] == "pages" }),
            Opt.Choice("format", "Format", listOf("jpg" to "JPG", "png" to "PNG"), "jpg"),
        )),
        Tool("pdf2word", "PDF to Word", "Convert to an editable DOCX document", Icons.Outlined.Article, Category.FROM_PDF, Input.PDF, "Convert to Word", options = listOf(
            Opt.Choice("mode", "Result", listOf("text" to "Editable text", "layout" to "Exact look"), "text"),
        )),
        Tool("pdf2ppt", "PDF to PowerPoint", "Turn pages into PPTX slides", Icons.Outlined.CoPresent, Category.FROM_PDF, Input.PDF, "Convert to PowerPoint", options = listOf(
            Opt.Choice("mode", "Result", listOf("images" to "Exact look", "text" to "Editable text"), "images"),
        )),
        Tool("pdf2excel", "PDF to Excel", "Pull tables and data into XLSX", Icons.Outlined.GridOn, Category.FROM_PDF, Input.PDF, "Convert to Excel", options = listOf(
            Opt.Choice("sheets", "Sheets", listOf("page" to "One per page", "single" to "All in one"), "page"),
        )),
        Tool("pdfa", "PDF to PDF/A", "Archive-ready PDF for long-term storage", Icons.Outlined.Inventory2, Category.FROM_PDF, Input.PDF, "Convert to PDF/A", options = listOf(
            Opt.Choice("part", "Standard", listOf("2" to "PDF/A-2b", "1" to "PDF/A-1b"), "2"),
            Opt.Toggle("flatten", "Flatten pages (most compatible, text not selectable)", false),
        )),

        // Edit
        Tool("rotate", "Rotate PDF", "Rotate all or selected pages", Icons.Outlined.RotateRight, Category.EDIT, Input.PDF, "Rotate PDF", options = listOf(
            Opt.Choice("angle", "Rotate", listOf("90" to "90° right", "180" to "180°", "270" to "90° left"), "90"),
            Opt.Text("pages", "Pages (leave empty for all)", pagesHint),
        )),
        Tool("numbers", "Page numbers", "Add page numbers in any position", Icons.Outlined.FormatListNumbered, Category.EDIT, Input.PDF, "Add numbers", options = listOf(
            Opt.Choice("position", "Position", listOf("bl" to "Bottom left", "bc" to "Bottom center", "br" to "Bottom right", "tl" to "Top left", "tc" to "Top center", "tr" to "Top right"), "bc"),
            Opt.Choice("format", "Format", listOf("n" to "1", "page_n" to "Page 1", "n_of_t" to "1 / 10", "page_n_of_t" to "Page 1 of 10"), "n"),
            Opt.Text("start", "First number", "1", "1", number = true),
            Opt.Slider("size", "Text size", 7f, 24f, 11f, " pt"),
            Opt.Toggle("skipFirst", "Skip the first page (cover)", false),
        )),
        Tool("watermark", "Add watermark", "Stamp text or an image over pages", Icons.Outlined.BrandingWatermark, Category.EDIT, Input.PDF, "Add watermark", options = listOf(
            Opt.Choice("type", "Watermark", listOf("text" to "Text", "image" to "Image"), "text"),
            Opt.Text("text", "Text", "CONFIDENTIAL", "CONFIDENTIAL", visibleIf = { it["type"] == "text" }),
            Opt.ImageFile("image", "Image", visibleIf = { it["type"] == "image" }),
            Opt.Choice("color", "Color", listOf("888888" to "Gray", "D32F2F" to "Red", "1E4FD8" to "Blue", "111111" to "Black"), "888888", visibleIf = { it["type"] == "text" }),
            Opt.Slider("size", "Text size", 12f, 120f, 54f, " pt", visibleIf = { it["type"] == "text" }),
            Opt.Slider("scale", "Image width", 10f, 100f, 50f, "%", visibleIf = { it["type"] == "image" }),
            Opt.Slider("opacity", "Opacity", 5f, 100f, 25f, "%"),
            Opt.Choice("angle", "Angle", listOf("45" to "Diagonal", "0" to "Horizontal"), "45", visibleIf = { it["type"] == "text" }),
            Opt.Choice("layout", "Layout", listOf("center" to "Center", "tile" to "Tiled"), "center"),
            Opt.Text("pages", "Pages (leave empty for all)", pagesHint),
        )),
        Tool("crop", "Crop PDF", "Trim margins or select an area", Icons.Outlined.Crop, Category.EDIT, Input.PDF, "Open crop tool", Mode.EDITOR),
        Tool("edit", "Edit PDF", "Edit existing text (OCR for scans), add text, drawings and images", Icons.Outlined.EditNote, Category.EDIT, Input.PDF, "Open editor", Mode.EDITOR),

        // Security
        Tool("sign", "Sign PDF", "Draw or type your signature and place it", Icons.Outlined.Draw, Category.SECURITY, Input.PDF, "Open signer", Mode.EDITOR),
        Tool("redact", "Redact PDF", "Permanently black out sensitive content", Icons.Outlined.VisibilityOff, Category.SECURITY, Input.PDF, "Open redactor", Mode.EDITOR),
        Tool("unlock", "Unlock PDF", "Remove a PDF password", Icons.Outlined.LockOpen, Category.SECURITY, Input.PDF, "Unlock PDF", options = listOf(
            Opt.Text("password", "Current password", "", password = true),
        )),
        Tool("protect", "Protect PDF", "Encrypt with a password (AES-256)", Icons.Outlined.Lock, Category.SECURITY, Input.PDF, "Protect PDF", options = listOf(
            Opt.Text("password", "Password", "", password = true),
            Opt.Text("confirm", "Repeat password", "", password = true),
            Opt.Toggle("print", "Allow printing", true),
            Opt.Toggle("copy", "Allow copying text", true),
            Opt.Toggle("modify", "Allow editing", false),
        )),
        Tool("compare", "Compare PDF", "See what changed between two versions", Icons.Outlined.Compare, Category.SECURITY, Input.TWO_PDFS, "Compare"),

        // Smart
        Tool("summarize", "Summarize PDF", "Get the key points of a document", Icons.Outlined.AutoAwesome, Category.SMART, Input.PDF, "Summarize", options = listOf(
            Opt.Choice("length", "Length", listOf("short" to "Short", "medium" to "Medium", "long" to "Detailed"), "medium"),
        )),
        Tool("translate", "Translate PDF", "Translate documents on your device", Icons.Outlined.Translate, Category.SMART, Input.PDF, "Translate", options = listOf(
            Opt.Dropdown("source", "From", { listOf("auto" to "Detect language") + Translator.languages }, "auto"),
            Opt.Dropdown("target", "To", { Translator.languages }, "en"),
        )),
    )

    fun byId(id: String) = all.first { it.id == id }
}

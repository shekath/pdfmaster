package com.pdfmaster.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pdfmaster.app.engine.DetectedLine
import com.pdfmaster.app.engine.EditItem
import com.pdfmaster.app.engine.TextEdit
import com.pdfmaster.app.engine.Render
import com.pdfmaster.app.engine.Work
import com.pdfmaster.app.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class EMode(val label: String) { EDIT_TEXT("Edit text"), MOVE("Move"), TEXT("Text"), DRAW("Draw"), HIGHLIGHT("Highlight"), WHITEOUT("White-out"), BOX("Box"), IMAGE("Image"), SIGN("Signature"), REDACT("Redact"), CROP("Crop") }

private val palette = listOf(0xFF111111.toInt(), 0xFF1E4FD8.toInt(), 0xFFD32F2F.toInt(), 0xFF1E9E6A.toInt(), 0xFFF5B400.toInt(), 0xFFFFFFFF.toInt())

@Composable
fun EditorScreen(vm: MainVm, tool: Tool, file: File) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc = remember(file) { runCatching { Render.Doc(file) }.getOrNull() }
    DisposableEffect(doc) { onDispose { doc?.close() } }
    if (doc == null) {
        Scaffold(topBar = { Bar(tool.title, { vm.back() }) }) { p -> Box(Modifier.padding(p).padding(16.dp)) { ErrorNote("This PDF couldn't be opened.") } }
        return
    }
    val modes = when (tool.id) {
        "sign" -> listOf(EMode.SIGN, EMode.TEXT, EMode.MOVE)
        "redact" -> listOf(EMode.REDACT)
        "crop" -> listOf(EMode.CROP)
        else -> listOf(EMode.EDIT_TEXT, EMode.TEXT, EMode.DRAW, EMode.HIGHLIGHT, EMode.WHITEOUT, EMode.BOX, EMode.IMAGE, EMode.MOVE)
    }
    var page by remember { mutableIntStateOf(0) }
    val items = remember { mutableStateListOf<EditItem>() }
    var mode by remember { mutableStateOf(modes.first()) }
    var color by remember { mutableIntStateOf(if (tool.id == "sign") 0xFF1E3A8A.toInt() else palette[0]) }
    var textSize by remember { mutableFloatStateOf(0.03f) }
    var stroke by remember { mutableFloatStateOf(0.004f) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var cropAll by remember { mutableStateOf(true) }
    var textDialog by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var signDialog by remember { mutableStateOf(false) }
    var searchDialog by remember { mutableStateOf(false) }
    var signature by remember { mutableStateOf<File?>(null) }
    var pendingImage by remember { mutableStateOf<File?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val bitmaps = remember { mutableStateMapOf<File, ImageBitmap>() }
    // ---- editable existing text (text layer or OCR) ----
    val detected = remember { mutableStateMapOf<Int, List<DetectedLine>>() }
    var pdfLines by remember { mutableStateOf<Map<Int, List<DetectedLine>>?>(null) }
    var detecting by remember { mutableStateOf(false) }
    var replaceTarget by remember { mutableStateOf<Pair<DetectedLine, Int?>?>(null) }
    var forceOcr by remember { mutableIntStateOf(0) }
    fun bmpFor(f: File): ImageBitmap? = bitmaps[f] ?: BitmapFactory.decodeFile(f.path)?.asImageBitmap()?.also { bitmaps[f] = it }

    val pageBmp by produceState<Bitmap?>(null, page) { value = null; value = withContext(Dispatchers.IO) { runCatching { doc.render(page, 1400) }.getOrNull() } }
    val pageSize = remember(page) { doc.pageSize(page) }
    val aspect = pageSize.height / pageSize.width

    LaunchedEffect(mode, page, forceOcr) {
        if (mode != EMode.EDIT_TEXT) return@LaunchedEffect
        val wantOcr = forceOcr > 0 && detected[page]?.firstOrNull()?.fromOcr != true
        if (detected.containsKey(page) && !wantOcr) return@LaunchedEffect
        detecting = true
        try {
            val layer = pdfLines ?: withContext(Dispatchers.IO) { runCatching { TextEdit.pdfLines(file) }.getOrDefault(emptyMap()) }.also { pdfLines = it }
            val fromLayer = layer[page].orEmpty()
            if (fromLayer.isNotEmpty() && !wantOcr) {
                detected[page] = fromLayer
                info = "Tap any line to edit it"
            } else {
                info = "Reading the page with OCR…"
                val lines = withContext(Dispatchers.Default) {
                    runCatching {
                        val size = doc.pageSize(page)
                        val bmp = withContext(Dispatchers.IO) { doc.render(page, (size.width * 3f).toInt().coerceIn(1400, 2600)) }
                        TextEdit.ocrLines(page, bmp)
                    }.getOrElse { emptyList() }
                }
                detected[page] = lines
                info = if (lines.isEmpty()) "No text found on this page" else "Found ${lines.size} lines with OCR. Tap one to edit it"
            }
        } finally { detecting = false; forceOcr = 0 }
    }

    fun openReplace(nx: Float, ny: Float): Boolean {
        // re-edit an existing replacement first
        val existing = items.indexOfLast { it is EditItem.Replace && it.page == page && nx in it.l..maxOf(it.r, it.l + 0.02f) && ny in it.t..it.b }
        if (existing >= 0) {
            val it = items[existing] as EditItem.Replace
            replaceTarget = DetectedLine(page, it.l, it.t, it.r, it.b, it.text, it.size, it.bold, it.ocr) to existing
            return true
        }
        val pad = 0.006f
        val line = detected[page].orEmpty().filter { nx in (it.l - pad)..(it.r + pad) && ny in (it.t - pad)..(it.b + pad) }
            .minByOrNull { (it.b - it.t) * (it.r - it.l) } ?: return false
        replaceTarget = line to null
        return true
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val f = withContext(Dispatchers.IO) { vm.importSync(uri) }
            if (f != null) { pendingImage = f; mode = EMode.IMAGE; info = "Tap the page to place the image" }
        }
    }

    fun place(f: File, x: Float, y: Float, widthFrac: Float) {
        val b = bmpFor(f) ?: return
        val w = widthFrac
        val h = w * b.height / b.width / aspect
        items += EditItem.Picture(page, (x - w / 2).coerceIn(0f, 1f - w), (y - h / 2).coerceIn(0f, max(0f, 1f - h)), w, h, f)
        selected = items.lastIndex
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            Bar(tool.title, { vm.back() }, actions = {
                IconButton(onClick = { if (items.isNotEmpty()) { items.removeAt(items.lastIndex); selected = null } }, enabled = items.isNotEmpty()) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
                TextButton(onClick = { vm.runEditor(tool, file, items.toList(), cropAll) }, enabled = !vm.running) { Text("Save", style = MaterialTheme.typography.titleSmall) }
            })
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp)) {
                    vm.error?.let { Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { ErrorNote(it) } }
                    if (vm.running) Box(Modifier.padding(12.dp)) { WorkingPanel(vm.progressText, vm.progress) { vm.cancel() } }
                    // page navigation
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        IconButton(onClick = { page--; selected = null }, enabled = page > 0) { Icon(Icons.Outlined.ChevronLeft, "Previous page") }
                        Text("Page ${page + 1} of ${doc.pageCount}", style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { page++; selected = null }, enabled = page < doc.pageCount - 1) { Icon(Icons.Outlined.ChevronRight, "Next page") }
                        Spacer(Modifier.weight(1f))
                        val sel = selected?.let { items.getOrNull(it) }
                        if (sel != null) IconButton(onClick = { items.removeAt(selected!!); selected = null }) { Icon(Icons.Outlined.Delete, "Delete selected") }
                        if (tool.id == "crop") { Text("All pages", style = MaterialTheme.typography.bodySmall); Switch(checked = cropAll, onCheckedChange = { cropAll = it }, modifier = Modifier.padding(start = 6.dp)) }
                        if (mode == EMode.EDIT_TEXT) TextButton(onClick = { forceOcr++ }, enabled = !detecting) { Icon(Icons.Outlined.DocumentScanner, null, Modifier.size(18.dp)); HSpace(6.dp); Text(if (detecting) "Reading…" else "OCR page") }
                        if (tool.id == "redact") TextButton(onClick = { searchDialog = true }) { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)); HSpace(6.dp); Text("Find text") }
                    }
                    if (modes.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        modes.forEach { m ->
                            FilterChip(selected = mode == m, onClick = {
                                mode = m
                                when (m) {
                                    EMode.IMAGE -> imagePicker.launch("image/*")
                                    EMode.SIGN -> if (signature == null) signDialog = true else info = "Tap the page to place your signature"
                                    else -> {}
                                }
                            }, label = { Text(m.label) }, leadingIcon = { Icon(iconFor(m), null, Modifier.size(18.dp)) })
                        }
                        if (tool.id == "sign") {
                            FilterChip(selected = false, onClick = { signDialog = true }, label = { Text("New signature") }, leadingIcon = { Icon(Icons.Outlined.Draw, null, Modifier.size(18.dp)) })
                            FilterChip(selected = false, onClick = {
                                items += EditItem.Text(page, 0.1f, 0.85f, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date()), 0.025f, color); selected = items.lastIndex
                            }, label = { Text("Date") }, leadingIcon = { Icon(Icons.Outlined.Event, null, Modifier.size(18.dp)) })
                        }
                    }
                    if (mode in setOf(EMode.TEXT, EMode.DRAW, EMode.BOX, EMode.SIGN, EMode.MOVE) && tool.id != "redact" || (mode == EMode.EDIT_TEXT && selected?.let { items.getOrNull(it) } is EditItem.Replace)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            palette.forEach { c ->
                                Box(Modifier.padding(end = 8.dp).size(26.dp).background(Color(c), CircleShape)
                                    .border(if (color == c) 3.dp else 1.dp, if (color == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                                    .clickable {
                                        color = c
                                        selected?.let { i -> items.getOrNull(i)?.let { it2 -> recolor(it2, c)?.let { r -> items[i] = r } } }
                                    })
                            }
                            Slider(
                                value = when (mode) { EMode.DRAW -> stroke * 1000; else -> textSize * 1000 },
                                onValueChange = { v ->
                                    if (mode == EMode.DRAW) stroke = v / 1000 else textSize = v / 1000
                                    selected?.let { i -> items.getOrNull(i)?.let { it2 -> resize(it2, v / 1000, aspect)?.let { r -> items[i] = r } } }
                                },
                                valueRange = if (mode == EMode.DRAW) 1f..20f else 10f..120f,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    ) { pad ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad).padding(12.dp), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            var w = maxWidth; var h = w * aspect
            if (h > maxHeight) { h = maxHeight; w = h / aspect }
            val wPx = with(density) { w.toPx() }; val hPx = with(density) { h.toPx() }
            Box(Modifier.size(w, h).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                pageBmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
                var dragStart by remember { mutableStateOf<Offset?>(null) }
                var dragNow by remember { mutableStateOf<Offset?>(null) }
                val ink = remember { mutableStateListOf<Pair<Float, Float>>() }
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(mode, page, signature, pendingImage) {
                            detectTapGestures { off ->
                                val nx = off.x / size.width; val ny = off.y / size.height
                                if (mode == EMode.EDIT_TEXT) {
                                    if (!openReplace(nx, ny)) info = if (detecting) "Still reading the page…" else "No text there. Tap a highlighted line"
                                    return@detectTapGestures
                                }
                                val hit = hitTest(items, page, nx, ny, aspect)
                                if (hit != null && mode != EMode.REDACT && mode != EMode.CROP) { selected = hit; return@detectTapGestures }
                                selected = null
                                when (mode) {
                                    EMode.TEXT -> textDialog = nx to ny
                                    EMode.SIGN -> signature?.let { place(it, nx, ny, 0.3f) } ?: run { signDialog = true }
                                    EMode.IMAGE -> pendingImage?.let { place(it, nx, ny, 0.4f); pendingImage = null; info = null } ?: imagePicker.launch("image/*")
                                    else -> {}
                                }
                            }
                        }
                        .pointerInput(mode, page) {
                            detectDragGestures(
                                onDragStart = { off ->
                                    val nx = off.x / size.width; val ny = off.y / size.height
                                    val hit = if (mode in setOf(EMode.MOVE, EMode.TEXT, EMode.SIGN, EMode.IMAGE)) hitTest(items, page, nx, ny, aspect) else null
                                    if (hit != null) { selected = hit; dragStart = null }
                                    else if (mode == EMode.MOVE) selected = null
                                    else { selected = null; dragStart = off; dragNow = off; ink.clear(); if (mode == EMode.DRAW) ink += nx to ny }
                                },
                                onDragEnd = {
                                    val s = dragStart; val e = dragNow
                                    if (s != null && e != null) {
                                        val l = min(s.x, e.x) / size.width; val r = max(s.x, e.x) / size.width
                                        val t = min(s.y, e.y) / size.height; val b = max(s.y, e.y) / size.height
                                        val big = abs(r - l) > 0.01f && abs(b - t) > 0.005f
                                        when (mode) {
                                            EMode.DRAW -> if (ink.size > 1) items += EditItem.Ink(page, ink.toList(), stroke, color)
                                            EMode.HIGHLIGHT -> if (big) items += EditItem.Box(page, l, t, r, b, 0xFFFFE14D.toInt(), 0.4f)
                                            EMode.WHITEOUT -> if (big) items += EditItem.Box(page, l, t, r, b, 0xFFFFFFFF.toInt(), 1f)
                                            EMode.BOX -> if (big) items += EditItem.Box(page, l, t, r, b, color, 1f)
                                            EMode.REDACT -> if (big) items += EditItem.Box(page, l, t, r, b, 0xFF000000.toInt(), 1f)
                                            EMode.CROP -> if (big) { items.removeAll { it is EditItem.Box }; items += EditItem.Box(page, l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f), 0xFF2F5BEA.toInt(), 0.15f) }
                                            else -> {}
                                        }
                                    }
                                    dragStart = null; dragNow = null; ink.clear()
                                },
                                onDragCancel = { dragStart = null; dragNow = null; ink.clear() },
                                onDrag = { change, amount ->
                                    change.consume()
                                    val sel = selected
                                    if (dragStart == null && sel != null) {
                                        items.getOrNull(sel)?.let { items[sel] = moveBy(it, amount.x / size.width, amount.y / size.height) }
                                    } else if (dragStart != null) {
                                        dragNow = change.position
                                        if (mode == EMode.DRAW) ink += change.position.x / size.width to change.position.y / size.height
                                    }
                                }
                            )
                        }
                ) {
                    items.forEachIndexed { i, item -> if (item.page == page) drawItem(item, i == selected, ::bmpFor) }
                    if (mode == EMode.EDIT_TEXT) {
                        val done = items.filterIsInstance<EditItem.Replace>().filter { it.page == page }
                        detected[page].orEmpty().forEach { d ->
                            if (done.any { abs(it.l - d.l) < 0.002f && abs(it.t - d.t) < 0.002f }) return@forEach
                            drawRect(Color(0x142F5BEA), Offset(d.l * size.width, d.t * size.height), Size((d.r - d.l) * size.width, (d.b - d.t) * size.height))
                            drawRect(Color(0x992F5BEA), Offset(d.l * size.width, d.t * size.height), Size((d.r - d.l) * size.width, (d.b - d.t) * size.height), style = Stroke(1.2f))
                        }
                    }
                    val s = dragStart; val e = dragNow
                    if (mode == EMode.DRAW && ink.size > 1) {
                        val p = Path().apply { moveTo(ink[0].first * size.width, ink[0].second * size.height); ink.drop(1).forEach { lineTo(it.first * size.width, it.second * size.height) } }
                        drawPath(p, Color(color), style = Stroke(stroke * size.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    } else if (s != null && e != null) {
                        val tl = Offset(min(s.x, e.x), min(s.y, e.y)); val sz = Size(abs(e.x - s.x), abs(e.y - s.y))
                        val c = when (mode) { EMode.HIGHLIGHT -> Color(0x66FFE14D); EMode.WHITEOUT -> Color.White; EMode.REDACT -> Color.Black; EMode.CROP -> Color(0x262F5BEA); else -> Color(color) }
                        drawRect(c, tl, sz)
                        drawRect(Color(0xFF2F5BEA), tl, sz, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                    }
                    if (mode == EMode.CROP) items.filterIsInstance<EditItem.Box>().lastOrNull()?.let { b ->
                        val dim = Color(0x88000000)
                        drawRect(dim, Offset.Zero, Size(size.width, b.t * size.height))
                        drawRect(dim, Offset(0f, b.b * size.height), Size(size.width, size.height - b.b * size.height))
                        drawRect(dim, Offset(0f, b.t * size.height), Size(b.l * size.width, (b.b - b.t) * size.height))
                        drawRect(dim, Offset(b.r * size.width, b.t * size.height), Size(size.width - b.r * size.width, (b.b - b.t) * size.height))
                    }
                }
                if (pageBmp == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading page…", color = Color.Gray) }
            }
            info?.let {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface, modifier = Modifier.align(Alignment.TopCenter)) {
                    Text(it, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }

    textDialog?.let { (x, y) ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { textDialog = null },
            title = { Text("Add text") },
            text = { OutlinedTextField(text, { text = it }, placeholder = { Text("Type here") }, minLines = 2, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (text.isNotBlank()) { items += EditItem.Text(page, x, y, text, textSize, color); selected = items.lastIndex }; textDialog = null }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { textDialog = null }) { Text("Cancel") } },
        )
    }
    replaceTarget?.let { (line, index) ->
        var text by remember(line) { mutableStateOf(line.text) }
        var bold by remember(line) { mutableStateOf(line.bold) }
        AlertDialog(
            onDismissRequest = { replaceTarget = null },
            title = { Text("Edit text") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bold", modifier = Modifier.weight(1f)); Switch(checked = bold, onCheckedChange = { bold = it })
                    }
                    if (line.fromOcr) Text("Recognised with OCR: check the spelling before saving.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val bmp = pageBmp
                    val prev = index?.let { items.getOrNull(it) as? EditItem.Replace }
                    val (bg, fg) = prev?.let { it.bg to it.color } ?: (bmp?.let { TextEdit.sampleColors(it, line.l, line.t, line.r, line.b) } ?: (0xFFFFFFFF.toInt() to 0xFF000000.toInt()))
                    val item = EditItem.Replace(page, line.l, line.t, line.r, line.b, text.replace('\n', ' '), prev?.size ?: line.size, fg, bg, bold, line.fromOcr || prev?.ocr == true)
                    if (index != null) items[index] = item else items += item
                    selected = if (index != null) index else items.lastIndex
                    replaceTarget = null
                }) { Text("Apply") }
            },
            dismissButton = {
                Row {
                    if (index != null) TextButton(onClick = { items.removeAt(index); selected = null; replaceTarget = null }) { Text("Restore original") }
                    TextButton(onClick = { replaceTarget = null }) { Text("Cancel") }
                }
            },
        )
    }
    if (signDialog) SignatureDialog(color, onDismiss = { signDialog = false }) { bmp ->
        val f = Work.tmpFile(ctx, "png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        signature = f; signDialog = false; mode = EMode.SIGN
        place(f, 0.5f, 0.8f, 0.3f)
        info = "Drag to move it. Tap elsewhere to add it again."
    }
    if (searchDialog) {
        var term by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { searchDialog = false },
            title = { Text("Redact matching text") },
            text = { OutlinedTextField(term, { term = it }, singleLine = true, label = { Text("Word or phrase") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = !busy && term.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        val found = runCatching { vm.findText(file, term) }.getOrDefault(emptyList())
                        items += found
                        info = if (found.isEmpty()) "No matches for \"$term\"" else "Marked ${found.size} match(es) across ${found.map { it.page }.distinct().size} page(s)"
                        busy = false; searchDialog = false
                    }
                }) { Text(if (busy) "Searching…" else "Mark all") }
            },
            dismissButton = { TextButton(onClick = { searchDialog = false }) { Text("Cancel") } },
        )
    }
}

private fun iconFor(m: EMode) = when (m) {
    EMode.EDIT_TEXT -> Icons.Outlined.EditNote; EMode.MOVE -> Icons.Outlined.PanTool; EMode.TEXT -> Icons.Outlined.TextFields; EMode.DRAW -> Icons.Outlined.Brush
    EMode.HIGHLIGHT -> Icons.Outlined.Highlight; EMode.WHITEOUT -> Icons.Outlined.CropSquare; EMode.BOX -> Icons.Outlined.CropSquare
    EMode.IMAGE -> Icons.Outlined.AddPhotoAlternate; EMode.SIGN -> Icons.Outlined.Draw; EMode.REDACT -> Icons.Outlined.CropSquare; EMode.CROP -> Icons.Outlined.Crop
}

private fun textBounds(t: EditItem.Text, aspect: Float): FloatArray {
    val lines = t.text.split('\n')
    val w = lines.maxOf { it.length } * t.size * 0.55f
    val h = lines.size * t.size * 1.2f / aspect
    return floatArrayOf(t.x, t.y, t.x + w, t.y + h)
}

private fun hitTest(items: List<EditItem>, page: Int, x: Float, y: Float, aspect: Float): Int? {
    for (i in items.indices.reversed()) {
        val it = items[i]
        if (it.page != page) continue
        val b = when (it) {
            is EditItem.Text -> textBounds(it, aspect)
            is EditItem.Picture -> floatArrayOf(it.x, it.y, it.x + it.w, it.y + it.h)
            else -> continue
        }
        val pad = 0.015f
        if (x in (b[0] - pad)..(b[2] + pad) && y in (b[1] - pad)..(b[3] + pad)) return i
    }
    return null
}

private fun moveBy(it: EditItem, dx: Float, dy: Float): EditItem = when (it) {
    is EditItem.Text -> it.copy(x = (it.x + dx).coerceIn(-0.2f, 1f), y = (it.y + dy).coerceIn(-0.05f, 1f))
    is EditItem.Picture -> it.copy(x = (it.x + dx).coerceIn(-it.w / 2, 1f - it.w / 2), y = (it.y + dy).coerceIn(-it.h / 2, 1f - it.h / 2))
    else -> it
}

private fun recolor(it: EditItem, c: Int): EditItem? = when (it) {
    is EditItem.Text -> it.copy(color = c)
    is EditItem.Replace -> it.copy(color = c)
    is EditItem.Ink -> it.copy(color = c)
    else -> null
}

private fun resize(it: EditItem, v: Float, aspect: Float): EditItem? = when (it) {
    is EditItem.Text -> it.copy(size = v)
    is EditItem.Replace -> it.copy(size = v)
    is EditItem.Picture -> { val nw = (v * 8f).coerceIn(0.05f, 1f); val nh = it.h * nw / it.w; it.copy(w = nw, h = nh) }
    else -> null
}

private fun DrawScope.drawItem(item: EditItem, selected: Boolean, bmp: (File) -> ImageBitmap?) {
    val W = size.width; val H = size.height
    when (item) {
        is EditItem.Replace -> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = item.color; textSize = item.size * W; typeface = if (item.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
            val tw = paint.measureText(item.text)
            val bw = maxOf((item.r - item.l) * W, tw + item.size * W * 0.2f)
            drawRect(Color(item.bg), Offset(item.l * W - 1, item.t * H - 1), Size(bw + 2, (item.b - item.t) * H + 2))
            drawContext.canvas.nativeCanvas.drawText(item.text, item.l * W, item.t * H - paint.fontMetrics.ascent, paint)
            if (selected) drawRect(Color(0xFF2F5BEA), Offset(item.l * W - 3, item.t * H - 3), Size(bw + 6, (item.b - item.t) * H + 6), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        }
        is EditItem.Box -> drawRect(Color(item.color).copy(alpha = item.alpha), Offset(item.l * W, item.t * H), Size((item.r - item.l) * W, (item.b - item.t) * H))
        is EditItem.Ink -> {
            val p = Path().apply { moveTo(item.points[0].first * W, item.points[0].second * H); item.points.drop(1).forEach { lineTo(it.first * W, it.second * H) } }
            drawPath(p, Color(item.color), style = Stroke(item.width * W, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        is EditItem.Text -> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = item.color; textSize = item.size * W; typeface = Typeface.DEFAULT }
            val fm = paint.fontMetrics
            item.text.split('\n').forEachIndexed { i, line ->
                drawContext.canvas.nativeCanvas.drawText(line, item.x * W, item.y * H - fm.ascent + i * item.size * W * 1.2f, paint)
            }
            if (selected) {
                val lines = item.text.split('\n')
                val w = lines.maxOf { paint.measureText(it) }; val h = lines.size * item.size * W * 1.2f
                drawRect(Color(0xFF2F5BEA), Offset(item.x * W - 4, item.y * H - 4), Size(w + 8, h + 8), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
            }
        }
        is EditItem.Picture -> {
            bmp(item.file)?.let { drawImage(it, srcOffset = IntOffset.Zero, srcSize = IntSize(it.width, it.height), dstOffset = IntOffset((item.x * W).toInt(), (item.y * H).toInt()), dstSize = IntSize((item.w * W).toInt(), (item.h * H).toInt())) }
            if (selected) drawRect(Color(0xFF2F5BEA), Offset(item.x * W, item.y * H), Size(item.w * W, item.h * H), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        }
    }
}

/** Draw or type a signature; returns a tightly-cropped transparent bitmap. */
@Composable
private fun SignatureDialog(color: Int, onDismiss: () -> Unit, onDone: (Bitmap) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var name by remember { mutableStateOf("") }
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Your signature", style = MaterialTheme.typography.titleLarge)
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Draw") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Type") })
                }
                if (tab == 0) {
                    Box(Modifier.fillMaxWidth().height(180.dp).background(Color.White, RoundedCornerShape(12.dp)).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(12.dp))) {
                        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                            canvasSize = size
                            detectDragGestures(
                                onDragStart = { current.clear(); current += it },
                                onDragEnd = { if (current.size > 1) strokes += current.toList(); current.clear() },
                                onDrag = { ch, _ -> ch.consume(); current += ch.position }
                            )
                        }) {
                            (strokes + listOf(current.toList())).forEach { s ->
                                if (s.size > 1) drawPath(Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }, Color(color), style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                        if (strokes.isEmpty() && current.isEmpty()) Text("Sign here", color = Color.LightGray, modifier = Modifier.align(Alignment.Center))
                    }
                    TextButton(onClick = { strokes.clear() }) { Text("Clear") }
                } else {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Full name") }, modifier = Modifier.fillMaxWidth())
                    Box(Modifier.fillMaxWidth().height(90.dp).background(Color.White, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Text(name.ifEmpty { "Your Name" }, color = Color(color).copy(alpha = if (name.isEmpty()) 0.3f else 1f), style = MaterialTheme.typography.headlineMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Cursive))
                    }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(enabled = if (tab == 0) strokes.isNotEmpty() else name.isNotBlank(), onClick = {
                        onDone(if (tab == 0) renderStrokes(strokes, color) else renderTyped(name, color))
                    }) { Text("Use signature") }
                }
            }
        }
    }
}

private fun renderStrokes(strokes: List<List<Offset>>, color: Int): Bitmap {
    val pts = strokes.flatten()
    val pad = 12f
    val minX = pts.minOf { it.x } - pad; val minY = pts.minOf { it.y } - pad
    val maxX = pts.maxOf { it.x } + pad; val maxY = pts.maxOf { it.y } + pad
    val scale = 2f
    val bmp = Bitmap.createBitmap(((maxX - minX) * scale).toInt().coerceAtLeast(1), ((maxY - minY) * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 5f * scale; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    strokes.forEach { s ->
        val path = android.graphics.Path()
        s.forEachIndexed { i, o -> val x = (o.x - minX) * scale; val y = (o.y - minY) * scale; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        c.drawPath(path, p)
    }
    return bmp
}

private fun renderTyped(name: String, color: Int): Bitmap {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = 140f; typeface = Typeface.create("cursive", Typeface.NORMAL) }
    val w = p.measureText(name).toInt() + 40
    val fm = p.fontMetrics
    val h = (fm.descent - fm.ascent).toInt() + 20
    val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(bmp).drawText(name, 20f, 10f - fm.ascent, p)
    return bmp
}

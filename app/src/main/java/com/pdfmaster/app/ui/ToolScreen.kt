package com.pdfmaster.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.pdfmaster.app.engine.Work
import com.pdfmaster.app.tools.Input
import com.pdfmaster.app.tools.Mode
import com.pdfmaster.app.tools.Opt
import com.pdfmaster.app.tools.Tool
import java.io.File
import kotlin.math.roundToInt

@Composable
fun ToolScreen(vm: MainVm, tool: Tool) {
    val ctx = LocalContext.current
    val multiple = tool.input.multiple
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addUris(it) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.addUris(listOf(u)) } }
    val pick = {
        val mimes = if (tool.input == Input.HTML) arrayOf("text/html", "application/xhtml+xml") else tool.input.mimes
        if (multiple) pickMany.launch(mimes) else pickOne.launch(mimes)
    }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pdf?.uri?.let { vm.onScanned(it, vm.options["ocr"] == "true", vm.options["lang"] ?: vm.ocrLang) }
        }
    }
    val startScan = {
        val activity = ctx as? Activity
        if (activity != null) {
            val opts = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(opts).getStartScanIntent(activity)
                .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener { vm.error = "The document scanner isn't available on this device (it needs Google Play services). You can photograph pages and use JPG to PDF instead." }
        }
    }

    val ready = when (tool.input) {
        Input.NONE -> true
        Input.HTML -> vm.files.isNotEmpty() || vm.options["url"].orEmpty().isNotBlank()
        else -> vm.files.size >= tool.input.min
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { Bar(tool.title, onBack = { vm.back() }) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().imePadding().padding(16.dp)) {
                if (vm.running) WorkingPanel(vm.progressText, vm.progress) { vm.cancel() }
                else Button(
                    onClick = {
                        when (tool.mode) {
                            Mode.SCAN -> startScan()
                            Mode.EDITOR, Mode.ORGANIZE -> vm.openEditor(tool)
                            Mode.FORM -> vm.run(tool)
                        }
                    },
                    enabled = ready && !vm.importing,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(tool.action, style = MaterialTheme.typography.titleSmall) }
            }
        }
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(tool.icon, tool.category.tint, 48.dp)
                    Text(tool.subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp))
                }
            }
            vm.error?.let { e -> item { ErrorNote(e) } }

            if (tool.input != Input.NONE) {
                if (tool.input == Input.HTML) {
                    item { OptionsCard(tool.options.filter { it.key == "url" }, vm) }
                    item {
                        if (vm.files.isEmpty()) TextButton(onClick = pick) { Icon(Icons.Outlined.FileUpload, null); HSpace(8.dp); Text("Or choose an HTML file") }
                    }
                }
                if (vm.files.isEmpty() && tool.input != Input.HTML) item { DropZone(tool, vm.importing, pick) }
                itemsIndexed(vm.files, key = { _, p -> p.file.path }) { i, p ->
                    FileRow(p, tool.reorder && vm.files.size > 1, i, vm.files.size, onUp = { vm.move(i, i - 1) }, onDown = { vm.move(i, i + 1) }, onRemove = { vm.remove(p) })
                }
                if (vm.files.isNotEmpty() && multiple && !(tool.input == Input.TWO_PDFS && vm.files.size >= 2)) item {
                    OutlinedButton(onClick = pick, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Add, null); HSpace(8.dp); Text(if (tool.input == Input.TWO_PDFS) "Add the second PDF" else "Add more files")
                    }
                }
                if (tool.input == Input.TWO_PDFS && vm.files.size == 2) item {
                    Text("Comparing \"${vm.files[0].name}\" (original) with \"${vm.files[1].name}\" (changed).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val opts = tool.options.filter { tool.input != Input.HTML || it.key != "url" }
            if (opts.isNotEmpty()) item { OptionsCard(opts, vm) }
            if (tool.mode == Mode.EDITOR || tool.mode == Mode.ORGANIZE) item {
                Text(editorHint(tool.id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (tool.mode == Mode.SCAN) item {
                Text("The scanner finds page edges automatically, and lets you crop, rotate and clean up each page before saving.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

private fun editorHint(id: String) = when (id) {
    "edit" -> "Tap \"Edit text\" to change the words already on a page. Scanned pages are read with OCR automatically. You can also add text, drawings, highlights, white-out and images."
    "sign" -> "Draw or type your signature, then tap the page to place it. Drag to move it."
    "redact" -> "Drag over anything you want to hide, or search for words. Redacted pages are flattened so the hidden content is really gone."
    "crop" -> "Drag a box around the area to keep. Apply it to one page or all pages."
    "organize" -> "Reorder, rotate, delete, duplicate pages, or add blank pages and pages from other PDFs."
    else -> ""
}

@Composable
private fun DropZone(tool: Tool, busy: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxWidth().height(180.dp)
            .drawBehind {
                drawRoundRect(color.copy(alpha = 0.05f), cornerRadius = CornerRadius(20.dp.toPx()))
                drawRoundRect(color.copy(alpha = 0.45f), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))), cornerRadius = CornerRadius(20.dp.toPx()))
            }
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (busy) CircularProgressIndicator()
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.FileUpload, null, tint = color, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(8.dp))
            Text("Select ${tool.input.noun}", style = MaterialTheme.typography.titleSmall, color = color)
            if (tool.input.min > 1) Text(if (tool.input == Input.TWO_PDFS) "Original first, then the changed version" else "Choose ${tool.input.min} or more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileRow(p: Picked, reorder: Boolean, index: Int, count: Int, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    if (p.locked) Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp))
                    else Text(p.file.extension.uppercase().take(4), style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(Work.humanSize(p.size), p.pages?.let { "$it page${if (it == 1) "" else "s"}" }, if (p.locked) "Password protected" else null).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (reorder) {
                IconButton(onClick = onUp, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "Move up") }
                IconButton(onClick = onDown, enabled = index < count - 1) { Icon(Icons.Outlined.KeyboardArrowDown, "Move down") }
            }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, "Remove") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun OptionsCard(opts: List<Opt>, vm: MainVm) {
    val values = vm.options
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (o in opts) {
                if (o.visibleIf?.invoke(values) == false) continue
                when (o) {
                    is Opt.Choice -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(o.label, style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            o.choices.forEach { (k, label) -> FilterChip(selected = values[o.key] == k, onClick = { values[o.key] = k }, label = { Text(label) }) }
                        }
                    }
                    is Opt.Dropdown -> {
                        var open by remember { mutableStateOf(false) }
                        val choices = remember(o.key) { o.choices() }
                        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                            OutlinedTextField(
                                value = choices.firstOrNull { it.first == values[o.key] }?.second ?: "", onValueChange = {}, readOnly = true,
                                label = { Text(o.label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            )
                            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                choices.forEach { (k, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { values[o.key] = k; open = false }) }
                            }
                        }
                    }
                    is Opt.Text -> {
                        var show by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = values[o.key].orEmpty(), onValueChange = { values[o.key] = it },
                            label = { Text(o.label) }, placeholder = if (o.hint.isNotEmpty()) ({ Text(o.hint) }) else null,
                            singleLine = true, shape = RoundedCornerShape(12.dp),
                            visualTransformation = if (o.password && !show) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = when { o.password -> KeyboardType.Password; o.number -> KeyboardType.Number; o.key == "url" -> KeyboardType.Uri; else -> KeyboardType.Text }),
                            trailingIcon = if (o.password) ({ IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (show) "Hide" else "Show") } }) else null,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is Opt.Toggle -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { values[o.key] = (values[o.key] != "true").toString() }) {
                        Text(o.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = values[o.key] == "true", onCheckedChange = { values[o.key] = it.toString() })
                    }
                    is Opt.Slider -> Column {
                        val v = values[o.key]?.toFloatOrNull() ?: o.default
                        Row { Text(o.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)); Text("${v.roundToInt()}${o.suffix}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                        Slider(value = v, onValueChange = { values[o.key] = it.roundToInt().toFloat().toString() }, valueRange = o.min..o.max)
                    }
                    is Opt.ImageFile -> {
                        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> vm.setImageOption(o.key, u) } }
                        val path = values[o.key].orEmpty()
                        OutlinedButton(onClick = { launcher.launch("image/*") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Image, null); HSpace(8.dp)
                            Text(if (path.isEmpty()) "Choose ${o.label.lowercase()}" else File(path).name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

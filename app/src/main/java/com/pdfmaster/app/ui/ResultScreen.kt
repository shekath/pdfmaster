package com.pdfmaster.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfmaster.app.engine.Differ
import com.pdfmaster.app.engine.Work
import com.pdfmaster.app.tools.RunResult
import com.pdfmaster.app.tools.Tool
import java.io.File

@Composable
fun ResultScreen(vm: MainVm, tool: Tool, result: RunResult) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var saving by remember { mutableStateOf<File?>(null) }
    var savedTo by remember { mutableStateOf<String?>(null) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = saving
        if (uri != null && f != null) {
            runCatching { Files.copyTo(ctx, f, uri) }
                .onSuccess { Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    fun save(f: File) {
        val where = runCatching { Files.saveToDownloads(ctx, f) }.getOrNull()
        if (where != null) savedTo = where else { saving = f; saveAs.launch(f.name) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { Bar(tool.title, onBack = { vm.back() }, actions = { IconButton(onClick = { vm.home() }) { Icon(Icons.Outlined.Home, "All tools") } }) },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).background(Color(0xFF1E9E6A).copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Check, null, tint = Color(0xFF1E9E6A), modifier = Modifier.size(34.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Done", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(result.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            items(result.files, key = { it.path }) { f ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                                Box(contentAlignment = Alignment.Center) { Text(f.extension.uppercase().take(4), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(Work.humanSize(f.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Button(onClick = { save(f) }, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(vertical = 14.dp), modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Download, null); HSpace(8.dp); Text("Save to Downloads")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { Files.open(ctx, f) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp)); HSpace(6.dp); Text("Open") }
                            FilledTonalButton(onClick = { Files.share(ctx, listOf(f)) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); HSpace(6.dp); Text("Share") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { saving = f; saveAs.launch(f.name) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.SaveAs, null, Modifier.size(18.dp)); HSpace(6.dp); Text("Save as…") }
                            if (f.extension.equals("pdf", true)) OutlinedButton(onClick = { vm.continueWith(f) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Build, null, Modifier.size(18.dp)); HSpace(6.dp); Text("More tools") }
                        }
                    }
                }
            }
            savedTo?.let { s -> item { Text("Saved to $s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) } }
            result.text?.let { text ->
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionLabel("Summary", Modifier.weight(1f))
                                IconButton(onClick = { clipboard.setText(AnnotatedString(text)); Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Outlined.ContentCopy, "Copy") }
                            }
                            SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
            result.diff?.let { d -> diffItems(d) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.diffItems(d: Differ.Result) {
    val changed = d.lines.filter { it.kind != Differ.Kind.SAME }
    if (changed.isEmpty()) return
    item { SectionLabel("Changes (${changed.size})", Modifier.padding(top = 8.dp)) }
    items(changed.take(500)) { l ->
        val added = l.kind == Differ.Kind.ADDED
        val bg = if (added) Color(0xFF1E9E6A) else Color(0xFFE0475B)
        Surface(shape = RoundedCornerShape(10.dp), color = bg.copy(alpha = 0.10f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(if (added) "+" else "−", color = bg, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 10.dp))
                Text(l.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text("p.${l.page}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    if (changed.size > 500) item { Text("Showing the first 500 changes. The full list is in the report PDF.", style = MaterialTheme.typography.bodySmall) }
}

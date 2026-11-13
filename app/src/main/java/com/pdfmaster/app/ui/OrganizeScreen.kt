package com.pdfmaster.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PostAdd
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.pdfmaster.app.engine.PdfOps
import com.pdfmaster.app.engine.Render
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class Thumb(val id: Long, val ref: PdfOps.PageRef)

@Composable
fun OrganizeScreen(vm: MainVm, file: File) {
    val scope = rememberCoroutineScope()
    val sources = remember { mutableStateListOf(file) }
    val docs = remember { mutableStateListOf<Render.Doc>() }
    val pages = remember { mutableStateListOf<Thumb>() }
    var nextId = remember { longArrayOf(0) }
    DisposableEffect(Unit) {
        val d = runCatching { Render.Doc(file) }.getOrNull()
        if (d != null) { docs += d; repeat(d.pageCount) { pages += Thumb(nextId[0]++, PdfOps.PageRef(0, it, 0)) } }
        onDispose { docs.forEach { it.close() } }
    }
    val addPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val f = withContext(Dispatchers.IO) { vm.importSync(uri) } ?: return@launch
            val d = withContext(Dispatchers.IO) { runCatching { Render.Doc(f) }.getOrNull() }
            if (d == null) { vm.error = "That PDF couldn't be opened (is it password protected?)"; return@launch }
            sources += f; docs += d
            val src = sources.lastIndex
            repeat(d.pageCount) { pages += Thumb(nextId[0]++, PdfOps.PageRef(src, it, 0)) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Bar("Organize PDF", { vm.back() }, actions = {
                TextButton(onClick = { vm.runOrganize(sources.toList(), pages.map { it.ref }) }, enabled = pages.isNotEmpty() && !vm.running) { Text("Save", style = MaterialTheme.typography.titleSmall) }
            })
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.error?.let { ErrorNote(it) }
                if (vm.running) WorkingPanel(vm.progressText, vm.progress) { vm.cancel() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { addPdf.launch(arrayOf("application/pdf")) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.PostAdd, null, Modifier.size(18.dp)); HSpace(6.dp); Text("Add PDF")
                    }
                    OutlinedButton(onClick = { pages += Thumb(nextId[0]++, PdfOps.PageRef(0, -1, 0)) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Outlined.NoteAdd, null, Modifier.size(18.dp)); HSpace(6.dp); Text("Blank page")
                    }
                }
            }
        }
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(pages, key = { _, t -> t.id }) { i, t ->
                PageCard(i, t.ref, docs.getOrNull(t.ref.src),
                    onRotate = { pages[i] = t.copy(ref = t.ref.copy(rotation = (t.ref.rotation + 90) % 360)) },
                    onDelete = { pages.removeAt(i) },
                    onDuplicate = { pages.add(i + 1, Thumb(nextId[0]++, t.ref)) },
                    onLeft = { if (i > 0) { pages.removeAt(i); pages.add(i - 1, t) } },
                    onRight = { if (i < pages.lastIndex) { pages.removeAt(i); pages.add(i + 1, t) } },
                    isFirst = i == 0, isLast = i == pages.lastIndex)
            }
        }
    }
}

@Composable
private fun PageCard(index: Int, ref: PdfOps.PageRef, doc: Render.Doc?, onRotate: () -> Unit, onDelete: () -> Unit, onDuplicate: () -> Unit, onLeft: () -> Unit, onRight: () -> Unit, isFirst: Boolean, isLast: Boolean) {
    val bmp by produceState<Bitmap?>(null, ref.src, ref.index) {
        value = if (doc == null || ref.index < 0) null else withContext(Dispatchers.IO) { runCatching { doc.render(ref.index, 320) }.getOrNull() }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.77f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp).rotate(ref.rotation.toFloat()))
                else if (ref.index < 0) Box(Modifier.fillMaxSize(0.7f).rotate(ref.rotation.toFloat()).background(Color.White)) { Text("Blank", color = Color.Gray, modifier = Modifier.align(Alignment.Center)) }
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) {
                    Text("${index + 1}", color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onLeft, enabled = !isFirst, modifier = Modifier.size(34.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Move earlier", Modifier.size(18.dp)) }
                IconButton(onClick = onRotate, modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.RotateRight, "Rotate", Modifier.size(18.dp)) }
                IconButton(onClick = onDuplicate, modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.ContentCopy, "Duplicate", Modifier.size(18.dp)) }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.Delete, "Delete", Modifier.size(18.dp)) }
                IconButton(onClick = onRight, enabled = !isLast, modifier = Modifier.size(34.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Move later", Modifier.size(18.dp)) }
            }
        }
    }
}

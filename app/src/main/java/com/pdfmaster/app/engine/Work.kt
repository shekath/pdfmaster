package com.pdfmaster.app.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** File-system helpers: importing content URIs, naming outputs, zipping, page-range parsing. */
object Work {
    fun inDir(ctx: Context): File = File(ctx.cacheDir, "in").apply { mkdirs() }
    fun outDir(ctx: Context): File = File(ctx.cacheDir, "out").apply { mkdirs() }
    fun tmpDir(ctx: Context): File = File(ctx.cacheDir, "tmp").apply { mkdirs() }

    fun clearAll(ctx: Context) {
        listOf("in", "out", "tmp").forEach { File(ctx.cacheDir, it).deleteRecursively() }
    }

    fun displayName(ctx: Context, uri: Uri): String {
        var name: String? = null
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        }
        return sanitize(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file")
    }

    fun sanitize(n: String): String =
        n.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(120).ifBlank { "file" }

    fun importUri(ctx: Context, uri: Uri): File {
        val name = displayName(ctx, uri)
        val dir = File(inDir(ctx), System.nanoTime().toString()).apply { mkdirs() }
        val f = File(dir, name)
        val input = ctx.contentResolver.openInputStream(uri) ?: error("Cannot open $name")
        input.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    fun baseName(f: File): String = f.name.substringBeforeLast('.').ifBlank { "document" }

    /** A unique file in the output directory. */
    fun outFile(ctx: Context, base: String, ext: String): File {
        val b = sanitize(base).ifBlank { "document" }
        val dir = outDir(ctx)
        var f = File(dir, "$b.$ext")
        var i = 2
        while (f.exists()) { f = File(dir, "$b ($i).$ext"); i++ }
        return f
    }

    fun tmpFile(ctx: Context, ext: String): File = File(tmpDir(ctx), "${System.nanoTime()}.$ext")

    fun zip(files: List<File>, out: File): File {
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            val used = HashSet<String>()
            for (f in files) {
                var n = f.name
                var i = 2
                while (!used.add(n)) { n = "${f.nameWithoutExtension} ($i).${f.extension}"; i++ }
                z.putNextEntry(ZipEntry(n))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return out
    }

    /**
     * Parses "1-3, 5, 8-" into 1-based inclusive ranges. "8-" means 8 to the end.
     * @throws IllegalArgumentException with a user-readable message.
     */
    fun parseRanges(spec: String, pageCount: Int): List<IntRange> {
        val parts = spec.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "Enter at least one page or range, e.g. 1-3, 5" }
        return parts.map { p ->
            val m = Regex("^(\\d*)\\s*-\\s*(\\d*)$").find(p)
            val r = if (m != null) {
                val a = m.groupValues[1].ifEmpty { "1" }.toInt()
                val b = m.groupValues[2].ifEmpty { pageCount.toString() }.toInt()
                a..b
            } else {
                val n = p.toIntOrNull() ?: throw IllegalArgumentException("\"$p\" is not a page number")
                n..n
            }
            require(r.first in 1..pageCount && r.last in 1..pageCount) {
                "Page range \"$p\" is outside this document (1-$pageCount)"
            }
            require(r.first <= r.last) { "Range \"$p\" is backwards" }
            r
        }
    }

    fun pagesFromSpec(spec: String, pageCount: Int): Set<Int> =
        if (spec.isBlank()) (0 until pageCount).toSet()
        else parseRanges(spec, pageCount).flatMap { r -> r.map { it - 1 } }.toSet()

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }
}

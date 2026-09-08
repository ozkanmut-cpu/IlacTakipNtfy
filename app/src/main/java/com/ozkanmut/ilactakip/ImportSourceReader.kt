package com.ozkanmut.ilactakip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object ImportSourceReader {
    data class Result(val text: String, val error: String? = null)

    fun read(c: Context, uri: Uri, done: (Result) -> Unit) {
        val mime = c.contentResolver.getType(uri).orEmpty().lowercase()
        when {
            mime.startsWith("image/") -> readImage(c, uri, done)
            mime == "application/pdf" -> readPdf(c, uri, done)
            mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") -> readText(c, uri, done)
            else -> readText(c, uri) { r ->
                if (r.text.isNotBlank()) done(r)
                else done(Result("", tr("Bu dosya türünden metin çıkarılamadı.", "Text could not be extracted from this file type.")))
            }
        }
    }

    private fun readText(c: Context, uri: Uri, done: (Result) -> Unit) {
        runCatching {
            c.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        }.onSuccess { done(Result(it)) }
            .onFailure { done(Result("", it.message ?: tr("Dosya okunamadı.", "The file could not be read."))) }
    }

    private fun readImage(c: Context, uri: Uri, done: (Result) -> Unit) {
        runCatching { InputImage.fromFilePath(c, uri) }
            .onFailure { done(Result("", it.message ?: tr("Görsel açılamadı.", "The image could not be opened."))) }
            .onSuccess { image ->
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(image)
                    .addOnSuccessListener { done(Result(it.text)) }
                    .addOnFailureListener { done(Result("", it.message ?: tr("Metin okunamadı.", "Text could not be recognized."))) }
                    .addOnCompleteListener { recognizer.close() }
            }
    }

    private fun readPdf(c: Context, uri: Uri, done: (Result) -> Unit) {
        val descriptor = runCatching { c.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (descriptor == null) { done(Result("", tr("PDF açılamadı.", "The PDF could not be opened."))); return }
        val renderer = runCatching { PdfRenderer(descriptor) }.getOrElse {
            descriptor.close(); done(Result("", it.message ?: tr("PDF açılamadı.", "The PDF could not be opened."))); return
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val pageCount = minOf(renderer.pageCount, 8)
        val output = StringBuilder()

        fun finish(error: String? = null) {
            recognizer.close(); renderer.close(); descriptor.close()
            done(Result(output.toString().trim(), error))
        }

        fun processPage(index: Int) {
            if (index >= pageCount) { finish(); return }
            val page = renderer.openPage(index)
            val width = page.width.coerceAtMost(1800)
            val height = ((page.height.toDouble() / page.width.coerceAtLeast(1)) * width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    if (text.text.isNotBlank()) {
                        if (output.isNotEmpty()) output.append('\n')
                        output.append(text.text)
                    }
                }
                .addOnFailureListener {
                    if (output.isEmpty()) { bitmap.recycle(); finish(it.message ?: tr("PDF metni okunamadı.", "PDF text could not be recognized.")); return@addOnFailureListener }
                }
                .addOnCompleteListener {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    if (renderer.pageCount > 0) processPage(index + 1)
                }
        }
        processPage(0)
    }

    private fun tr(tr: String, en: String) = if (I18n.language() == "tr") tr else en
}

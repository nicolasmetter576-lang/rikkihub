package me.rerere.document

import com.artifex.mupdf.fitz.PDFDocument
import java.io.File

object PdfParser {
    fun parsePdf(file: File): String {
        val document = PDFDocument.openDocument(file.absolutePath)
        try {
            // asPDF() keeps an extra native reference, so both wrappers must be destroyed.
            val pdf = document.asPDF()
                ?: throw IllegalStateException("Not a PDF document: ${file.name}")
            try {
                val pages = pdf.countPages()
                val result = StringBuilder()
                for (i in 0 until pages) {
                    val page = pdf.loadPage(i)
                    try {
                        val text = page.toStructuredText()
                        try {
                            result.append("---")
                            result.append("Page ${i + 1}:\n")
                            result.append(text.asText())
                            result.appendLine()
                        } finally {
                            text.destroy()
                        }
                    } finally {
                        page.destroy()
                    }
                }
                return result.toString()
            } finally {
                pdf.destroy()
            }
        } finally {
            document.destroy()
        }
    }
}

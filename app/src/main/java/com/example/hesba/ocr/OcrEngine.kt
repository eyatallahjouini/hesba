package com.example.hesba.ocr

import android.content.Context
import android.graphics.Bitmap
import com.example.hesba.domain.TextNormalizer
import com.example.hesba.domain.invoice.InvoiceParser
import com.example.hesba.domain.invoice.OcrLayout
import com.example.hesba.domain.invoice.OcrRow
import com.example.hesba.domain.invoice.OcrToken
import com.example.hesba.domain.invoice.ParsedInvoice

/** Résultat brut d'un moteur OCR : morceaux de texte positionnés. */
data class OcrResult(val engine: String, val tokens: List<OcrToken>, val rightToLeft: Boolean) {
    val rows: List<OcrRow> by lazy { OcrLayout.groupRows(tokens, rightToLeft) }
}

/** Un moteur de reconnaissance de texte, sur le téléphone, gratuit. */
interface OcrEngine {
    val id: String
    suspend fun recognize(bitmap: Bitmap): OcrResult
    fun close() {}
}

enum class OcrLanguage { AUTO, FRENCH, ARABIC }

/**
 * Choix du moteur :
 *  - Français/anglais → ML Kit (écriture latine, modèle inclus dans l'appli) ;
 *  - العربية → Tesseract avec le modèle arabe (tessdata_fast « ara ») ;
 *  - Auto → ML Kit d'abord ; si le texte est surtout arabe ou si la facture n'est pas exploitable,
 *    Tesseract est essayé et on garde le résultat que le parseur comprend le mieux.
 */
class OcrRouter(private val context: Context) {

    data class Outcome(val result: OcrResult, val invoice: ParsedInvoice, val language: String)

    private val latin by lazy { MlKitOcrEngine() }
    private val arabic by lazy { TesseractOcrEngine(context) }

    suspend fun read(pages: List<Bitmap>, language: OcrLanguage): Outcome = when (language) {
        OcrLanguage.FRENCH -> run(latin, pages, "fr")
        OcrLanguage.ARABIC -> run(arabic, pages, "ar")
        OcrLanguage.AUTO -> {
            val first = run(latin, pages, "fr")
            val text = first.result.rows.joinToString(" ") { it.text }
            val looksArabic = TextNormalizer.arabicRatio(text) > 0.3
            if (!looksArabic && score(first.invoice) >= 2) first
            else {
                val second = run(arabic, pages, "ar")
                if (score(second.invoice) > score(first.invoice)) second else first
            }
        }
    }

    private suspend fun run(engine: OcrEngine, pages: List<Bitmap>, language: String): Outcome {
        val results = pages.map { engine.recognize(it) }
        val merged = OcrResult(engine.id, emptyList(), results.firstOrNull()?.rightToLeft ?: false)
        val rows = results.flatMap { it.rows.map { r -> r.text } }
        val invoice = InvoiceParser.parse(rows)
        return Outcome(MergedResult(merged, results), invoice, language)
    }

    /** Qualité d'une lecture : lignes produit sûres comptent double, date et total comptent aussi. */
    private fun score(inv: ParsedInvoice): Int =
        inv.lines.count { it.uncertain.isEmpty() } * 2 + inv.lines.size +
            (if (inv.date.value != null) 1 else 0) + (if (inv.totalMillimes != null) 1 else 0)

    fun close() {
        runCatching { latin.close() }
        runCatching { arabic.close() }
    }

    /** Plusieurs pages (PDF) mises bout à bout. */
    @Suppress("FunctionName")
    private fun MergedResult(base: OcrResult, pages: List<OcrResult>): OcrResult {
        var offset = 0
        val tokens = mutableListOf<OcrToken>()
        for (p in pages) {
            val height = (p.tokens.maxOfOrNull { it.bottom } ?: 0) + 50
            p.tokens.forEach { t -> tokens += t.copy(top = t.top + offset, bottom = t.bottom + offset) }
            offset += height
        }
        return base.copy(tokens = tokens)
    }
}

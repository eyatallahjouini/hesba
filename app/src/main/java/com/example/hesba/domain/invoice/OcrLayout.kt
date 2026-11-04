package com.example.hesba.domain.invoice

/** Un morceau de texte reconnu avec sa position dans l'image (pixels). */
data class OcrToken(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float? = null      // 0..1, null si le moteur ne la donne pas
) {
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val centerY: Float get() = (top + bottom) / 2f
}

/** Ligne de facture reconstituée (une ligne visuelle du document). */
data class OcrRow(val text: String, val minConfidence: Float?)

/**
 * Les moteurs OCR découpent souvent un tableau en colonnes (désignation d'un côté, prix de l'autre).
 * On reconstruit les lignes visuelles : les morceaux dont les bandes verticales se recouvrent
 * sont sur la même ligne ; dans une ligne on les range de gauche à droite (ou de droite à gauche en arabe).
 * Deux morceaux très espacés sont séparés par deux espaces (indice de colonne pour le parseur).
 */
object OcrLayout {

    fun groupRows(tokens: List<OcrToken>, rightToLeft: Boolean): List<OcrRow> {
        val clean = tokens.filter { it.text.isNotBlank() }.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<OcrToken>>()
        for (token in clean) {
            val row = rows.lastOrNull()
            if (row != null && sameRow(row, token)) row += token else rows += mutableListOf(token)
        }
        return rows.map { row ->
            val ordered = if (rightToLeft) row.sortedByDescending { it.right } else row.sortedBy { it.left }
            val avgHeight = row.map { it.height }.average()
            val sb = StringBuilder()
            ordered.forEachIndexed { i, t ->
                if (i > 0) {
                    val prev = ordered[i - 1]
                    val gap = if (rightToLeft) prev.left - t.right else t.left - prev.right
                    sb.append(if (gap > avgHeight * 1.5) "  " else " ")
                }
                sb.append(t.text.trim())
            }
            OcrRow(sb.toString(), row.mapNotNull { it.confidence }.minOrNull())
        }
    }

    private fun sameRow(row: List<OcrToken>, token: OcrToken): Boolean {
        val top = row.minOf { it.top }
        val bottom = row.maxOf { it.bottom }
        val overlap = minOf(bottom, token.bottom) - maxOf(top, token.top)
        val minHeight = minOf(row.map { it.height }.average(), token.height.toDouble())
        return overlap >= minHeight * 0.5
    }
}

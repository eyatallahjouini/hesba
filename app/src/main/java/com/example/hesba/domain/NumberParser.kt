package com.example.hesba.domain

import java.math.BigDecimal

/**
 * Lecture des nombres écrits à la main, saisis au clavier ou reconnus par l'OCR.
 *
 * - Chiffres latins 0-9, arabes orientaux ٠-٩ et persans ۰-۹ (normalisés par [TextNormalizer.normalizeDigits]).
 * - Séparateur décimal : « , » « . » « ٫ » ou « ، ».
 * - Un seul séparateur → c'est la virgule décimale (« 1.500 » = 1,5 dinar, l'usage tunisien).
 * - Plusieurs séparateurs → le dernier est décimal, les autres sont des milliers (« 1.234,500 »).
 * - Espaces (y compris insécables) et « ٬ » = séparateurs de milliers.
 */
object NumberParser {

    private val SEPARATORS = setOf(',', '.', '٫', '،')
    private val THOUSAND_MARKS = setOf(' ', ' ', ' ', '٬', '\'')

    fun parse(raw: String): BigDecimal? {
        var text = TextNormalizer.stripBidiMarks(TextNormalizer.normalizeDigits(raw)).trim()
        if (text.isEmpty()) return null
        var negative = false
        if (text.startsWith("-") || text.startsWith("−")) { negative = true; text = text.substring(1) }
        text = text.filterNot { it in THOUSAND_MARKS }
        if (text.isEmpty() || text.any { !it.isDigit() && it !in SEPARATORS }) return null
        if (!text.first().isDigit() && text.length == 1) return null

        val sepPositions = text.indices.filter { text[it] in SEPARATORS }
        val normalized = when (sepPositions.size) {
            0 -> text
            1 -> text.replace(text[sepPositions[0]], '.')
            else -> {
                val last = sepPositions.last()
                val intPart = text.substring(0, last).filter { it.isDigit() }
                intPart + "." + text.substring(last + 1)
            }
        }
        if (normalized == "." || normalized.endsWith(".") && normalized.length == 1) return null
        val cleaned = if (normalized.endsWith(".")) normalized.dropLast(1) else normalized
        val withLeading = if (cleaned.startsWith(".")) "0$cleaned" else cleaned
        val value = withLeading.toBigDecimalOrNull() ?: return null
        return if (negative) value.negate() else value
    }

    /** Version pratique pour les quantités saisies dans un champ. */
    fun parseOrNull(raw: String?): BigDecimal? = raw?.let { parse(it) }

    /**
     * Nombre entier positif ou nul (parts, minutes, employés, heures…), chiffres latins ou arabes.
     * Null si le champ est vide, négatif, décimal (« 2,5 » n'est PAS arrondi à 2) ou trop grand pour un Int
     * (BigDecimal.toInt() rebouclerait : « 4294967298 » deviendrait 2).
     */
    fun parseCount(raw: String?): Int? {
        val value = parseOrNull(raw) ?: return null
        if (value.signum() < 0) return null
        return try { value.intValueExact() } catch (e: ArithmeticException) { null }
    }
}

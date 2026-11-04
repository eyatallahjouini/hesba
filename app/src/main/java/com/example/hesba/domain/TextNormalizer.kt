package com.example.hesba.domain

import java.text.Normalizer
import java.util.Locale

/**
 * Normalisation de texte pour la recherche et la correspondance d'ingrédients.
 * Français : minuscules, sans accents (« Crème » = « creme »), œ → oe.
 * Arabe : sans harakat ni tatweel, أ/إ/آ/ٱ → ا, ى → ي, ة → ه, ؤ → و, ئ → ي.
 */
object TextNormalizer {

    private val COMBINING = Regex("\\p{Mn}+")
    private val BIDI = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u061C]")
    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")
    private val SPACES = Regex("\\s+")

    fun normalizeDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    fun stripBidiMarks(text: String): String = text.replace(BIDI, "")

    /** Clé de comparaison : minuscules, sans accents/harakat, lettres arabes unifiées, ponctuation → espace. */
    fun normalize(text: String): String {
        var s = stripBidiMarks(normalizeDigits(text))
        s = s.replace("œ", "oe").replace("Œ", "oe").replace("æ", "ae").replace("Æ", "ae")
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(COMBINING, "")
        s = s.lowercase(Locale.ROOT)
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    'ٱ', 'أ', 'إ', 'آ' -> 'ا'
                    'ى', 'ی' -> 'ي'
                    'ة' -> 'ه'
                    'ؤ' -> 'و'
                    'ئ' -> 'ي'
                    'ک' -> 'ك'
                    else -> c
                }
            )
        }
        sb.removeAll('ـ')   // tatweel : simple étirement décoratif, à supprimer
        return sb.toString().replace(NON_WORD, " ").replace(SPACES, " ").trim()
    }

    private fun StringBuilder.removeAll(ch: Char) {
        var i = indexOf(ch.toString())
        while (i >= 0) { deleteCharAt(i); i = indexOf(ch.toString(), i) }
    }

    fun tokens(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    fun containsArabic(text: String): Boolean = text.any { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' }

    /** Part des lettres qui sont arabes (0..1) ; sert au choix automatique du moteur OCR. */
    fun arabicRatio(text: String): Double {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return 0.0
        return letters.count { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' }.toDouble() / letters.length
    }

    /** Vrai si [query] (déjà normalisée ou non) se trouve dans [text], sans tenir compte des accents/casse. */
    fun matches(text: String, query: String): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return true
        return normalize(text).contains(q)
    }
}

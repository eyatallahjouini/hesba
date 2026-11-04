package com.example.hesba.domain.invoice

import com.example.hesba.domain.TextNormalizer

/**
 * Correspondance « nom lu sur la facture » → ingrédient de l'utilisateur, entièrement sur le téléphone.
 * Mise derrière une interface pour pouvoir brancher plus tard un autre moteur (ex. service en ligne)
 * sans toucher aux écrans.
 */
interface IngredientMatching {
    fun match(text: String): MatchResult
}

data class MatchCandidate(val ingredientId: Long, val name: String)

enum class MatchConfidence { EXACT, HIGH, LOW, NONE }

data class MatchResult(
    val ingredientId: Long?,
    val score: Double,
    val confidence: MatchConfidence,
    val alternatives: List<Pair<Long, Double>>
)

/**
 * Règles :
 *  1. synonyme enregistré (correction précédente) ou nom identique après normalisation → EXACT ;
 *  2. sinon score = meilleur de : similarité globale (Levenshtein), similarité mot à mot,
 *     inclusion de tous les mots de l'ingrédient ; l'article arabe « ال » et les mots vides sont ignorés ;
 *  3. ≥ 0,90 → HIGH (proposé), ≥ 0,70 → LOW (proposé mais marqué à vérifier), sinon aucun.
 */
class IngredientMatcher(
    private val candidates: List<MatchCandidate>,
    synonyms: List<Pair<String, Long>>   // (texte déjà normalisé, ingredientId)
) : IngredientMatching {

    private val synonymMap: Map<String, Long> = synonyms.associate { (text, id) -> text to id }
    private val prepared = candidates.map { it to keyTokens(it.name) }

    override fun match(text: String): MatchResult {
        val normalized = TextNormalizer.normalize(text)
        synonymMap[normalized]?.let { return MatchResult(it, 1.0, MatchConfidence.EXACT, emptyList()) }
        val queryTokens = keyTokens(text)
        if (queryTokens.isEmpty()) return MatchResult(null, 0.0, MatchConfidence.NONE, emptyList())
        synonymMap[queryTokens.joinToString(" ")]?.let { return MatchResult(it, 1.0, MatchConfidence.EXACT, emptyList()) }

        val scored = prepared.map { (candidate, tokens) -> candidate.ingredientId to score(queryTokens, tokens) }
            .sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return MatchResult(null, 0.0, MatchConfidence.NONE, emptyList())
        val alternatives = scored.drop(1).filter { it.second >= LOW }.take(3)
        val confidence = when {
            best.second >= 0.999 -> MatchConfidence.EXACT
            best.second >= HIGH -> MatchConfidence.HIGH
            best.second >= LOW -> MatchConfidence.LOW
            else -> MatchConfidence.NONE
        }
        // deux candidats presque à égalité : on ne choisit pas à la place de l'utilisateur
        val tie = alternatives.firstOrNull()?.let { best.second - it.second < 0.03 } ?: false
        val finalConfidence = if (tie && confidence != MatchConfidence.NONE) MatchConfidence.LOW else confidence
        return MatchResult(
            if (finalConfidence == MatchConfidence.NONE) null else best.first,
            best.second, finalConfidence, alternatives
        )
    }

    companion object {
        const val HIGH = 0.90
        const val LOW = 0.70
        private val STOP = setOf("de", "du", "des", "la", "le", "les", "l", "d", "en", "au", "aux", "et", "a", "the", "of",
            "sac", "paquet", "boite", "bte", "pqt", "carton", "bidon", "kg", "g", "gr", "l", "ml", "pcs", "pc", "piece", "pieces",
            "كيس", "علبه", "كغ", "غ", "ل", "قطعه", "من", "و")

        fun keyTokens(text: String): List<String> =
            TextNormalizer.tokens(text)
                .filter { tok -> tok.any { it.isLetter() } }
                .map { stripArticle(it) }
                .filter { it !in STOP && it.length >= 2 }

        private fun stripArticle(token: String): String = when {
            token.length > 4 && (token.startsWith("وال") || token.startsWith("بال")) -> token.substring(3)
            token.length > 3 && token.startsWith("ال") -> token.substring(2)
            else -> token
        }

        fun score(query: List<String>, candidate: List<String>): Double {
            if (candidate.isEmpty()) return 0.0
            val whole = similarity(query.joinToString(" "), candidate.joinToString(" "))
            // chaque mot de l'ingrédient trouvé (même approximativement) dans le texte de la facture
            val perToken = candidate.map { c -> query.maxOf { q -> similarity(q, c) } }
            val coverage = perToken.average()
            val allFound = perToken.all { it >= 0.85 }
            val containment = if (allFound) 0.92 * coverage + 0.08 * (candidate.size.toDouble() / maxOf(query.size, candidate.size)) else 0.0
            return maxOf(whole, containment, coverage * 0.9)
        }

        /** 1 − distance de Levenshtein ÷ longueur max. */
        fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                System.arraycopy(cur, 0, prev, 0, cur.size)
            }
            return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
        }
    }
}

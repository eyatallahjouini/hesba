package com.example.hesba.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Politique d'arrondi unique de HESBA (dinar tunisien, 3 décimales = millimes).
 *
 * 1. Les montants sont STOCKÉS en millimes entiers (Long) : 1,250 DT = 1250.
 * 2. Les calculs intermédiaires (prix au gramme, coût d'une ligne, total du lot…) se font
 *    en BigDecimal SANS arrondi au millime, avec [INTERNAL_SCALE] décimales pour les divisions.
 * 3. On arrondit au millime (HALF_UP : 0,0005 → 0,001) uniquement les résultats qu'on montre
 *    ou qu'on enregistre : coût d'une ligne affiché, coût du lot, coût par part, prix conseillé.
 * 4. Le prix conseillé est calculé à partir du coût par part DÉJÀ arrondi, pour que l'utilisateur
 *    puisse refaire le calcul à la main avec les chiffres affichés.
 */
object Money {
    const val DECIMALS = 3
    val ROUNDING: RoundingMode = RoundingMode.HALF_UP
    const val INTERNAL_SCALE = 10
    private val THOUSAND = BigDecimal(1000)

    /** Montant en dinars (BigDecimal) → millimes arrondis selon la politique. */
    fun toMillimes(dinars: BigDecimal): Long =
        dinars.setScale(DECIMALS, ROUNDING).movePointRight(DECIMALS).longValueExact()

    /** Montant exprimé en millimes (éventuellement fractionnaires) → millimes entiers arrondis. */
    fun roundMillimes(millimes: BigDecimal): Long = millimes.setScale(0, ROUNDING).longValueExact()

    fun toDinars(millimes: Long): BigDecimal = BigDecimal.valueOf(millimes, DECIMALS)

    /** Division au format interne (jamais d'ArithmeticException pour un développement infini). */
    fun div(a: BigDecimal, b: BigDecimal): BigDecimal = a.divide(b, INTERNAL_SCALE, ROUNDING)

    /**
     * Lit un montant saisi ou reconnu par l'OCR : « 1,250 », « 1.250 », « 1 250,500 », « ١٫٢٥٠ »…
     * Renvoie des millimes, ou null si le texte n'est pas un nombre positif lisible.
     * Voir [NumberParser] pour les règles de séparateurs.
     */
    fun parseMillimes(text: String): Long? {
        val value = NumberParser.parse(text) ?: return null
        if (value.signum() < 0) return null
        return toMillimes(value)
    }

    /** Convertit une ancienne valeur Double (base v1) en millimes, sans autre changement que l'arrondi. */
    fun fromLegacyDouble(value: Double): Long = toMillimes(BigDecimal(value.toString()))

    fun millimesPerDinar(): BigDecimal = THOUSAND
}

package com.example.hesba.domain

import java.math.BigDecimal

/**
 * Unités de HESBA.
 * Chaque ingrédient est stocké dans une UNITÉ DE BASE : "g", "ml" ou "piece".
 * Les recettes et les factures peuvent utiliser d'autres unités, converties vers la base :
 *  - masse : mg, g, kg
 *  - volume : ml, cl, l
 *  - pièce : piece, douzaine
 *  - emballage (« boîte », « paquet », « sac »…) : 1 emballage = quantité de l'emballage de l'ingrédient.
 */
enum class MeasureUnit(val code: String, val base: String?, val factor: BigDecimal?) {
    MG("mg", "g", BigDecimal("0.001")),
    G("g", "g", BigDecimal.ONE),
    KG("kg", "g", BigDecimal(1000)),
    ML("ml", "ml", BigDecimal.ONE),
    CL("cl", "ml", BigDecimal(10)),
    L("l", "ml", BigDecimal(1000)),
    PIECE("piece", "piece", BigDecimal.ONE),
    DOZEN("douzaine", "piece", BigDecimal(12)),
    PACK("emballage", null, null);   // dépend de l'ingrédient

    companion object {
        val BASES = listOf("g", "ml", "piece")

        fun fromCode(code: String?): MeasureUnit? = entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }

        /** Unités qu'on peut choisir pour une ligne de recette selon l'unité de base de l'ingrédient. */
        fun compatibleWith(base: String): List<MeasureUnit> = when (base) {
            "g" -> listOf(G, KG, MG, PACK)
            "ml" -> listOf(ML, L, CL, PACK)
            else -> listOf(PIECE, DOZEN, PACK)
        }

        /**
         * Reconnaît une unité écrite sur une facture (français, anglais, arabe).
         * Renvoie null si le mot n'est pas une unité.
         */
        fun parseWord(word: String): MeasureUnit? {
            val w = TextNormalizer.normalize(word).replace(" ", "")
            return when (w) {
                "mg" -> MG
                "g", "gr", "grs", "gramme", "grammes", "gram", "grams", "غ", "غرام", "غم", "جرام" -> G
                "kg", "kgs", "kilo", "kilos", "kilogramme", "kilogrammes", "كغ", "كلغ", "كغم", "كيلو", "كيلوغرام" -> KG
                "ml", "millilitre", "millilitres", "مل" -> ML
                "cl", "centilitre" -> CL
                "l", "lt", "ltr", "litre", "litres", "liter", "liters", "ل", "لتر", "لترات" -> L
                "p", "pc", "pcs", "piece", "pieces", "u", "un", "unite", "unites", "unit", "units", "pce",
                "قطعه", "قطع", "حبه", "حبات", "وحده" -> PIECE
                "dz", "douzaine", "douzaines", "dozen", "دزينه" -> DOZEN
                "bte", "boite", "boites", "box", "paquet", "paquets", "pqt", "sac", "sacs", "carton", "cartons",
                "pack", "bidon", "bidons", "plateau", "plateaux", "علبه", "علب", "كيس", "اكياس", "صندوق", "كرتونه" -> PACK
                else -> null
            }
        }
    }
}

object UnitConverter {

    /**
     * Convertit [quantity] exprimée en [unit] vers l'unité de base [base] de l'ingrédient.
     * [packageQuantityBase] sert pour l'unité « emballage ».
     * Renvoie null si la conversion est impossible (ex. kg vers pièce).
     */
    fun toBase(quantity: BigDecimal, unit: MeasureUnit, base: String, packageQuantityBase: BigDecimal?): BigDecimal? {
        if (unit == MeasureUnit.PACK) {
            if (packageQuantityBase == null || packageQuantityBase.signum() <= 0) return null
            return quantity.multiply(packageQuantityBase)
        }
        if (unit.base != base) return null
        return quantity.multiply(unit.factor)
    }

    fun toBase(quantity: BigDecimal, unitCode: String, base: String, packageQuantityBase: BigDecimal?): BigDecimal? {
        val unit = MeasureUnit.fromCode(unitCode) ?: return null
        return toBase(quantity, unit, base, packageQuantityBase)
    }

    /** Convertit une quantité de base vers l'unité [unit] (affichage). */
    fun fromBase(quantityBase: BigDecimal, unit: MeasureUnit, packageQuantityBase: BigDecimal?): BigDecimal? {
        if (unit == MeasureUnit.PACK) {
            if (packageQuantityBase == null || packageQuantityBase.signum() <= 0) return null
            return Money.div(quantityBase, packageQuantityBase)
        }
        return Money.div(quantityBase, unit.factor!!)
    }
}

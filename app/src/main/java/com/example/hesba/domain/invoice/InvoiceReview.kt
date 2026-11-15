package com.example.hesba.domain.invoice

import com.example.hesba.data.Ingredient
import com.example.hesba.domain.MeasureUnit
import com.example.hesba.domain.Money
import com.example.hesba.domain.UnitConverter
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Vérification d'une facture avant mise à jour des prix (logique pure, testée).
 * Règle de prudence : une ligne n'est acceptée par défaut que si l'ingrédient ET le prix sont sûrs.
 * Tout le reste attend une action de l'utilisateur ; rien n'est écrit avant sa confirmation.
 */
object InvoiceReview {

    enum class Action { ACCEPT, IGNORE }
    enum class Problem { NO_INGREDIENT, NO_PRICE, UNIT_MISMATCH, DUPLICATE, INVALID_PRICE, INVALID_QUANTITY, NO_NAME }

    /** Pourquoi une ligne n'est pas enregistrée (montré dans le résumé final). */
    enum class Skip { IGNORED, NO_INGREDIENT, NO_PRICE, INVALID_PRICE, INVALID_QUANTITY, UNIT_MISMATCH, DUPLICATE, NO_NAME }

    data class Line(
        val index: Int,
        val source: InvoiceLine,
        val name: String = source.name,
        val quantity: BigDecimal? = source.quantity,
        val unit: MeasureUnit? = source.unit,
        val packageSize: BigDecimal? = source.packageSize,
        val packageUnit: MeasureUnit? = source.packageUnit,
        val unitPriceMillimes: Long? = source.unitPriceMillimes,
        val ingredientId: Long? = null,
        val match: MatchConfidence = MatchConfidence.NONE,
        val createNew: Boolean = false,
        val action: Action = Action.IGNORE,
        val edited: Boolean = false
    ) {
        /** Champs encore « à vérifier » (une modification par l'utilisateur les valide). */
        val uncertain: Set<LineField> get() = if (edited) emptySet() else source.uncertain

        fun asInvoiceLine() = source.copy(
            name = name, quantity = quantity, unit = unit, packageSize = packageSize, packageUnit = packageUnit,
            unitPriceMillimes = unitPriceMillimes
        )
    }

    data class Evaluation(
        val oldPriceMillimes: Long?,
        val newPriceMillimes: Long?,     // prix du MÊME emballage que l'ingrédient, pour comparer
        val problem: Problem?
    ) {
        val diff: Long? get() = if (oldPriceMillimes != null && newPriceMillimes != null) newPriceMillimes - oldPriceMillimes else null
        val percent: BigDecimal? get() = if (oldPriceMillimes != null && oldPriceMillimes > 0 && diff != null)
            BigDecimal(diff!!).multiply(BigDecimal(100)).divide(BigDecimal(oldPriceMillimes), 1, RoundingMode.HALF_UP) else null
    }

    data class Options(val taxPercent: BigDecimal?, val includeTax: Boolean, val discountPercent: BigDecimal?, val applyDiscount: Boolean) {
        val tax: BigDecimal? get() = if (includeTax) taxPercent else null
        val discount: BigDecimal? get() = if (applyDiscount) discountPercent else null
    }

    fun initial(invoice: ParsedInvoice, matcher: IngredientMatching, ingredients: Map<Long, Ingredient>, options: Options): List<Line> {
        val lines = invoice.lines.mapIndexed { i, src ->
            val m = matcher.match(src.name)
            val line = Line(index = i, source = src, ingredientId = m.ingredientId, match = m.confidence)
            // aucun ingrédient ne correspond : on propose de le créer (si son unité permet de le ranger en g, ml ou pièce)
            if (m.ingredientId == null && newIngredientFor(line) != null) line.copy(createNew = true) else line
        }
        val dup = duplicates(lines.map { it.copy(action = Action.ACCEPT) })
        return lines.map { l ->
            val ev = evaluate(l, ingredients[l.ingredientId], options)
            val sureTarget = l.createNew || l.match == MatchConfidence.EXACT || l.match == MatchConfidence.HIGH
            // coché d'office seulement si la cible, le prix et l'unité sont sûrs ; sinon l'utilisateur décide
            val safe = sureTarget && ev.problem == null &&
                LineField.UNIT_PRICE !in l.uncertain && LineField.UNIT !in l.uncertain && l.index !in dup
            l.copy(action = if (safe) Action.ACCEPT else Action.IGNORE)
        }
    }

    /** Ingrédient virtuel pour une ligne qui créera un nouvel ingrédient. */
    fun newIngredientFor(line: Line): Ingredient? {
        val pricedUnit = line.packageUnit ?: line.unit ?: return null
        if (pricedUnit == MeasureUnit.PACK) return null
        val base = pricedUnit.base ?: return null
        val qty = if (line.packageUnit != null && line.packageSize != null) UnitConverter.toBase(line.packageSize, line.packageUnit, base, null)
        else UnitConverter.toBase(BigDecimal.ONE, pricedUnit, base, null)
        if (qty == null || qty.signum() <= 0) return null
        return Ingredient(nom = line.name.trim(), unite = base, quantiteEmballage = qty.toDouble(), prixMillimes = null)
    }

    fun evaluate(line: Line, ingredient: Ingredient?, options: Options): Evaluation {
        // nouvel ingrédient : le nom est obligatoire
        if (ingredient == null && line.createNew && line.name.isBlank()) return Evaluation(null, null, Problem.NO_NAME)
        val target = ingredient ?: (if (line.createNew) newIngredientFor(line) else null)
            ?: return Evaluation(null, null, if (line.createNew) Problem.UNIT_MISMATCH else Problem.NO_INGREDIENT)
        val old = if (ingredient == null) null else target.prixMillimes
        // quantité : absente = acceptée (beaucoup de tickets n'affichent qu'un prix), mais jamais ≤ 0
        if (line.quantity != null && line.quantity.signum() <= 0) return Evaluation(old, null, Problem.INVALID_QUANTITY)
        if (line.unitPriceMillimes == null) return Evaluation(old, null, Problem.NO_PRICE)
        if (line.unitPriceMillimes <= 0) return Evaluation(old, null, Problem.INVALID_PRICE)   // prix ≤ 0 refusé
        val pkg = BigDecimal(target.quantiteEmballage.toString())
        // unité du ticket convertible vers celle de l'ingrédient (kg ↔ g, L ↔ ml), sinon rien n'est modifié
        val perBase = InvoiceParser.pricePerBaseUnit(line.asInvoiceLine(), target.unite, pkg, options.discount, options.tax)
            ?: return Evaluation(old, null, Problem.UNIT_MISMATCH)
        val newPrice = Money.roundMillimes(perBase.multiply(pkg))
        if (newPrice <= 0) return Evaluation(old, null, Problem.INVALID_PRICE)               // arrondi à 0 millime
        return Evaluation(old, newPrice, null)
    }

    /** Raison pour laquelle la ligne ne sera pas enregistrée, ou null si elle le sera. */
    fun skipReason(line: Line, ingredient: Ingredient?, options: Options, duplicates: Set<Int>): Skip? {
        val problem = evaluate(line, ingredient, options).problem
        return when {
            problem == Problem.NO_INGREDIENT -> Skip.NO_INGREDIENT
            problem == Problem.NO_PRICE -> Skip.NO_PRICE
            problem == Problem.INVALID_PRICE -> Skip.INVALID_PRICE
            problem == Problem.INVALID_QUANTITY -> Skip.INVALID_QUANTITY
            problem == Problem.UNIT_MISMATCH -> Skip.UNIT_MISMATCH
            problem == Problem.NO_NAME -> Skip.NO_NAME
            problem == Problem.DUPLICATE || line.index in duplicates -> Skip.DUPLICATE
            line.action == Action.IGNORE -> Skip.IGNORED
            else -> null
        }
    }

    /** Lignes acceptées qui visent le même ingrédient (on n'en garde qu'une : pas de doublon). */
    fun duplicates(lines: List<Line>): Set<Int> {
        val seen = mutableSetOf<Long>()
        val dups = mutableSetOf<Int>()
        for (l in lines) {
            if (l.action != Action.ACCEPT || l.createNew) continue
            val id = l.ingredientId ?: continue
            if (!seen.add(id)) dups += l.index
        }
        val newNames = mutableSetOf<String>()
        for (l in lines) {
            if (l.action != Action.ACCEPT || !l.createNew) continue
            if (!newNames.add(com.example.hesba.domain.TextNormalizer.normalize(l.name))) dups += l.index
        }
        return dups
    }
}

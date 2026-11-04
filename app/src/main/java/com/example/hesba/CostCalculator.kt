package com.example.hesba

import com.example.hesba.domain.MeasureUnit
import com.example.hesba.domain.Money
import com.example.hesba.domain.UnitConverter
import java.math.BigDecimal

/**
 * Toutes les formules financières de HESBA, en Kotlin pur (aucune dépendance Android).
 * Montants : millimes (Long) en entrée et en sortie, BigDecimal sans arrondi entre les deux.
 * La politique d'arrondi est décrite dans [Money].
 *
 * Formules :
 *  prix réel par unité de base = prix de l'emballage ÷ (quantité de l'emballage × rendement %)
 *  coût d'une ligne            = quantité utilisée (convertie en unité de base) × prix réel par unité
 *  coût du lot                 = Σ lignes + gaz/énergie du lot + main-d'œuvre du lot
 *  coût par part               = coût du lot ÷ nombre de parts + emballage par part + charges fixes par part
 *  prix conseillé              = coût par part ÷ (1 − marge voulue)
 *  marge réelle                = (prix de vente − coût par part) ÷ prix de vente
 */
object CostCalculator {

    private val HUNDRED = BigDecimal(100)

    // ---------- données d'entrée ----------

    data class IngredientPrice(
        val id: Long,
        val name: String,
        val baseUnit: String,               // "g", "ml", "piece"
        val packageQuantity: BigDecimal,    // en unité de base
        val packagePriceMillimes: Long?,    // null = prix inconnu
        val yieldPercent: BigDecimal = HUNDRED
    )

    data class Line(val ingredientId: Long, val quantity: BigDecimal, val unit: String)

    data class HiddenCosts(
        val packagingPerPortionMillimes: Long = 0,
        val energyPerBatchMillimes: Long = 0,
        val laborPerBatchMillimes: Long = 0,
        val fixedPerPortionMillimes: Long = 0      // part du loyer (profil)
    )

    data class RecipeInput(
        val portions: Int,
        val targetMarginPercent: BigDecimal,
        val lines: List<Line>,
        val hidden: HiddenCosts = HiddenCosts(),
        val sellingPriceMillimes: Long? = null
    )

    // ---------- résultats ----------

    enum class Issue { MISSING_INGREDIENT, MISSING_PRICE, ZERO_QUANTITY, UNIT_MISMATCH, INVALID_PACKAGE, ZERO_PORTIONS, INVALID_MARGIN }

    data class LineCost(
        val line: Line,
        val ingredient: IngredientPrice?,
        val quantityBase: BigDecimal?,
        val costExact: BigDecimal?,         // millimes, non arrondi ; null si incalculable
        val issue: Issue?
    ) {
        val costMillimes: Long? get() = costExact?.let { Money.roundMillimes(it) }
    }

    data class RecipeCost(
        val lines: List<LineCost>,
        val ingredientsBatchExact: BigDecimal,
        val hiddenBatchExact: BigDecimal,
        val batchExact: BigDecimal,
        val costPerPortionMillimes: Long?,  // null si parts = 0
        val suggestedPriceMillimes: Long?,  // null si parts = 0 ou marge invalide
        val sellingPriceMillimes: Long?,
        val realMarginPercent: BigDecimal?, // marge avec le prix de vente pratiqué
        val issues: Set<Issue>
    ) {
        val ingredientsBatchMillimes: Long get() = Money.roundMillimes(ingredientsBatchExact)
        val batchMillimes: Long get() = Money.roundMillimes(batchExact)
        /** Le résultat est complet seulement si toutes les lignes ont un prix et une quantité valables. */
        val isComplete: Boolean get() = issues.isEmpty()

        fun isBelowTarget(target: BigDecimal): Boolean =
            realMarginPercent != null && realMarginPercent < target

        /** Ligne qui pèse le plus dans le coût des ingrédients (conseiller de marge). */
        val mostExpensive: LineCost? get() = lines.filter { (it.costExact?.signum() ?: 0) > 0 }.maxByOrNull { it.costExact!! }
    }

    // ---------- formules de base ----------

    /**
     * Prix réel d'une unité de base en millimes (non arrondi), pertes comprises.
     * Ex. 1000 g à 6,000 DT, rendement 100 % → 6 millimes/g ; rendement 70 % → 8,571… millimes/g.
     * Renvoie null si le prix est inconnu, la quantité ≤ 0 ou le rendement hors de ]0 ; 100].
     */
    fun unitPrice(packagePriceMillimes: Long?, packageQuantity: BigDecimal, yieldPercent: BigDecimal = HUNDRED): BigDecimal? {
        if (packagePriceMillimes == null) return null
        if (packageQuantity.signum() <= 0) return null
        if (yieldPercent.signum() <= 0 || yieldPercent > HUNDRED) return null
        val usable = packageQuantity.multiply(yieldPercent).divide(HUNDRED)
        return Money.div(BigDecimal.valueOf(packagePriceMillimes), usable)
    }

    fun lineCost(quantityBase: BigDecimal, unitPriceMillimes: BigDecimal): BigDecimal = quantityBase.multiply(unitPriceMillimes)

    /** Coût par part (millimes arrondis) ; null si [portions] ≤ 0. */
    fun costPerPortion(batchExact: BigDecimal, portions: Int): Long? {
        if (portions <= 0) return null
        return Money.roundMillimes(Money.div(batchExact, BigDecimal(portions)))
    }

    /** Prix conseillé = coût ÷ (1 − marge). Null si la marge n'est pas dans [0 ; 100[. */
    fun suggestedPrice(costMillimes: Long, marginPercent: BigDecimal): Long? {
        if (marginPercent.signum() < 0 || marginPercent >= HUNDRED) return null
        val divisor = BigDecimal.ONE.subtract(marginPercent.divide(HUNDRED))
        return Money.roundMillimes(Money.div(BigDecimal.valueOf(costMillimes), divisor))
    }

    /** Marge réelle en % (1 décimale) = (prix − coût) ÷ prix. Null si le prix ≤ 0. */
    fun marginPercent(sellingPriceMillimes: Long, costMillimes: Long): BigDecimal? {
        if (sellingPriceMillimes <= 0) return null
        val diff = BigDecimal.valueOf(sellingPriceMillimes - costMillimes).multiply(HUNDRED)
        return diff.divide(BigDecimal.valueOf(sellingPriceMillimes), 1, Money.ROUNDING)
    }

    /** Bénéfice par part en millimes. */
    fun profitPerPortion(sellingPriceMillimes: Long, costMillimes: Long): Long = sellingPriceMillimes - costMillimes

    /** Main-d'œuvre = (masse salariale mensuelle ÷ heures par mois) × minutes ÷ 60. Null si données absentes. */
    fun laborCost(monthlyPayrollMillimes: Long, hoursPerMonth: Int, minutes: Int): Long? {
        if (monthlyPayrollMillimes <= 0 || hoursPerMonth <= 0 || minutes <= 0) return null
        val perMinute = Money.div(BigDecimal.valueOf(monthlyPayrollMillimes), BigDecimal(hoursPerMonth * 60))
        return Money.roundMillimes(perMinute.multiply(BigDecimal(minutes)))
    }

    /** Part des charges fixes = charges mensuelles ÷ parts produites par mois. 0 si données absentes. */
    fun fixedCostPerPortion(monthlyFixedMillimes: Long, portionsPerMonth: Int): Long {
        if (monthlyFixedMillimes <= 0 || portionsPerMonth <= 0) return 0
        return Money.roundMillimes(Money.div(BigDecimal.valueOf(monthlyFixedMillimes), BigDecimal(portionsPerMonth)))
    }

    // ---------- calcul complet d'une recette ----------

    fun lineCost(line: Line, ingredient: IngredientPrice?): LineCost {
        if (ingredient == null) return LineCost(line, null, null, null, Issue.MISSING_INGREDIENT)
        if (line.quantity.signum() <= 0) return LineCost(line, ingredient, BigDecimal.ZERO, null, Issue.ZERO_QUANTITY)
        val unit = MeasureUnit.fromCode(line.unit)
            ?: return LineCost(line, ingredient, null, null, Issue.UNIT_MISMATCH)
        val qtyBase = UnitConverter.toBase(line.quantity, unit, ingredient.baseUnit, ingredient.packageQuantity)
            ?: return LineCost(line, ingredient, null, null, Issue.UNIT_MISMATCH)
        if (ingredient.packagePriceMillimes == null) return LineCost(line, ingredient, qtyBase, null, Issue.MISSING_PRICE)
        val price = unitPrice(ingredient.packagePriceMillimes, ingredient.packageQuantity, ingredient.yieldPercent)
            ?: return LineCost(line, ingredient, qtyBase, null, Issue.INVALID_PACKAGE)
        return LineCost(line, ingredient, qtyBase, lineCost(qtyBase, price), null)
    }

    fun compute(input: RecipeInput, ingredientsById: Map<Long, IngredientPrice>): RecipeCost {
        val lines = input.lines.map { lineCost(it, ingredientsById[it.ingredientId]) }
        val issues = lines.mapNotNull { it.issue }.toMutableSet()
        val ingredients = lines.mapNotNull { it.costExact }.fold(BigDecimal.ZERO, BigDecimal::add)
        val hiddenBatch = BigDecimal.valueOf(input.hidden.energyPerBatchMillimes + input.hidden.laborPerBatchMillimes)
        val batch = ingredients.add(hiddenBatch)

        if (input.portions <= 0) issues += Issue.ZERO_PORTIONS
        val perPortion = costPerPortion(batch, input.portions)
            ?.plus(input.hidden.packagingPerPortionMillimes + input.hidden.fixedPerPortionMillimes)

        val suggested = perPortion?.let { suggestedPrice(it, input.targetMarginPercent) }
        if (perPortion != null && suggested == null) issues += Issue.INVALID_MARGIN
        val selling = input.sellingPriceMillimes
        val realMargin = if (perPortion != null && selling != null) marginPercent(selling, perPortion) else null

        return RecipeCost(lines, ingredients, hiddenBatch, batch, perPortion, suggested, selling, realMargin, issues)
    }

    /** Prix conseillés pour plusieurs marges (conseiller de marge : « et si je visais X % ? »). */
    fun priceForMargins(costMillimes: Long, margins: List<BigDecimal>): List<Pair<BigDecimal, Long?>> =
        margins.map { it to suggestedPrice(costMillimes, it) }
}

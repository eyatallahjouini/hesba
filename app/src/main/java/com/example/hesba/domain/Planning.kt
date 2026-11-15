package com.example.hesba.domain

import com.example.hesba.CostCalculator
import com.example.hesba.RecipeCosting
import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.PrixHistorique
import com.example.hesba.data.Recette
import com.example.hesba.data.Utilisateur
import java.math.BigDecimal
import java.math.RoundingMode

/** Données d'un utilisateur nécessaires aux calculs (lecture seule). */
data class Workspace(
    val user: Utilisateur?,
    val ingredients: List<Ingredient>,
    val recipes: List<Recette>,
    val lines: List<LigneRecette>
) {
    val ingredientsById: Map<Long, Ingredient> = ingredients.associateBy { it.id }
    val linesByRecipe: Map<Long, List<LigneRecette>> = lines.groupBy { it.recetteId }

    fun cost(recette: Recette, ingredients: Map<Long, Ingredient> = ingredientsById): CostCalculator.RecipeCost =
        RecipeCosting.compute(recette, linesByRecipe[recette.id].orEmpty(), ingredients, user)

    fun recipesUsing(ingredientId: Long): List<Recette> =
        recipes.filter { r -> linesByRecipe[r.id].orEmpty().any { it.ingredientId == ingredientId } }
}

/** Effet d'un changement de prix sur une recette. */
data class RecipeImpact(
    val recette: Recette,
    val before: CostCalculator.RecipeCost,
    val after: CostCalculator.RecipeCost
) {
    val target: BigDecimal get() = BigDecimal(recette.margeVoulue.toString())
    val costDelta: Long? get() = after.costPerPortionMillimes?.let { a -> before.costPerPortionMillimes?.let { a - it } }
    val fallsBelowTarget: Boolean get() = after.isBelowTarget(target) && !before.isBelowTarget(target)
    val belowTarget: Boolean get() = after.isBelowTarget(target)
}

/** Simulateur « et si » : rien n'est enregistré, on calcule seulement. */
object WhatIf {

    sealed class Change {
        data class NewPrice(val millimes: Long) : Change()
        data class Amount(val deltaMillimes: Long) : Change()
        data class Percent(val percent: BigDecimal) : Change()
    }

    /** Nouveau prix de l'emballage (jamais négatif). Null si l'ingrédient n'a pas encore de prix et que le changement est relatif. */
    fun newPackagePrice(current: Long?, change: Change): Long? = when (change) {
        is Change.NewPrice -> change.millimes.coerceAtLeast(0)
        is Change.Amount -> current?.let { (it + change.deltaMillimes).coerceAtLeast(0) }
        is Change.Percent -> current?.let {
            val factor = BigDecimal(100).add(change.percent).divide(BigDecimal(100))
            Money.roundMillimes(BigDecimal.valueOf(it).multiply(factor)).coerceAtLeast(0)
        }
    }

    fun simulate(workspace: Workspace, ingredientId: Long, newPriceMillimes: Long): List<RecipeImpact> {
        val ingredient = workspace.ingredientsById[ingredientId] ?: return emptyList()
        val changed = workspace.ingredientsById + (ingredientId to ingredient.copy(prixMillimes = newPriceMillimes))
        return workspace.recipesUsing(ingredientId).map { r ->
            RecipeImpact(r, workspace.cost(r), workspace.cost(r, changed))
        }
    }
}

/** Liste de courses pour produire une quantité donnée d'une recette. */
object ShoppingList {

    data class Item(
        val ingredient: Ingredient,
        val netBase: BigDecimal,          // quantité utilisée (unité de base)
        val grossBase: BigDecimal,        // quantité à acheter en tenant compte des pertes
        val packages: Int?,               // emballages entiers à acheter
        val costMillimes: Long?,          // coût de la quantité utilisée (pertes comprises)
        val packagesCostMillimes: Long?   // coût si on achète des emballages entiers
    )

    /** [unconvertible] : ingrédients dont l'unité de la ligne ne se convertit pas (ex. œufs en kg) ; absents de la liste et du total. */
    data class Result(
        val items: List<Item>, val totalMillimes: Long, val packagesTotalMillimes: Long, val missingPrices: List<Ingredient>, val factor: BigDecimal,
        val unconvertible: List<Ingredient> = emptyList()
    )

    /** [portions] = nombre de parts à produire. */
    fun build(workspace: Workspace, recette: Recette, portions: Int): Result? {
        if (recette.nombreParts <= 0 || portions <= 0) return null
        val factor = BigDecimal(portions).divide(BigDecimal(recette.nombreParts), Money.INTERNAL_SCALE, RoundingMode.HALF_UP)
        val grouped = linkedMapOf<Long, BigDecimal>()
        val unconvertible = mutableListOf<Ingredient>()
        for (ligne in workspace.linesByRecipe[recette.id].orEmpty()) {
            val ingredient = workspace.ingredientsById[ligne.ingredientId] ?: continue
            val pkg = BigDecimal(ingredient.quantiteEmballage.toString())
            val base = UnitConverter.toBase(BigDecimal(ligne.quantite.toString()), ligne.unite, ingredient.unite, pkg)
            if (base == null) { if (unconvertible.none { it.id == ingredient.id }) unconvertible += ingredient; continue }
            grouped[ingredient.id] = (grouped[ingredient.id] ?: BigDecimal.ZERO).add(base.multiply(factor))
        }
        val missing = mutableListOf<Ingredient>()
        var total = BigDecimal.ZERO
        var totalPackages = 0L
        val items = grouped.map { (id, net) ->
            val ingredient = workspace.ingredientsById.getValue(id)
            val yieldPct = BigDecimal(ingredient.rendementPourcent.toString())
            val gross = if (yieldPct.signum() > 0) Money.div(net.multiply(BigDecimal(100)), yieldPct) else net
            val pkg = BigDecimal(ingredient.quantiteEmballage.toString())
            val packages = if (pkg.signum() > 0) gross.divide(pkg, 0, RoundingMode.CEILING).toInt() else null
            val unitPrice = CostCalculator.unitPrice(ingredient.prixMillimes, pkg, yieldPct)
            val cost = unitPrice?.multiply(net)
            if (ingredient.prixMillimes == null) missing += ingredient
            cost?.let { total = total.add(it) }
            val packagesCost = if (packages != null && ingredient.prixMillimes != null) packages * ingredient.prixMillimes else null
            packagesCost?.let { totalPackages += it }
            Item(ingredient, net, gross, packages, cost?.let { Money.roundMillimes(it) }, packagesCost)
        }
        return Result(items, Money.roundMillimes(total), totalPackages, missing, factor, unconvertible.filter { it.id !in grouped })
    }
}

/** Analyses tirées UNIQUEMENT des données réelles de l'utilisateur (historique des prix). */
object Insights {

    data class PriceChange(val ingredient: Ingredient, val oldPriceMillimes: Long, val newPriceMillimes: Long, val percent: BigDecimal, val date: Long)

    data class AffectedRecipe(val recette: Recette, val oldCost: Long, val newCost: Long) {
        val delta: Long get() = newCost - oldCost
    }

    /** Ancien prix au gramme (ou ml, pièce) comparé au prix actuel, depuis la première modification de la période. */
    fun priceChanges(workspace: Workspace, history: List<PrixHistorique>): List<PriceChange> {
        val byIngredient = history.groupBy { it.ingredientId }
        return byIngredient.mapNotNull { (id, entries) ->
            val ingredient = workspace.ingredientsById[id] ?: return@mapNotNull null
            val first = entries.minByOrNull { it.date } ?: return@mapNotNull null
            val last = entries.maxByOrNull { it.date }!!
            val oldPrice = first.ancienPrixMillimes ?: return@mapNotNull null
            val newPrice = ingredient.prixMillimes ?: return@mapNotNull null
            // comparaison au prix par unité de base (l'emballage a pu changer)
            val oldUnit = CostCalculator.unitPrice(oldPrice, BigDecimal(first.ancienneQuantite.toString())) ?: return@mapNotNull null
            val newUnit = CostCalculator.unitPrice(newPrice, BigDecimal(ingredient.quantiteEmballage.toString())) ?: return@mapNotNull null
            if (oldUnit.signum() == 0) return@mapNotNull null
            val percent = newUnit.subtract(oldUnit).multiply(BigDecimal(100)).divide(oldUnit, 1, RoundingMode.HALF_UP)
            // prix ramené à l'emballage actuel pour un affichage comparable
            val oldComparable = Money.roundMillimes(oldUnit.multiply(BigDecimal(ingredient.quantiteEmballage.toString())))
            PriceChange(ingredient, oldComparable, newPrice, percent, last.date)
        }.sortedByDescending { it.percent }
    }

    /** Recettes dont le coût par part a le plus bougé à cause des changements de prix de la période. */
    fun affectedRecipes(workspace: Workspace, history: List<PrixHistorique>): List<AffectedRecipe> {
        val oldest = history.groupBy { it.ingredientId }.mapValues { (_, e) -> e.minByOrNull { it.date }!! }
        if (oldest.isEmpty()) return emptyList()
        val oldIngredients = workspace.ingredientsById.mapValues { (id, ing) ->
            oldest[id]?.let { h -> ing.copy(prixMillimes = h.ancienPrixMillimes, quantiteEmballage = h.ancienneQuantite) } ?: ing
        }
        return workspace.recipes.mapNotNull { r ->
            if (workspace.linesByRecipe[r.id].orEmpty().none { it.ingredientId in oldest }) return@mapNotNull null
            val now = workspace.cost(r).costPerPortionMillimes ?: return@mapNotNull null
            val before = workspace.cost(r, oldIngredients).costPerPortionMillimes ?: return@mapNotNull null
            AffectedRecipe(r, before, now)
        }.filter { it.delta != 0L }.sortedByDescending { kotlin.math.abs(it.delta) }
    }
}

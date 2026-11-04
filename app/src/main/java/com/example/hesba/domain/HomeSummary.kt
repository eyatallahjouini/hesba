package com.example.hesba.domain

import com.example.hesba.CostCalculator
import com.example.hesba.data.Ingredient
import com.example.hesba.data.PrixHistorique
import com.example.hesba.data.Recette
import java.math.BigDecimal

/** Ce que l'Accueil affiche, calculé uniquement à partir des données réelles de l'utilisateur. */
data class HomeSummary(
    val recipeCount: Int,
    val ingredientCount: Int,
    val alerts: List<Alert>
) {
    /** Nombre de recettes qui demandent une action (marge sous l'objectif, coût incomplet, prix non fixé). */
    val recipesNeedingAttention: Int get() = alerts.filterIsInstance<Alert.RecipeAlert>().map { it.recette.id }.distinct().size

    sealed class Alert {
        abstract val priority: Int

        sealed class RecipeAlert : Alert() { abstract val recette: Recette }

        data class BelowTarget(override val recette: Recette, val margin: BigDecimal, val target: BigDecimal, val cost: Long, val price: Long) : RecipeAlert() {
            override val priority = 0
        }
        data class Incomplete(override val recette: Recette, val missing: List<String>) : RecipeAlert() {
            override val priority = 1
        }
        data class NoSellingPrice(override val recette: Recette, val suggested: Long?) : RecipeAlert() {
            override val priority = 3
        }
        data class PriceChanged(val ingredient: Ingredient, val entry: PrixHistorique, val percent: BigDecimal?) : Alert() {
            override val priority = 2
        }
    }

    companion object {
        const val RECENT_DAYS = 14

        fun build(ws: Workspace, recentHistory: List<PrixHistorique>): HomeSummary {
            val alerts = mutableListOf<Alert>()
            for (r in ws.recipes) {
                val cost = ws.cost(r)
                val target = BigDecimal(r.margeVoulue.toString())
                val missing = cost.lines.filter { it.issue == CostCalculator.Issue.MISSING_PRICE || it.issue == CostCalculator.Issue.MISSING_INGREDIENT }
                if (missing.isNotEmpty()) {
                    alerts += Alert.Incomplete(r, missing.mapNotNull { it.ingredient?.name })
                }
                val margin = cost.realMarginPercent
                if (margin != null && margin < target && cost.costPerPortionMillimes != null && r.prixVenteMillimes != null) {
                    alerts += Alert.BelowTarget(r, margin, target, cost.costPerPortionMillimes, r.prixVenteMillimes)
                } else if (r.prixVenteMillimes == null && missing.isEmpty()) {
                    alerts += Alert.NoSellingPrice(r, cost.suggestedPriceMillimes)
                }
            }
            // dernier changement de prix par ingrédient sur la période
            recentHistory.groupBy { it.ingredientId }.forEach { (id, entries) ->
                val ingredient = ws.ingredientsById[id] ?: return@forEach
                val last = entries.maxByOrNull { it.date } ?: return@forEach
                alerts += Alert.PriceChanged(ingredient, last, percentChange(last))
            }
            alerts.sortWith(compareBy<Alert> { it.priority }.thenByDescending {
                when (it) {
                    is Alert.BelowTarget -> it.target.subtract(it.margin)
                    is Alert.PriceChanged -> it.percent?.abs() ?: BigDecimal.ZERO
                    else -> BigDecimal.ZERO
                }
            })
            return HomeSummary(ws.recipes.size, ws.ingredients.size, alerts)
        }

        /** Variation du prix par unité de base entre l'ancien et le nouveau prix d'une entrée d'historique. */
        fun percentChange(h: PrixHistorique): BigDecimal? {
            val old = CostCalculator.unitPrice(h.ancienPrixMillimes, BigDecimal(h.ancienneQuantite.toString())) ?: return null
            val new = CostCalculator.unitPrice(h.nouveauPrixMillimes, BigDecimal(h.nouvelleQuantite.toString())) ?: return null
            if (old.signum() == 0) return null
            return new.subtract(old).multiply(BigDecimal(100)).divide(old, 1, java.math.RoundingMode.HALF_UP)
        }
    }
}

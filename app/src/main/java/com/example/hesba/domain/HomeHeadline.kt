package com.example.hesba.domain

import com.example.hesba.domain.HomeSummary.Alert

/**
 * La phrase d'ouverture de l'Accueil, construite uniquement à partir des données réelles
 * (aucun chiffre de vente, aucun bilan). L'écran la traduit avec des ressources « plurals ».
 */
object HomeHeadline {

    sealed class Lead {
        /** Aucun ingrédient : commencer par là. */
        object FirstIngredient : Lead()
        /** Des ingrédients mais aucune recette. */
        object FirstRecipe : Lead()
        /** [count] recettes demandent une action (marge sous l'objectif, coût incomplet, prix non fixé). */
        data class Attention(val count: Int) : Lead()
        /** Toutes les [recipes] recettes sont au niveau voulu. */
        data class AllGood(val recipes: Int) : Lead()
    }

    /** Détail de la phrase : chaque compteur n'est affiché que s'il est > 0. */
    data class Facts(val belowTarget: Int, val incomplete: Int, val noPrice: Int, val priceChanges: Int) {
        val isEmpty: Boolean get() = belowTarget + incomplete + noPrice + priceChanges == 0
    }

    fun lead(s: HomeSummary): Lead = when {
        s.ingredientCount == 0 -> Lead.FirstIngredient
        s.recipeCount == 0 -> Lead.FirstRecipe
        s.recipesNeedingAttention > 0 -> Lead.Attention(s.recipesNeedingAttention)
        else -> Lead.AllGood(s.recipeCount)
    }

    fun facts(s: HomeSummary): Facts {
        fun <T : Alert.RecipeAlert> recipes(type: Class<T>) = s.alerts.filter { type.isInstance(it) }.map { (it as Alert.RecipeAlert).recette.id }.distinct().size
        return Facts(
            belowTarget = recipes(Alert.BelowTarget::class.java),
            incomplete = recipes(Alert.Incomplete::class.java),
            noPrice = recipes(Alert.NoSellingPrice::class.java),
            priceChanges = s.alerts.filterIsInstance<Alert.PriceChanged>().map { it.ingredient.id }.distinct().size
        )
    }
}

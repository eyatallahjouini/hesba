package com.example.hesba.domain

import com.example.hesba.CostCalculator
import com.example.hesba.data.Recette

/** Recherche et tri de « Mes recettes » (sans accents ni casse, arabe normalisé). */
object RecipeSearch {

    enum class Sort { NAME, COST, MARGIN }

    data class Item(val recette: Recette, val cost: CostCalculator.RecipeCost)

    /**
     * Tous les mots tapés doivent se trouver dans le nom (dans n'importe quel ordre).
     * Tri : nom A→Z ; coût par part du plus cher au moins cher ; marge de la plus faible à la plus forte
     * (les recettes sans prix de vente vont à la fin).
     */
    fun apply(items: List<Item>, query: String, sort: Sort): List<Item> {
        val words = TextNormalizer.tokens(query)
        val filtered = if (words.isEmpty()) items else items.filter { item ->
            val name = TextNormalizer.normalize(item.recette.nom)
            words.all { name.contains(it) }
        }
        val byName = compareBy<Item, String>(java.text.Collator.getInstance()) { TextNormalizer.normalize(it.recette.nom) }
        return when (sort) {
            Sort.NAME -> filtered.sortedWith(byName)
            Sort.COST -> filtered.sortedWith(compareByDescending<Item> { it.cost.costPerPortionMillimes ?: -1 }.then(byName))
            Sort.MARGIN -> filtered.sortedWith(
                compareBy<Item> { it.cost.realMarginPercent == null }
                    .thenBy { it.cost.realMarginPercent }
                    .then(byName)
            )
        }
    }
}

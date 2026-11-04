package com.example.hesba.domain

import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.PrixHistorique
import com.example.hesba.data.Recette
import com.example.hesba.domain.RecipeSearch.Sort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeSearchTest {

    private val pain = Ingredient(id = 1, nom = "Pain", unite = "piece", quantiteEmballage = 1.0, prixMillimes = 250)
    private val recipes = listOf(
        Recette(id = 1, nom = "Crème brûlée", nombreParts = 1, margeVoulue = 40.0, prixVenteMillimes = 1000),
        Recette(id = 2, nom = "Bambalouni", nombreParts = 1, margeVoulue = 40.0, prixVenteMillimes = 300),
        Recette(id = 3, nom = "بريك بالتن", nombreParts = 1, margeVoulue = 40.0),
        Recette(id = 4, nom = "Tarte aux pommes", nombreParts = 1, margeVoulue = 40.0, prixVenteMillimes = 500)
    )
    private val lines = recipes.map { LigneRecette(recetteId = it.id, ingredientId = 1, quantite = it.id.toDouble(), unite = "piece") }
    private val ws = Workspace(null, listOf(pain), recipes, lines)
    private val items = recipes.map { RecipeSearch.Item(it, ws.cost(it)) }

    private fun names(q: String, s: Sort = Sort.NAME) = RecipeSearch.apply(items, q, s).map { it.recette.id }

    @Test
    fun rechercheSansAccentNiCasse() {
        assertEquals(listOf(1L), names("CREME"))
        assertEquals(listOf(1L), names("brulee creme"))
        assertEquals(listOf(4L), names("  pom "))
        assertTrue(names("pizza").isEmpty())
        assertEquals(4, names("").size)
    }

    @Test
    fun rechercheArabe() {
        assertEquals(listOf(3L), names("بريك"))
        assertEquals(listOf(3L), names("التن"))
    }

    @Test
    fun tris() {
        // coût : 4 × 250 = 1000 > 3 × 250 > …
        assertEquals(listOf(4L, 3L, 2L, 1L), names("", Sort.COST))
        // tarte : (500 − 1000) ÷ 500 = −100 % ; bambalouni : (300 − 500) ÷ 300 = −66,7 % ; crème : (1000 − 250) ÷ 1000 = 75 %
        assertEquals(listOf(4L, 2L, 1L, 3L), names("", Sort.MARGIN))   // sans prix de vente à la fin
    }

    @Test
    fun accueilAlertesEtChiffres() {
        val history = listOf(PrixHistorique(ingredientId = 1, ancienPrixMillimes = 200, nouveauPrixMillimes = 250, ancienneQuantite = 1.0, nouvelleQuantite = 1.0, source = "manuel", date = 1))
        val s = HomeSummary.build(ws, history)
        assertEquals(4, s.recipeCount)
        assertEquals(1, s.ingredientCount)
        // 2 recettes sous la marge de 40 % (bambalouni, tarte) + 1 sans prix de vente (brik) = 3 à surveiller
        assertEquals(3, s.recipesNeedingAttention)
        assertTrue(s.alerts.first() is HomeSummary.Alert.BelowTarget)
        assertTrue(s.alerts.any { it is HomeSummary.Alert.PriceChanged })
    }
}

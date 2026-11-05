package com.example.hesba.domain

import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.PrixHistorique
import com.example.hesba.data.Recette
import com.example.hesba.domain.HomeHeadline.Lead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La phrase d'ouverture de l'Accueil suit les données réelles. */
class HomeHeadlineTest {

    private val farine = Ingredient(id = 1, nom = "Farine", unite = "g", quantiteEmballage = 1000.0, prixMillimes = 1000)
    private val pistache = Ingredient(id = 2, nom = "Pistache", unite = "g", quantiteEmballage = 1000.0, prixMillimes = null)

    private fun summary(ingredients: List<Ingredient>, recipes: List<Recette>, lines: List<LigneRecette>, history: List<PrixHistorique> = emptyList()) =
        HomeSummary.build(Workspace(null, ingredients, recipes, lines), history)

    @Test
    fun baseVide_commencerParUnIngredient() {
        assertEquals(Lead.FirstIngredient, HomeHeadline.lead(summary(emptyList(), emptyList(), emptyList())))
    }

    @Test
    fun ingredientsSansRecette_composerLaPremiere() {
        assertEquals(Lead.FirstRecipe, HomeHeadline.lead(summary(listOf(farine), emptyList(), emptyList())))
    }

    @Test
    fun recettesSousObjectifEtIncompletes_compteesUneFoisChacune() {
        val r1 = Recette(id = 1, nom = "Pain", nombreParts = 10, margeVoulue = 60.0, prixVenteMillimes = 120)  // coût 100/part → marge 16,7 %
        val r2 = Recette(id = 2, nom = "Baklawa", nombreParts = 10, margeVoulue = 60.0, prixVenteMillimes = 900) // prix manquant
        val r3 = Recette(id = 3, nom = "Galette", nombreParts = 10, margeVoulue = 50.0, prixVenteMillimes = 1000) // coût 100 → 90 %
        val lines = listOf(
            LigneRecette(recetteId = 1, ingredientId = 1, quantite = 1000.0, unite = "g"),
            LigneRecette(recetteId = 2, ingredientId = 2, quantite = 100.0, unite = "g"),
            LigneRecette(recetteId = 3, ingredientId = 1, quantite = 1000.0, unite = "g")
        )
        val s = summary(listOf(farine, pistache), listOf(r1, r2, r3), lines)
        assertEquals(Lead.Attention(2), HomeHeadline.lead(s))
        val f = HomeHeadline.facts(s)
        assertEquals(1, f.belowTarget)
        assertEquals(1, f.incomplete)
    }

    @Test
    fun toutAuNiveau_phrasePositiveAvecLeNombreDeRecettes() {
        val r = Recette(id = 1, nom = "Galette", nombreParts = 10, margeVoulue = 50.0, prixVenteMillimes = 1000)
        val s = summary(listOf(farine), listOf(r), listOf(LigneRecette(recetteId = 1, ingredientId = 1, quantite = 1000.0, unite = "g")))
        assertEquals(Lead.AllGood(1), HomeHeadline.lead(s))
        assertTrue(HomeHeadline.facts(s).isEmpty)
    }
}

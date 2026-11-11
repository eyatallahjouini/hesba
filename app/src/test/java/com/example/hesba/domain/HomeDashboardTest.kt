package com.example.hesba.domain

import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.Recette
import com.example.hesba.domain.Greeting.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/** Cartes de l'Accueil et salutation selon l'heure. */
class HomeDashboardTest {

    private val farine = Ingredient(id = 1, nom = "Farine", unite = "g", quantiteEmballage = 1000.0, prixMillimes = 1000)
    private val pistache = Ingredient(id = 2, nom = "Pistache", unite = "g", quantiteEmballage = 1000.0, prixMillimes = null)

    private fun dashboard(recipes: List<Recette>, lines: List<LigneRecette>): HomeDashboard {
        val ws = Workspace(null, listOf(farine, pistache), recipes, lines)
        return HomeDashboard.build(ws, HomeSummary.build(ws, emptyList()))
    }

    private fun line(recipe: Long, ingredient: Long = 1) =
        LigneRecette(recetteId = recipe, ingredientId = ingredient, quantite = 1000.0, unite = "g")

    @Test
    fun moyenneEtMeilleureMarge_seulementLesRecettesAvecUneMargeConnue() {
        // coût 100 millimes par part dans les trois recettes chiffrées
        val pain = Recette(id = 1, nom = "Pain", nombreParts = 10, margeVoulue = 60.0, prixVenteMillimes = 200)      // 50 %
        val galette = Recette(id = 2, nom = "Galette", nombreParts = 10, margeVoulue = 50.0, prixVenteMillimes = 1000) // 90 %
        val sansPrix = Recette(id = 3, nom = "Brioche", nombreParts = 10, margeVoulue = 60.0, prixVenteMillimes = null)
        val incomplete = Recette(id = 4, nom = "Baklawa", nombreParts = 10, margeVoulue = 60.0, prixVenteMillimes = 900)
        val d = dashboard(listOf(pain, galette, sansPrix, incomplete), listOf(line(1), line(2), line(3), line(4, ingredient = 2)))

        assertEquals(2, d.pricedCount)
        assertEquals(BigDecimal("70.0"), d.averageMargin)
        assertEquals(BigDecimal("55.0"), d.averageTarget)
        assertEquals(1, d.belowTargetCount)
        assertEquals("Galette", d.best?.recette?.nom)
        assertEquals(1000L, d.best?.priceMillimes)
        assertEquals(100L, d.best?.costMillimes)
    }

    @Test
    fun aucunPrixDeVente_pasDeMoyenne() {
        val d = dashboard(listOf(Recette(id = 1, nom = "Pain", nombreParts = 10, margeVoulue = 60.0)), listOf(line(1)))
        assertNull(d.averageMargin)
        assertNull(d.best)
        assertEquals(0, d.pricedCount)
    }

    @Test
    fun salutation_selonLHeure() {
        assertEquals(Period.EVENING, Greeting.period(4))
        assertEquals(Period.MORNING, Greeting.period(5))
        assertEquals(Period.MORNING, Greeting.period(11))
        assertEquals(Period.AFTERNOON, Greeting.period(12))
        assertEquals(Period.AFTERNOON, Greeting.period(17))
        assertEquals(Period.EVENING, Greeting.period(18))
        assertEquals(Period.EVENING, Greeting.period(23))
        assertEquals(Period.EVENING, Greeting.period(0))
    }
}

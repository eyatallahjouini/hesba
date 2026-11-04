package com.example.hesba

import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.Recette
import com.example.hesba.data.Utilisateur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeCostingTest {

    private val recette = Recette(id = 1, utilisateurId = 1, nom = "Sandwich", nombreParts = 10, margeVoulue = 40.0)
    private val lignes = listOf(
        LigneRecette(recetteId = 1, ingredientId = 1, quantite = 50.0, unite = "g"),
        LigneRecette(recetteId = 1, ingredientId = 2, quantite = 10.0, unite = "piece")
    )
    private val harissa = Ingredient(id = 1, nom = "Harissa", unite = "g", quantiteEmballage = 1000.0, prixMillimes = 6000)
    private val pain = Ingredient(id = 2, nom = "Pain", unite = "piece", quantiteEmballage = 1.0, prixMillimes = 250)

    @Test
    fun coutAvecLesPrixActuels() {
        val r = RecipeCosting.compute(recette, lignes, mapOf(1L to harissa, 2L to pain))
        assertEquals(280L, r.costPerPortionMillimes)
        assertEquals(467L, r.suggestedPriceMillimes)
    }

    @Test
    fun leCoutSuitLeNouveauPrix() {
        val r = RecipeCosting.compute(recette, lignes, mapOf(1L to harissa, 2L to pain.copy(prixMillimes = 300)))
        assertEquals(330L, r.costPerPortionMillimes)
        assertEquals(550L, r.suggestedPriceMillimes)
    }

    @Test
    fun rendementParDefautNeChangeRien() {
        // après migration le rendement vaut 100 % : mêmes résultats qu'avant
        val avec = RecipeCosting.compute(recette, lignes, mapOf(1L to harissa.copy(rendementPourcent = 100.0), 2L to pain))
        assertEquals(280L, avec.costPerPortionMillimes)
        // 50 % de rendement sur la harissa : 50 g × 12 = 600 → lot 3100 → 310
        val perte = RecipeCosting.compute(recette, lignes, mapOf(1L to harissa.copy(rendementPourcent = 50.0), 2L to pain))
        assertEquals(310L, perte.costPerPortionMillimes)
    }

    @Test
    fun ingredientIntrouvableNeCompteRienEtEstSignale() {
        val r = RecipeCosting.compute(recette, lignes, mapOf(2L to pain))
        assertEquals(250L, r.costPerPortionMillimes)
        assertEquals(setOf(CostCalculator.Issue.MISSING_INGREDIENT), r.issues)
    }

    @Test
    fun chargesDuProfil() {
        val user = Utilisateur(
            prenom = "A", email = "a@b.tn", motDePasseHash = "x", activite = "sans_employe",
            loyerMensuelMillimes = 900_000, volumeMensuel = 1000, salaireMoyenMillimes = 1_200_000, heuresTravailMois = 200
        )
        assertEquals(900L, RecipeCosting.fixedPerPortion(user))
        assertEquals(6000L, RecipeCosting.laborEstimate(user, 60))   // 1 200 000 ÷ 12 000 min × 60 min
        val r = RecipeCosting.compute(recette, lignes, mapOf(1L to harissa, 2L to pain), user)
        assertEquals(1180L, r.costPerPortionMillimes)               // 280 + 900 de loyer par part
        assertNull(RecipeCosting.laborEstimate(user.copy(heuresTravailMois = 0), 60))
    }
}

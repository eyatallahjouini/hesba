package com.example.hesba

import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.Recette
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeCostingTest {

    private val delta = 0.0001

    private val recette = Recette(id = 1, utilisateurId = 1, nom = "Sandwich", nombreParts = 10, margeVoulue = 40.0)
    private val lignes = listOf(
        LigneRecette(recetteId = 1, ingredientId = 1, quantite = 50.0, unite = "g"),
        LigneRecette(recetteId = 1, ingredientId = 2, quantite = 10.0, unite = "piece")
    )
    private val harissa = Ingredient(id = 1, nom = "Harissa", unite = "g", quantiteEmballage = 1000.0, prixPaye = 6.0)
    private val pain = Ingredient(id = 2, nom = "Pain", unite = "piece", quantiteEmballage = 1.0, prixPaye = 0.25)

    @Test
    fun coutAvecLesPrixActuels() {
        // lot = 50 g × 0,006 + 10 × 0,25 = 2,80 dt -> 0,28 dt par pièce
        val r = RecipeCosting.compute(recette, lignes, mapOf(1L to harissa, 2L to pain))
        assertEquals(0.28, r.costPerPiece, delta)
        assertEquals(0.4667, r.advisedPrice, delta)
    }

    @Test
    fun leCoutSuitLeNouveauPrix() {
        // le pain passe de 0,25 à 0,30 dt : lot = 0,30 + 3,00 = 3,30 dt -> 0,33 dt par pièce
        val painPlusCher = pain.copy(prixPaye = 0.30)
        val r = RecipeCosting.compute(recette, lignes, mapOf(1L to harissa, 2L to painPlusCher))
        assertEquals(0.33, r.costPerPiece, delta)
        assertEquals(0.55, r.advisedPrice, delta)
    }

    @Test
    fun ingredientIntrouvableCompteZero() {
        val r = RecipeCosting.compute(recette, lignes, mapOf(2L to pain))
        assertEquals(0.25, r.costPerPiece, delta)
    }
}

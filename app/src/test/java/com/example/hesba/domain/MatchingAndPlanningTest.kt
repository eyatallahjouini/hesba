package com.example.hesba.domain

import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.PrixHistorique
import com.example.hesba.data.Recette
import com.example.hesba.domain.invoice.IngredientMatcher
import com.example.hesba.domain.invoice.MatchCandidate
import com.example.hesba.domain.invoice.MatchConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class MatchingAndPlanningTest {

    private val candidates = listOf(
        MatchCandidate(1, "Farine"),
        MatchCandidate(2, "Sucre"),
        MatchCandidate(3, "Crème fraîche"),
        MatchCandidate(4, "Lait"),
        MatchCandidate(5, "Huile d'olive"),
        MatchCandidate(6, "Huile de tournesol"),
        MatchCandidate(7, "سكر")
    )

    private fun matcher(synonyms: List<Pair<String, Long>> = emptyList()) = IngredientMatcher(candidates, synonyms)

    @Test
    fun correspondanceExacteSansAccents() {
        val m = matcher().match("CREME FRAICHE")
        assertEquals(3L, m.ingredientId)
        assertEquals(MatchConfidence.EXACT, m.confidence)
    }

    @Test
    fun nomDeFactureAvecMotsEnPlus() {
        val m = matcher().match("Farine fleur sac 25kg")
        assertEquals(1L, m.ingredientId)
        assertTrue(m.confidence == MatchConfidence.HIGH || m.confidence == MatchConfidence.EXACT)
    }

    @Test
    fun fauteDeFrappeOcr() {
        val m = matcher().match("Sucr3")
        assertEquals(2L, m.ingredientId)
        assertTrue(m.confidence != MatchConfidence.NONE)
    }

    @Test
    fun deuxHuilesNeSontPasConfondues() {
        assertEquals(5L, matcher().match("Huile olive extra").ingredientId)
        assertEquals(6L, matcher().match("huile tournesol 5L").ingredientId)
    }

    @Test
    fun arabeAvecArticleEtHamza() {
        assertEquals(7L, matcher().match("السكر").ingredientId)
        assertEquals(7L, matcher().match("سُكَّر أبيض").ingredientId)
    }

    @Test
    fun synonymeAppris() {
        // l'utilisateur a déjà relié « فارينة » à Farine : la prochaine fois c'est automatique
        val m = matcher(listOf(TextNormalizer.normalize("فارينة") to 1L)).match("فارينة")
        assertEquals(1L, m.ingredientId)
        assertEquals(MatchConfidence.EXACT, m.confidence)
    }

    @Test
    fun aucunIngredientProcheNEstPasForce() {
        val m = matcher().match("Pistaches grillées")
        assertNull(m.ingredientId)
        assertEquals(MatchConfidence.NONE, m.confidence)
    }

    // ---------- simulateur, liste de courses, analyses ----------

    private val farine = Ingredient(id = 1, nom = "Farine", unite = "g", quantiteEmballage = 1000.0, prixMillimes = 1200)
    private val beurre = Ingredient(id = 2, nom = "Beurre", unite = "g", quantiteEmballage = 250.0, prixMillimes = 3500, rendementPourcent = 100.0)
    private val oeufs = Ingredient(id = 3, nom = "Oeufs", unite = "piece", quantiteEmballage = 30.0, prixMillimes = 12000)
    private val pain = Ingredient(id = 4, nom = "Pain", unite = "piece", quantiteEmballage = 1.0, prixMillimes = 250)
    private val gateau = Recette(id = 10, utilisateurId = 1, nom = "Gâteau", nombreParts = 8, margeVoulue = 40.0, prixVenteMillimes = 1500)
    private val sandwich = Recette(id = 11, utilisateurId = 1, nom = "Sandwich", nombreParts = 1, margeVoulue = 50.0, prixVenteMillimes = 1000)
    private val lignes = listOf(
        LigneRecette(recetteId = 10, ingredientId = 1, quantite = 500.0, unite = "g"),
        LigneRecette(recetteId = 10, ingredientId = 2, quantite = 0.25, unite = "kg"),
        LigneRecette(recetteId = 10, ingredientId = 3, quantite = 4.0, unite = "piece"),
        LigneRecette(recetteId = 11, ingredientId = 4, quantite = 1.0, unite = "piece")
    )
    private val ws = Workspace(null, listOf(farine, beurre, oeufs, pain), listOf(gateau, sandwich), lignes)

    @Test
    fun simulateurNeTouchePasAuxAutresRecettes() {
        // gâteau : 600 + 3500 + 1600 = 5700 ÷ 8 = 712,5 → 713 ; prix pratiqué 1,500 → marge 52,5 %
        val beurrePlusCher = WhatIf.newPackagePrice(3500, WhatIf.Change.Percent(BigDecimal("50")))!!
        assertEquals(5250L, beurrePlusCher)
        val impacts = WhatIf.simulate(ws, 2, beurrePlusCher)
        assertEquals(listOf("Gâteau"), impacts.map { it.recette.nom })
        val g = impacts[0]
        assertEquals(713L, g.before.costPerPortionMillimes)
        assertEquals(931L, g.after.costPerPortionMillimes)      // 7450 ÷ 8 = 931,25
        assertEquals(218L, g.costDelta)
        assertEquals(0, BigDecimal("37.9").compareTo(g.after.realMarginPercent))
        assertTrue(g.fallsBelowTarget)
        // les données d'origine sont intactes
        assertEquals(3500L, ws.ingredientsById.getValue(2).prixMillimes)
    }

    @Test
    fun changementsDePrixRelatifs() {
        assertEquals(3000L, WhatIf.newPackagePrice(3500, WhatIf.Change.Amount(-500)))
        assertEquals(0L, WhatIf.newPackagePrice(300, WhatIf.Change.Amount(-500)))
        assertEquals(4000L, WhatIf.newPackagePrice(null, WhatIf.Change.NewPrice(4000)))
        assertNull(WhatIf.newPackagePrice(null, WhatIf.Change.Percent(BigDecimal("10"))))
    }

    @Test
    fun listeDeCourses() {
        // 24 parts = 3 lots : 1500 g de farine, 750 g de beurre, 12 œufs
        val list = ShoppingList.build(ws, gateau, 24)!!
        val farineItem = list.items.first { it.ingredient.id == 1L }
        assertEquals(0, BigDecimal("1500").compareTo(farineItem.netBase))
        assertEquals(2, farineItem.packages)
        assertEquals(1800L, farineItem.costMillimes)
        assertEquals(2400L, farineItem.packagesCostMillimes)
        val beurreItem = list.items.first { it.ingredient.id == 2L }
        assertEquals(3, beurreItem.packages)
        val oeufItem = list.items.first { it.ingredient.id == 3L }
        assertEquals(1, oeufItem.packages)
        assertEquals(17100L, list.totalMillimes)                 // 5700 × 3
        assertNull(ShoppingList.build(ws, gateau, 0))
    }

    @Test
    fun listeDeCoursesAvecPertes() {
        val ws2 = ws.copy(ingredients = listOf(farine.copy(rendementPourcent = 80.0), beurre, oeufs, pain))
        val item = ShoppingList.build(ws2, gateau, 8)!!.items.first { it.ingredient.id == 1L }
        assertEquals(0, BigDecimal("625").compareTo(item.grossBase))   // 500 g utilisables = 625 g achetés
        assertEquals(750L, item.costMillimes)
    }

    @Test
    fun analysesDepuisLHistorique() {
        val history = listOf(
            PrixHistorique(ingredientId = 2, ancienPrixMillimes = 3000, nouveauPrixMillimes = 3200, ancienneQuantite = 250.0, nouvelleQuantite = 250.0, source = "manuel", date = 1000),
            PrixHistorique(ingredientId = 2, ancienPrixMillimes = 3200, nouveauPrixMillimes = 3500, ancienneQuantite = 250.0, nouvelleQuantite = 250.0, source = "facture", date = 2000),
            PrixHistorique(ingredientId = 4, ancienPrixMillimes = 200, nouveauPrixMillimes = 250, ancienneQuantite = 1.0, nouvelleQuantite = 1.0, source = "manuel", date = 1500)
        )
        val changes = Insights.priceChanges(ws, history)
        assertEquals(listOf(4L, 2L), changes.map { it.ingredient.id })   // pain +25 %, beurre +16,7 %
        assertEquals(0, BigDecimal("25.0").compareTo(changes[0].percent))
        assertEquals(0, BigDecimal("16.7").compareTo(changes[1].percent))

        val affected = Insights.affectedRecipes(ws, history)
        // gâteau : beurre 3000 → 3500 : +500 sur le lot ÷ 8 = 62,5 → 713 − 650 = 63 ; sandwich +50
        assertEquals(listOf("Gâteau", "Sandwich"), affected.map { it.recette.nom })
        assertEquals(63L, affected[0].delta)
        assertEquals(50L, affected[1].delta)
    }
}

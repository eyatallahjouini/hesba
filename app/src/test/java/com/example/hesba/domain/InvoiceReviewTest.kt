package com.example.hesba.domain

import com.example.hesba.data.Ingredient
import com.example.hesba.domain.invoice.IngredientMatcher
import com.example.hesba.domain.invoice.InvoiceParser
import com.example.hesba.domain.invoice.InvoiceReview
import com.example.hesba.domain.invoice.InvoiceReview.Action
import com.example.hesba.domain.invoice.InvoiceReview.Problem
import com.example.hesba.domain.invoice.MatchCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class InvoiceReviewTest {

    private val farine = Ingredient(id = 1, nom = "Farine", unite = "g", quantiteEmballage = 1000.0, prixMillimes = 1200)
    private val sucre = Ingredient(id = 2, nom = "Sucre", unite = "g", quantiteEmballage = 1000.0, prixMillimes = 1700)
    private val lait = Ingredient(id = 3, nom = "Lait", unite = "ml", quantiteEmballage = 1000.0, prixMillimes = 1300)
    private val ingredients = listOf(farine, sucre, lait).associateBy { it.id }
    private val matcher = IngredientMatcher(ingredients.values.map { MatchCandidate(it.id, it.nom) }, emptyList())
    private val noTax = InvoiceReview.Options(null, false, null, false)

    private fun parse(vararg lines: String) = InvoiceParser.parse(lines.toList())

    @Test
    fun ancienEtNouveauPrixRamenesALEmballageDeLIngredient() {
        val inv = parse("Farine sac 25 kg 2 32,000 64,000", "Sucre 10 kg 1,850 18,500", "Lait 1L 12 1,350 16,200")
        val lines = InvoiceReview.initial(inv, matcher, ingredients, noTax)
        assertTrue(lines.all { it.action == Action.ACCEPT })
        val f = InvoiceReview.evaluate(lines[0], farine, noTax)
        assertEquals(1200L, f.oldPriceMillimes)
        assertEquals(1280L, f.newPriceMillimes)          // 32,000 ÷ 25 kg = 1,280 le kg
        assertEquals(80L, f.diff)
        assertEquals(0, BigDecimal("6.7").compareTo(f.percent))
        assertEquals(1850L, InvoiceReview.evaluate(lines[1], sucre, noTax).newPriceMillimes)
        assertEquals(1350L, InvoiceReview.evaluate(lines[2], lait, noTax).newPriceMillimes)
    }

    @Test
    fun tvaAjouteeSeulementSiDemandee() {
        val inv = parse("Sucre 10 kg 1,850 18,500")
        val line = InvoiceReview.initial(inv, matcher, ingredients, noTax)[0]
        val withTax = InvoiceReview.Options(BigDecimal("19"), true, null, false)
        assertEquals(2202L, InvoiceReview.evaluate(line, sucre, withTax).newPriceMillimes)   // 1850 × 1,19 = 2201,5 → 2202
    }

    @Test
    fun prixIncertainNEstPasAccepteParDefaut() {
        val inv = parse("Farine 0,800")
        val line = InvoiceReview.initial(inv, matcher, ingredients, noTax)[0]
        assertEquals(1L, line.ingredientId)
        assertEquals(Action.IGNORE, line.action)                     // l'utilisateur doit vérifier
    }

    @Test
    fun ingredientInconnuOuUniteIncompatible() {
        val inv = parse("Pistaches 2 kg 45,000 90,000", "Lait 2 kg 1,000 2,000")
        val lines = InvoiceReview.initial(inv, matcher, ingredients, noTax)
        assertNull(lines[0].ingredientId)
        // ingrédient inconnu, unité et prix sûrs : proposé à la création et coché d'office
        assertTrue(lines[0].createNew)
        assertEquals(Action.ACCEPT, lines[0].action)
        assertEquals(Problem.NO_INGREDIENT, InvoiceReview.evaluate(lines[0].copy(createNew = false), null, noTax).problem)
        // le lait est en ml : « kg » ne se convertit pas
        assertEquals(Problem.UNIT_MISMATCH, InvoiceReview.evaluate(lines[1], lait, noTax).problem)
        assertEquals(Action.IGNORE, lines[1].action)
    }

    @Test
    fun nouvelIngredientDepuisLaFacture() {
        val inv = parse("Pistaches 2 kg 45,000 90,000")
        val line = InvoiceReview.initial(inv, matcher, ingredients, noTax)[0].copy(createNew = true, action = Action.ACCEPT)
        val virtual = InvoiceReview.newIngredientFor(line)!!
        assertEquals("g", virtual.unite)
        assertEquals(1000.0, virtual.quantiteEmballage, 0.0)
        val ev = InvoiceReview.evaluate(line, null, noTax)
        assertNull(ev.oldPriceMillimes)
        assertEquals(45000L, ev.newPriceMillimes)
    }

    @Test
    fun nouvelIngredientIncertainOuSansUniteNonCoche() {
        // un seul montant : prix incertain → proposé à la création, mais pas coché
        val incertain = InvoiceReview.initial(parse("Cannelle 100 g 0,800"), matcher, ingredients, noTax)[0]
        assertNull(incertain.ingredientId)
        assertTrue(incertain.createNew)
        assertEquals(Action.IGNORE, incertain.action)
        // emballage sans contenu connu : impossible de le ranger en g/ml/pièce → pas de création proposée
        val sansUnite = InvoiceReview.initial(parse("Vanille 2 bte 1,500 3,000"), matcher, ingredients, noTax)[0]
        assertEquals(false, sansUnite.createNew)
        assertEquals(Action.IGNORE, sansUnite.action)
    }

    @Test
    fun prixOuQuantiteNulsRefuses() {
        val line = InvoiceReview.initial(parse("Sucre 10 kg 1,850 18,500"), matcher, ingredients, noTax)[0]
        assertEquals(Problem.INVALID_PRICE, InvoiceReview.evaluate(line.copy(unitPriceMillimes = 0), sucre, noTax).problem)
        assertEquals(Problem.INVALID_QUANTITY, InvoiceReview.evaluate(line.copy(quantity = BigDecimal.ZERO), sucre, noTax).problem)
        assertEquals(Problem.INVALID_QUANTITY, InvoiceReview.evaluate(line.copy(quantity = BigDecimal("-2")), sucre, noTax).problem)
        // quantité absente : acceptée (le prix suffit)
        assertNull(InvoiceReview.evaluate(line.copy(quantity = null), sucre, noTax).problem)
    }

    @Test
    fun nomObligatoirePourUnNouvelIngredient() {
        val line = InvoiceReview.initial(parse("Pistaches 2 kg 45,000 90,000"), matcher, ingredients, noTax)[0]
        assertEquals(Problem.NO_NAME, InvoiceReview.evaluate(line.copy(name = "  "), null, noTax).problem)
    }

    @Test
    fun raisonDesLignesIgnorees() {
        val inv = parse("Farine sac 25 kg 2 32,000 64,000", "Lait 2 kg 1,000 2,000", "Sucre 10 kg 1,850 18,500")
        val lines = InvoiceReview.initial(inv, matcher, ingredients, noTax)
        val ignoredByUser = lines[2].copy(action = Action.IGNORE)
        assertNull(InvoiceReview.skipReason(lines[0], farine, noTax, emptySet()))                       // sera enregistrée
        assertEquals(InvoiceReview.Skip.UNIT_MISMATCH, InvoiceReview.skipReason(lines[1], lait, noTax, emptySet()))
        assertEquals(InvoiceReview.Skip.IGNORED, InvoiceReview.skipReason(ignoredByUser, sucre, noTax, emptySet()))
        assertEquals(InvoiceReview.Skip.DUPLICATE, InvoiceReview.skipReason(lines[0], farine, noTax, setOf(0)))
        assertEquals(InvoiceReview.Skip.NO_PRICE, InvoiceReview.skipReason(lines[0].copy(unitPriceMillimes = null), farine, noTax, emptySet()))
    }

    @Test
    fun doublonsDetectes() {
        val inv = parse("Farine 10 kg 1,200 12,000", "Farine T55 5 kg 1,250 6,250")
        val lines = InvoiceReview.initial(inv, matcher, ingredients, noTax)
        // la 2e ligne vise aussi Farine : elle n'est pas acceptée automatiquement
        assertEquals(Action.ACCEPT, lines[0].action)
        assertEquals(Action.IGNORE, lines[1].action)
        val bothAccepted = lines.map { it.copy(action = Action.ACCEPT) }
        assertEquals(setOf(1), InvoiceReview.duplicates(bothAccepted))
    }
}

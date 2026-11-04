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
        assertEquals(Problem.NO_INGREDIENT, InvoiceReview.evaluate(lines[0], null, noTax).problem)
        assertEquals(Action.IGNORE, lines[0].action)
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

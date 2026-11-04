package com.example.hesba

import com.example.hesba.CostCalculator.HiddenCosts
import com.example.hesba.CostCalculator.IngredientPrice
import com.example.hesba.CostCalculator.Issue
import com.example.hesba.CostCalculator.Line
import com.example.hesba.CostCalculator.RecipeInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CostCalculatorTest {

    private fun bd(s: String) = BigDecimal(s)

    private val harissa = IngredientPrice(1, "Harissa", "g", bd("1000"), 6000)      // 6,000 DT le kg
    private val pain = IngredientPrice(2, "Pain", "piece", bd("1"), 250)             // 0,250 DT la pièce
    private val prices = mapOf(1L to harissa, 2L to pain)

    @Test
    fun prixParUniteSansArrondiIntermediaire() {
        assertEquals(0, bd("6").compareTo(CostCalculator.unitPrice(6000, bd("1000"))))
        // 3,800 DT le kg → 3,8 millimes par gramme : pas d'arrondi au millime à ce stade
        assertEquals(0, bd("3.8").compareTo(CostCalculator.unitPrice(3800, bd("1000"))))
    }

    @Test
    fun rendementAugmenteLePrixReel() {
        // 1 kg à 7,000 DT avec 70 % utilisable : 7000 ÷ 700 g = 10 millimes par gramme utilisable
        assertEquals(0, bd("10").compareTo(CostCalculator.unitPrice(7000, bd("1000"), bd("70"))))
        assertNull(CostCalculator.unitPrice(7000, bd("1000"), bd("0")))
        assertNull(CostCalculator.unitPrice(7000, bd("1000"), bd("120")))
    }

    @Test
    fun prixInconnuOuQuantiteNulle() {
        assertNull(CostCalculator.unitPrice(null, bd("1000")))
        assertNull(CostCalculator.unitPrice(6000, BigDecimal.ZERO))
    }

    @Test
    fun recetteSimpleEnMillimes() {
        // lot = 50 g × 6 + 10 × 250 = 2800 millimes ; 10 parts → 280 ; marge 40 % → 280 ÷ 0,6 = 466,67 → 467
        val r = CostCalculator.compute(
            RecipeInput(10, bd("40"), listOf(Line(1, bd("50"), "g"), Line(2, bd("10"), "piece"))), prices
        )
        assertEquals(2800L, r.batchMillimes)
        assertEquals(280L, r.costPerPortionMillimes)
        assertEquals(467L, r.suggestedPriceMillimes)
        assertTrue(r.isComplete)
        assertEquals("Pain", r.mostExpensive?.ingredient?.name)
    }

    @Test
    fun conversionKgDansLaRecette() {
        val r = CostCalculator.compute(RecipeInput(1, bd("0"), listOf(Line(1, bd("0.05"), "kg"))), prices)
        assertEquals(300L, r.costPerPortionMillimes)
        assertEquals(300L, r.suggestedPriceMillimes)
    }

    @Test
    fun conversionLitresEtCentilitres() {
        val huile = IngredientPrice(6, "Huile", "ml", bd("1000"), 5400)   // 5,400 DT le litre
        val l = CostCalculator.compute(RecipeInput(1, bd("0"), listOf(Line(6, bd("0.25"), "l"))), mapOf(6L to huile))
        assertEquals(1350L, l.costPerPortionMillimes)
        val cl = CostCalculator.compute(RecipeInput(1, bd("0"), listOf(Line(6, bd("5"), "cl"))), mapOf(6L to huile))
        assertEquals(270L, cl.costPerPortionMillimes)
    }

    @Test
    fun uniteEmballageEtDouzaine() {
        val oeufs = IngredientPrice(3, "Oeufs", "piece", bd("30"), 12000)   // plateau de 30 à 12,000
        val r = CostCalculator.compute(RecipeInput(1, bd("0"), listOf(Line(3, bd("1"), "emballage"))), mapOf(3L to oeufs))
        assertEquals(12000L, r.costPerPortionMillimes)
        val dz = CostCalculator.compute(RecipeInput(1, bd("0"), listOf(Line(3, bd("1"), "douzaine"))), mapOf(3L to oeufs))
        assertEquals(4800L, dz.costPerPortionMillimes)
    }

    @Test
    fun uniteIncompatibleSignalee() {
        val r = CostCalculator.compute(RecipeInput(1, bd("40"), listOf(Line(2, bd("100"), "g"))), prices)
        assertTrue(Issue.UNIT_MISMATCH in r.issues)
        assertFalse(r.isComplete)
        assertEquals(0L, r.costPerPortionMillimes)
    }

    @Test
    fun zeroPartsDonneUnCoutAbsent() {
        val r = CostCalculator.compute(RecipeInput(0, bd("40"), listOf(Line(1, bd("50"), "g"))), prices)
        assertNull(r.costPerPortionMillimes)
        assertNull(r.suggestedPriceMillimes)
        assertTrue(Issue.ZERO_PORTIONS in r.issues)
        assertEquals(300L, r.batchMillimes)
    }

    @Test
    fun quantiteNulleEtPrixManquant() {
        val sansPrix = IngredientPrice(4, "Safran", "g", bd("10"), null)
        val r = CostCalculator.compute(
            RecipeInput(2, bd("30"), listOf(Line(1, bd("0"), "g"), Line(4, bd("1"), "g"), Line(9, bd("1"), "g"))),
            prices + (4L to sansPrix)
        )
        assertEquals(setOf(Issue.ZERO_QUANTITY, Issue.MISSING_PRICE, Issue.MISSING_INGREDIENT), r.issues)
        assertEquals(0L, r.costPerPortionMillimes)
    }

    @Test
    fun coutsCachesInclusDansLaPart() {
        // lot 2800 + gaz 400 + main-d'oeuvre 1800 = 5000 ; ÷ 10 = 500 ; + emballage 120 + loyer 80 = 700
        val r = CostCalculator.compute(
            RecipeInput(
                10, bd("40"), listOf(Line(1, bd("50"), "g"), Line(2, bd("10"), "piece")),
                HiddenCosts(packagingPerPortionMillimes = 120, energyPerBatchMillimes = 400, laborPerBatchMillimes = 1800, fixedPerPortionMillimes = 80)
            ), prices
        )
        assertEquals(5000L, r.batchMillimes)
        assertEquals(700L, r.costPerPortionMillimes)
        assertEquals(1167L, r.suggestedPriceMillimes)   // 700 ÷ 0,6 = 1166,67
    }

    @Test
    fun arrondiHalfUpAuMillime() {
        val farine = IngredientPrice(5, "Farine", "g", bd("1000"), 1000)      // 1 millime le gramme
        // 1 millime ÷ 3 parts = 0,333 → 0
        assertEquals(0L, CostCalculator.compute(RecipeInput(3, bd("0"), listOf(Line(5, bd("1"), "g"))), mapOf(5L to farine)).costPerPortionMillimes)
        // 3 ÷ 2 = 1,5 → 2 (HALF_UP)
        assertEquals(2L, CostCalculator.compute(RecipeInput(2, bd("0"), listOf(Line(5, bd("3"), "g"))), mapOf(5L to farine)).costPerPortionMillimes)
        // 5 ÷ 2 = 2,5 → 3
        assertEquals(3L, CostCalculator.compute(RecipeInput(2, bd("0"), listOf(Line(5, bd("5"), "g"))), mapOf(5L to farine)).costPerPortionMillimes)
    }

    @Test
    fun pasDErreurDeVirguleFlottante() {
        // 0,1 + 0,2 en Double donne 0,30000000000000004 ; ici 100 + 200 millimes = 300 exactement
        val a = IngredientPrice(7, "A", "piece", bd("1"), 100)
        val b = IngredientPrice(8, "B", "piece", bd("1"), 200)
        val r = CostCalculator.compute(RecipeInput(1, bd("0"), listOf(Line(7, bd("1"), "piece"), Line(8, bd("1"), "piece"))), mapOf(7L to a, 8L to b))
        assertEquals(300L, r.costPerPortionMillimes)
    }

    @Test
    fun margesEtPrixConseille() {
        assertEquals(467L, CostCalculator.suggestedPrice(280, bd("40")))
        assertEquals(280L, CostCalculator.suggestedPrice(280, bd("0")))
        assertNull(CostCalculator.suggestedPrice(280, bd("100")))
        assertNull(CostCalculator.suggestedPrice(280, bd("-5")))
        assertEquals(0, bd("44.0").compareTo(CostCalculator.marginPercent(500, 280)))
        assertNull(CostCalculator.marginPercent(0, 280))
        assertEquals(0, bd("-12.0").compareTo(CostCalculator.marginPercent(250, 280)))
    }

    @Test
    fun margeInvalideSignalee() {
        val r = CostCalculator.compute(RecipeInput(10, bd("100"), listOf(Line(1, bd("50"), "g"))), prices)
        assertTrue(Issue.INVALID_MARGIN in r.issues)
        assertNull(r.suggestedPriceMillimes)
    }

    @Test
    fun margeReelleAvecPrixPratique() {
        val r = CostCalculator.compute(
            RecipeInput(10, bd("40"), listOf(Line(1, bd("50"), "g"), Line(2, bd("10"), "piece")), sellingPriceMillimes = 400), prices
        )
        assertEquals(0, bd("30.0").compareTo(r.realMarginPercent))   // (400 − 280) ÷ 400
        assertTrue(r.isBelowTarget(bd("40")))
        assertFalse(r.isBelowTarget(bd("30")))
    }

    @Test
    fun mainOeuvreEtChargesFixes() {
        // 2 personnes × 1200 DT, 2 × 200 h/mois : 2 400 000 ÷ 24 000 min = 100 millimes/min × 30 min = 3000
        assertEquals(3000L, CostCalculator.laborCost(2_400_000, 400, 30))
        assertNull(CostCalculator.laborCost(0, 160, 30))
        assertEquals(1800L, CostCalculator.fixedCostPerPortion(900_000, 500))
        assertEquals(0L, CostCalculator.fixedCostPerPortion(900_000, 0))
    }

    @Test
    fun prixPourPlusieursMarges() {
        val list = CostCalculator.priceForMargins(600, listOf(bd("30"), bd("50")))
        assertEquals(857L, list[0].second)
        assertEquals(1200L, list[1].second)
    }
}

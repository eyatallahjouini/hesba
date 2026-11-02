package com.example.hesba

import org.junit.Assert.assertEquals
import org.junit.Test

class CostCalculatorTest {

    private val delta = 0.0001

    @Test
    fun prixParUniteEtCoutDeLigne() {
        val ppu = CostCalculator.pricePerUnit(6.0, 1000.0)       // 6 dt le kg
        assertEquals(0.006, ppu, delta)
        assertEquals(0.30, CostCalculator.lineCost(50.0, ppu), delta)
    }

    @Test
    fun divisionsParZeroRenvoientZero() {
        assertEquals(0.0, CostCalculator.pricePerUnit(6.0, 0.0), delta)
        assertEquals(0.0, CostCalculator.costPerPiece(10.0, 0.0), delta)
        assertEquals(0.0, CostCalculator.sellingPriceHT(10.0, 1.0), delta)
    }

    @Test
    fun prixConseilleAvecMarge40() {
        // coût 0,28 dt -> prix conseillé 0,28 / 0,6 = 0,4667 dt
        assertEquals(0.4667, CostCalculator.sellingPriceHT(0.28, 0.40), delta)
    }

    @Test
    fun ingredientLePlusCher() {
        assertEquals(1, CostCalculator.mostExpensiveIndex(listOf(0.30, 2.50, 1.0)))
        assertEquals(-1, CostCalculator.mostExpensiveIndex(listOf(0.0, 0.0)))
        assertEquals(-1, CostCalculator.mostExpensiveIndex(emptyList()))
    }
}

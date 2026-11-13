package com.example.hesba.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Nombres entiers saisis : parts, minutes de préparation, volume mensuel, employés, heures. */
class WholeNumberTest {

    @Test
    fun latinAndArabicDigits() {
        assertEquals(12, NumberParser.parseCount("12"))
        assertEquals(12, NumberParser.parseCount(" 12 "))
        assertEquals(12, NumberParser.parseCount("١٢"))       // clavier arabe (liste de courses)
        assertEquals(1500, NumberParser.parseCount("1 500"))
        assertEquals(0, NumberParser.parseCount("0"))
    }

    @Test
    fun decimalIsRefusedInsteadOfTruncated() {
        assertNull(NumberParser.parseCount("2,5"))            // avant : devenait 2 en silence
        assertNull(NumberParser.parseCount("2.5"))
        assertEquals(3, NumberParser.parseCount("3,0"))
    }

    @Test
    fun hugeNumberIsRefusedInsteadOfWrapping() {
        assertNull(NumberParser.parseCount("4294967298"))     // avant : devenait 2
        assertNull(NumberParser.parseCount("3000000000"))     // avant : devenait négatif
        assertEquals(Int.MAX_VALUE, NumberParser.parseCount(Int.MAX_VALUE.toString()))
    }

    @Test
    fun emptyNegativeOrTextIsNull() {
        assertNull(NumberParser.parseCount(null))
        assertNull(NumberParser.parseCount(""))
        assertNull(NumberParser.parseCount("-3"))
        assertNull(NumberParser.parseCount("abc"))
    }
}

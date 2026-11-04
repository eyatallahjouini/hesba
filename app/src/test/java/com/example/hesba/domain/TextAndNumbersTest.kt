package com.example.hesba.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class TextAndNumbersTest {

    private fun num(s: String) = NumberParser.parse(s)

    @Test
    fun separateursDecimaux() {
        assertEquals(0, BigDecimal("1.5").compareTo(num("1,5")))
        assertEquals(0, BigDecimal("1.5").compareTo(num("1.500")))       // usage tunisien : 1.500 = 1,5 DT
        assertEquals(0, BigDecimal("1234.5").compareTo(num("1.234,500")))
        assertEquals(0, BigDecimal("1234.5").compareTo(num("1 234,500")))
        assertEquals(0, BigDecimal("1234.5").compareTo(num("1,234.500")))
        assertEquals(0, BigDecimal("0.5").compareTo(num(",5")))
        assertEquals(0, BigDecimal("12").compareTo(num("12")))
    }

    @Test
    fun chiffresArabes() {
        assertEquals(0, BigDecimal("1.25").compareTo(num("١٫٢٥")))
        assertEquals(0, BigDecimal("32").compareTo(num("٣٢")))
        assertEquals(0, BigDecimal("32.5").compareTo(num("٣٢،٥")))
        assertEquals(0, BigDecimal("1250").compareTo(num("١٬٢٥٠")))
        assertEquals(0, BigDecimal("47").compareTo(num("۴۷")))           // chiffres persans
        assertEquals(0, BigDecimal("3.2").compareTo(num("‏3,2‏")))   // marques bidi
    }

    @Test
    fun nombresInvalides() {
        assertNull(num(""))
        assertNull(num("abc"))
        assertNull(num("12a"))
        assertNull(num(","))
        assertEquals(0, BigDecimal("-3").compareTo(num("-3")))
    }

    @Test
    fun millimes() {
        assertEquals(1250L, Money.parseMillimes("1,25"))
        assertEquals(1250L, Money.parseMillimes("1.250"))
        assertEquals(1L, Money.parseMillimes("0,0005"))     // HALF_UP
        assertEquals(0L, Money.parseMillimes("0,0004"))
        assertNull(Money.parseMillimes("-1"))
        assertEquals(2675L, Money.fromLegacyDouble(2.675))  // Double 2.675 = 2.67499… : on part de sa forme décimale
        assertEquals(6000L, Money.fromLegacyDouble(6.0))
        assertEquals(0, BigDecimal("1.250").compareTo(Money.toDinars(1250)))
    }

    @Test
    fun normalisationFrancais() {
        assertEquals("creme fraiche", TextNormalizer.normalize("  Crème   FRAÎCHE "))
        assertEquals("oeufs", TextNormalizer.normalize("Œufs"))
        assertEquals("pate a choux", TextNormalizer.normalize("Pâte-à-choux"))
        assertTrue(TextNormalizer.matches("Crème brûlée", "BRULEE"))
        assertFalse(TextNormalizer.matches("Tarte", "pizza"))
    }

    @Test
    fun normalisationArabe() {
        assertEquals("اسكر", TextNormalizer.normalize("أسكر"))
        assertEquals("حليب", TextNormalizer.normalize("حَلِيب"))           // harakat retirés
        assertEquals("زبده", TextNormalizer.normalize("زبدة"))            // ة → ه
        assertEquals("حلوي", TextNormalizer.normalize("حلوى"))            // ى → ي
        assertEquals("طماطم", TextNormalizer.normalize("طمـــاطم"))        // tatweel
        assertEquals("ماء", TextNormalizer.normalize("ماء").replace(" ", ""))
        assertTrue(TextNormalizer.matches("بيتزا بالجبن", "بيتزا"))
        assertTrue(TextNormalizer.containsArabic("فارينة 25"))
        assertTrue(TextNormalizer.arabicRatio("فارينة flour") > 0.4)
    }

    @Test
    fun unites() {
        assertEquals(MeasureUnit.KG, MeasureUnit.parseWord("Kg"))
        assertEquals(MeasureUnit.KG, MeasureUnit.parseWord("كغ"))
        assertEquals(MeasureUnit.L, MeasureUnit.parseWord("لتر"))
        assertEquals(MeasureUnit.PIECE, MeasureUnit.parseWord("pcs"))
        assertEquals(MeasureUnit.PACK, MeasureUnit.parseWord("boîte"))
        assertEquals(MeasureUnit.PACK, MeasureUnit.parseWord("علبة"))
        assertNull(MeasureUnit.parseWord("farine"))

        val pkg = BigDecimal("30")
        assertEquals(0, BigDecimal("2500").compareTo(UnitConverter.toBase(BigDecimal("2.5"), MeasureUnit.KG, "g", null)))
        assertEquals(0, BigDecimal("60").compareTo(UnitConverter.toBase(BigDecimal("2"), MeasureUnit.PACK, "piece", pkg)))
        assertNull(UnitConverter.toBase(BigDecimal("1"), MeasureUnit.KG, "piece", pkg))
        assertNull(UnitConverter.toBase(BigDecimal("1"), MeasureUnit.PACK, "g", null))
        assertEquals(0, BigDecimal("0.5").compareTo(UnitConverter.fromBase(BigDecimal("500"), MeasureUnit.L, null)))
    }
}

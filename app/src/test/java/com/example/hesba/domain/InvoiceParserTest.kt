package com.example.hesba.domain

import com.example.hesba.domain.invoice.InvoiceParser
import com.example.hesba.domain.invoice.LineField
import com.example.hesba.domain.invoice.OcrLayout
import com.example.hesba.domain.invoice.OcrToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class InvoiceParserTest {

    private fun bd(s: String) = BigDecimal(s)

    private val factureFr = listOf(
        "Moulin du Sahel SARL",
        "Tél : 73 123 456   MF 1234567/A",
        "Facture N° 2026-118        Date : 03/11/2026",
        "Désignation      Qté    P.U     Montant",
        "Farine fleur sac 25 kg   2   32,000   64,000",
        "Sucre 10 kg 1,850 18,500",
        "Lait 1L   12   1.350   16.200",
        "Oeufs plateau 30 pcs  3  12,000  36,000",
        "Beurre",
        "Levure boulangère 0,800",
        "Remise 5 %   6,735",
        "Total HT   134,700",
        "TVA 19 %   24,312",
        "Timbre fiscal   1,000",
        "Net à payer   153,277 DT"
    )

    @Test
    fun factureFrancaiseEnTete() {
        val inv = InvoiceParser.parse(factureFr)
        assertEquals("2026-11-03", inv.date.value)
        assertFalse(inv.date.uncertain)
        assertEquals("Moulin du Sahel SARL", inv.supplier.value)
        assertTrue(inv.supplier.uncertain)                       // deviné : à confirmer
        assertEquals(0, bd("19").compareTo(inv.taxPercent))
        assertEquals(24312L, inv.taxMillimes)
        assertEquals(0, bd("5").compareTo(inv.discountPercent))
        assertEquals(153277L, inv.totalMillimes)
    }

    @Test
    fun factureFrancaiseLignes() {
        val lines = InvoiceParser.parse(factureFr).lines
        assertEquals(listOf("Farine fleur sac", "Sucre", "Lait", "Oeufs plateau", "Levure boulangère"), lines.map { it.name })

        val farine = lines[0]
        assertEquals(0, bd("2").compareTo(farine.quantity))
        assertEquals(MeasureUnit.PACK, farine.unit)
        assertEquals(0, bd("25").compareTo(farine.packageSize))
        assertEquals(MeasureUnit.KG, farine.packageUnit)
        assertEquals(32000L, farine.unitPriceMillimes)
        assertEquals(64000L, farine.totalMillimes)
        assertTrue(farine.uncertain.isEmpty())

        val sucre = lines[1]
        assertEquals(0, bd("10").compareTo(sucre.quantity))
        assertEquals(MeasureUnit.KG, sucre.unit)
        assertNull(sucre.packageSize)
        assertEquals(1850L, sucre.unitPriceMillimes)

        val lait = lines[2]
        assertEquals(0, bd("12").compareTo(lait.quantity))
        assertEquals(0, bd("1").compareTo(lait.packageSize))
        assertEquals(MeasureUnit.L, lait.packageUnit)
        assertEquals(1350L, lait.unitPriceMillimes)

        val oeufs = lines[3]
        assertEquals(0, bd("30").compareTo(oeufs.packageSize))
        assertEquals(MeasureUnit.PIECE, oeufs.packageUnit)
        assertEquals(12000L, oeufs.unitPriceMillimes)

        // un seul montant : proposé comme prix, mais marqué à vérifier ; aucune quantité inventée
        val levure = lines[4]
        assertEquals(800L, levure.unitPriceMillimes)
        assertNull(levure.quantity)
        assertTrue(LineField.UNIT_PRICE in levure.uncertain)
        assertTrue(LineField.QUANTITY in levure.uncertain)
    }

    @Test
    fun ligneSansPrixNestPasInventee() {
        // « Beurre » seul n'a aucun nombre : ce n'est pas une ligne exploitable, rien n'est inventé
        assertNull(InvoiceParser.parseLine("Beurre"))
        val l = InvoiceParser.parseLine("Beurre doux 250 g")!!
        assertNull(l.unitPriceMillimes)
        assertTrue(LineField.UNIT_PRICE in l.uncertain)
    }

    @Test
    fun multiplicationExplicite() {
        val l = InvoiceParser.parseLine("Chocolat noir 3 x 8,500 = 25,500")!!
        assertEquals("Chocolat noir", l.name)
        assertEquals(0, bd("3").compareTo(l.quantity))
        assertEquals(8500L, l.unitPriceMillimes)
        assertEquals(25500L, l.totalMillimes)
    }

    @Test
    fun remiseSurLaLigne() {
        val l = InvoiceParser.parseLine("Huile tournesol 5L 4 27,000 108,000 -10%")!!
        assertEquals(0, bd("10").compareTo(l.discountPercent))
        assertEquals(27000L, l.unitPriceMillimes)
    }

    private val factureAr = listOf(
        "شركة المطاحن الكبرى",
        "التاريخ : ٢٠٢٦/١١/٠٣",
        "التسمية    الكمية    السعر    المبلغ",
        "فارينة كيس ٢٥ كغ   ٢   ٣٢٫٠٠٠   ٦٤٫٠٠٠",
        "سكر ١٠ كغ ١٫٨٥٠ ١٨٫٥٠٠",
        "‏حليب ١ ل ١٢ ١٫٣٥٠ ١٦٫٢٠٠‏",
        "خصم ٥ %",
        "الأداء على القيمة المضافة ١٩ % ٢٤٫٣١٢",
        "المجموع ١٥٣٫٢٧٧ د.ت"
    )

    @Test
    fun factureArabeAvecChiffresArabes() {
        val inv = InvoiceParser.parse(factureAr)
        assertEquals("2026-11-03", inv.date.value)
        assertEquals(153277L, inv.totalMillimes)
        assertEquals(0, bd("19").compareTo(inv.taxPercent))
        assertEquals(0, bd("5").compareTo(inv.discountPercent))
        assertEquals(3, inv.lines.size)

        val farine = inv.lines[0]
        assertEquals("فارينة كيس", farine.name)
        assertEquals(0, bd("2").compareTo(farine.quantity))
        assertEquals(0, bd("25").compareTo(farine.packageSize))
        assertEquals(MeasureUnit.KG, farine.packageUnit)
        assertEquals(32000L, farine.unitPriceMillimes)

        val sucre = inv.lines[1]
        assertEquals("سكر", sucre.name)
        assertEquals(MeasureUnit.KG, sucre.unit)
        assertEquals(1850L, sucre.unitPriceMillimes)

        val lait = inv.lines[2]
        assertEquals("حليب", lait.name)
        assertEquals(1350L, lait.unitPriceMillimes)
        assertEquals(0, bd("12").compareTo(lait.quantity))
    }

    @Test
    fun ligneArabeEnOrdreVisuelInverse() {
        // certains OCR rendent la ligne de gauche à droite : total, prix, quantité, puis le nom
        val l = InvoiceParser.parseLine("٦٤٫٠٠٠ ٣٢٫٠٠٠ ٢ سميد")!!
        assertEquals("سميد", l.name)
        assertEquals(0, bd("2").compareTo(l.quantity))
        assertEquals(32000L, l.unitPriceMillimes)
        assertEquals(64000L, l.totalMillimes)
    }

    @Test
    fun prixParUniteDeBase() {
        val farine = InvoiceParser.parseLine("Farine sac 25 kg 2 32,000 64,000")!!
        // 32 000 millimes ÷ 25 000 g = 1,28 millime/g
        assertEquals(0, bd("1.28").compareTo(InvoiceParser.pricePerBaseUnit(farine, "g", null, null, null)))
        // TVA 19 % ajoutée : 1,5232
        assertEquals(0, bd("1.5232").compareTo(InvoiceParser.pricePerBaseUnit(farine, "g", null, null, bd("19"))))
        // remise globale 5 % : 1,216
        assertEquals(0, bd("1.216").compareTo(InvoiceParser.pricePerBaseUnit(farine, "g", null, bd("5"), null)))
        // unité incompatible (kg vers pièce) : pas de prix, l'utilisateur doit corriger
        assertNull(InvoiceParser.pricePerBaseUnit(farine, "piece", null, null, null))
        // emballage sans contenu connu : on utilise l'emballage de l'ingrédient
        val boite = InvoiceParser.parseLine("Levure 2 bte 1,500 3,000")!!
        assertEquals(MeasureUnit.PACK, boite.unit)
        assertEquals(0, bd("150").compareTo(InvoiceParser.pricePerBaseUnit(boite, "g", bd("10"), null, null)))
    }

    @Test
    fun dates() {
        assertEquals("2026-11-03", InvoiceParser.findDate("le 3/11/26"))
        assertEquals("2026-01-15", InvoiceParser.findDate("2026-01-15"))
        assertNull(InvoiceParser.findDate("45/13/2026"))
    }

    @Test
    fun heureDuTicketNestPasUnIngredient() {
        // le « : » de l'heure est souvent lu comme un point ou un espace par l'OCR
        listOf("14:35", "Heure : 09:12", "Heure 14.35", "Heure 14 35", "H : 14.35", "Time 14:35:20", "14h35",
            "الساعة ١٤:٣٥", "03/11/2026 14:35", "Date : 03-11-26").forEach {
            assertFalse(it, InvoiceParser.isIngredientLine(it))
        }
    }

    @Test
    fun lignesDuTicketIgnorees() {
        listOf("", "----------", "123 456 789", "TOTAL 25,400", "Sous-total 20,000", "TVA 19% 3,800", "Timbre 1,000",
            "Remise 2,000", "Net à payer 25,400", "Espèces 30,000", "Rendu 4,600", "Monnaie 4,600", "Carte 25,400",
            "Tél : 73 123 456", "MF 1234567/A", "Ticket N° 0045", "Facture N° 118", "Caisse 2", "Caissier : Ali 3",
            "Merci de votre visite", "À bientôt", "المجموع ٢٥٫٤٠٠", "شكرا لزيارتكم", "رقم ٤٥").forEach {
            assertFalse(it, InvoiceParser.isIngredientLine(it))
        }
    }

    @Test
    fun vraisArticlesGardes() {
        listOf("Farine fleur sac 25 kg 2 32,000 64,000", "Lait 1L 12 1.350 16.200", "Beurre doux 250 g",
            "Levure boulangère 0,800", "سكر ١٠ كغ ١٫٨٥٠ ١٨٫٥٠٠").forEach {
            assertTrue(it, InvoiceParser.isIngredientLine(it))
        }
        assertFalse(InvoiceParser.isIngredientLine("Beurre"))   // nom sans prix ni quantité
    }

    @Test
    fun ticketCompletSansHeureNiTotalDansLesIngredients() {
        val inv = InvoiceParser.parse(listOf(
            "Ticket N° 0045", "03/11/2026 14:35", "Heure 14.35", "Caisse 2",
            "Farine 2 1,200 2,400", "Sucre 1 kg 1,850", "TOTAL 4,250", "Espèces 5,000", "Rendu 0,750", "Merci, à bientôt"
        ))
        assertEquals(listOf("Farine", "Sucre"), inv.lines.map { it.name })
        assertEquals("2026-11-03", inv.date.value)
        assertEquals(4250L, inv.totalMillimes)
        assertNull(inv.supplier.value)   // « Ticket N° 0045 » n'est pas pris pour le magasin
    }

    @Test
    fun reconstructionDesLignesOcr() {
        // ML Kit rend souvent les colonnes séparément : on les remet sur la même ligne
        val tokens = listOf(
            OcrToken("Farine", 10, 100, 90, 120),
            OcrToken("64,000", 400, 102, 470, 121),
            OcrToken("2", 200, 99, 210, 119),
            OcrToken("Sucre", 10, 140, 80, 160),
            OcrToken("18,500", 400, 141, 470, 161)
        )
        val rows = OcrLayout.groupRows(tokens, rightToLeft = false)
        assertEquals(2, rows.size)
        assertEquals("Farine  2  64,000", rows[0].text)
        val rtl = OcrLayout.groupRows(listOf(OcrToken("حليب", 300, 10, 380, 30), OcrToken("١٢", 100, 12, 130, 31)), rightToLeft = true)
        assertEquals("حليب  ١٢", rtl[0].text)
        assertNotNull(rows[1])
    }
}

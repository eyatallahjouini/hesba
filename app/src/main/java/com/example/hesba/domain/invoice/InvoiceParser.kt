package com.example.hesba.domain.invoice

import com.example.hesba.domain.MeasureUnit
import com.example.hesba.domain.Money
import com.example.hesba.domain.NumberParser
import com.example.hesba.domain.TextNormalizer
import java.math.BigDecimal
import java.math.RoundingMode

/** Valeur extraite + indicateur « à vérifier ». Une valeur absente reste null : on n'invente jamais. */
data class Extracted<T>(val value: T?, val uncertain: Boolean) {
    companion object {
        fun <T> missing() = Extracted<T>(null, true)
    }
}

enum class LineField { NAME, QUANTITY, UNIT, UNIT_PRICE, TOTAL }

data class InvoiceLine(
    val raw: String,
    val name: String,
    val quantity: BigDecimal?,          // nombre d'unités achetées
    val unit: MeasureUnit?,             // unité de la quantité (kg, pièce, emballage…)
    val packageSize: BigDecimal?,       // contenu d'un emballage (« sac 25 kg » → 25)
    val packageUnit: MeasureUnit?,      // unité du contenu (kg)
    val unitPriceMillimes: Long?,       // prix d'UNE unité (d'un emballage si packageSize est connu)
    val totalMillimes: Long?,
    val discountPercent: BigDecimal?,
    val uncertain: Set<LineField>
)

data class ParsedInvoice(
    val supplier: Extracted<String>,
    val date: Extracted<String>,        // ISO yyyy-MM-dd
    val lines: List<InvoiceLine>,
    val discountPercent: BigDecimal?,
    val discountMillimes: Long?,
    val taxPercent: BigDecimal?,
    val taxMillimes: Long?,
    val totalMillimes: Long?
)

/**
 * Lecture d'une facture à partir des lignes OCR (français, anglais, arabe standard).
 * Règles :
 *  - chiffres ٠-٩ convertis, marques bidi retirées, ordre gauche→droite ou droite→gauche accepté ;
 *  - une ligne produit = un nom + des nombres ; si quantité × prix unitaire ≈ total, les trois sont sûrs ;
 *  - sinon les champs devinés sont marqués « à vérifier » et un prix absent reste vide.
 */
object InvoiceParser {

    private val NUMBER = Regex("\\d+(?:[.,٫،]\\d+)*")
    private val PERCENT = Regex("(\\d+(?:[.,٫]\\d+)?)\\s*%")
    private val DATE_DMY = Regex("(?<!\\d)(\\d{1,2})\\s*[/\\-.]\\s*(\\d{1,2})\\s*[/\\-.]\\s*(\\d{4}|\\d{2})(?!\\d)")
    private val DATE_YMD = Regex("(?<!\\d)(\\d{4})[/\\-.](\\d{1,2})[/\\-.](\\d{1,2})(?!\\d)")
    private val MULT = Regex("(?<=\\d)\\s*[x×*]\\s*(?=\\d)", RegexOption.IGNORE_CASE)

    private val CURRENCY = setOf("dt", "tnd", "dinar", "dinars", "د.ت", "دت", "دينار", "دنانير", "مي", "millimes", "dinard")
    private val STOP = setOf("de", "du", "des", "la", "le", "les", "l", "d", "en", "au", "aux", "et", "a", "of", "the", "x", "n", "no", "ref")

    private val TOTAL_WORDS = listOf("total ttc", "net a payer", "net à payer", "montant ttc", "total a payer", "total", "grand total",
        "amount due", "المجموع", "الجملة", "المبلغ الجملي", "المبلغ الاجمالي", "صافي", "الاجمالي", "المبلغ الجملي بحساب الاداءات")
    private val SUBTOTAL_WORDS = listOf("total ht", "sous total", "sous-total", "subtotal", "montant ht", "المجموع دون اداءات", "دون اعتبار الاداءات")
    private val TAX_WORDS = listOf("tva", "t v a", "vat", "tax", "taxe", "ض ق م", "الاداء على القيمه المضافه", "الضريبه", "اداء")
    private val DISCOUNT_WORDS = listOf("remise", "rabais", "ristourne", "discount", "خصم", "تخفيض", "تخفيظ")
    private val SUPPLIER_WORDS = listOf("fournisseur", "societe", "supplier", "vendeur", "المزود", "الشركه", "المورد", "البائع")
    private val IGNORE_WORDS = listOf("timbre", "طابع", "tel", "tél", "fax", "email", "e mail", "www", "adresse", "address", "matricule", "mf ",
        "m f", "rc ", "r c", "client", "page", "facture n", "invoice no", "bon de livraison", "الهاتف", "العنوان", "المعرف الجبائي",
        "فاتوره عدد", "رقم", "الحريف", "code tva", "rib", "iban", "caisse", "merci", "شكرا", "date", "التاريخ")
    private val HEADER_WORDS = listOf("designation", "désignation", "article", "produit", "description", "qte", "qté", "quantite", "quantité",
        "prix unitaire", "p u", "pu ", "montant", "qty", "unit price", "amount", "item", "التسميه", "البيان", "الكميه", "السعر", "المبلغ",
        "الثمن", "سعر الوحده", "الماده")

    fun parse(rawLines: List<String>): ParsedInvoice {
        val lines = rawLines.map { clean(it) }.filter { it.isNotBlank() }
        var supplier: Extracted<String>? = null
        var date: Extracted<String> = Extracted.missing()
        var total: Long? = null
        var taxPercent: BigDecimal? = null
        var taxAmount: Long? = null
        var discountPercent: BigDecimal? = null
        var discountAmount: Long? = null
        val products = mutableListOf<InvoiceLine>()

        for ((index, line) in lines.withIndex()) {
            val norm = TextNormalizer.normalize(line)
            if (date.value == null) findDate(line)?.let { date = Extracted(it, false) }

            when {
                SUPPLIER_WORDS.any { startsWithWord(norm, it) } -> {
                    val value = afterLabel(line)
                    if (value.isNotBlank()) supplier = Extracted(value, false)
                }
                SUBTOTAL_WORDS.any { containsWord(norm, it) } -> Unit
                TAX_WORDS.any { containsWord(norm, it) } && !looksLikeProduct(line) -> {
                    PERCENT.find(line)?.let { taxPercent = NumberParser.parse(it.groupValues[1]) }
                    lastAmount(line, excludePercent = true)?.let { taxAmount = it }
                }
                DISCOUNT_WORDS.any { containsWord(norm, it) } && !looksLikeProduct(line) -> {
                    PERCENT.find(line)?.let { discountPercent = NumberParser.parse(it.groupValues[1]) }
                    lastAmount(line, excludePercent = true)?.let { discountAmount = it }
                }
                TOTAL_WORDS.any { containsWord(norm, it) } -> {
                    lastAmount(line, excludePercent = true)?.let { total = it }
                }
                IGNORE_WORDS.any { containsWord(norm, TextNormalizer.normalize(it)) } -> Unit
                isHeader(norm) -> Unit
                else -> {
                    val product = parseLine(line)
                    if (product != null) products += product
                    else if (supplier == null && index < 4 && isNameLike(line)) supplier = Extracted(line.trim(), true)
                }
            }
        }
        return ParsedInvoice(
            supplier = supplier ?: Extracted.missing(),
            date = date,
            lines = products,
            discountPercent = discountPercent,
            discountMillimes = discountAmount,
            taxPercent = taxPercent,
            taxMillimes = taxAmount,
            totalMillimes = total
        )
    }

    /** Analyse une ligne de produit ; null si ce n'est pas une ligne de produit. */
    fun parseLine(rawLine: String): InvoiceLine? {
        val line = clean(rawLine)
        var discount: BigDecimal? = null
        var work = line
        PERCENT.find(work)?.let { m ->
            discount = NumberParser.parse(m.groupValues[1])
            work = work.removeRange(m.range)
        }
        work = work.replace(MULT, " ")

        // jetons : nombres, unités (collées ou non), mots
        val tokens = tokenize(work)
        var packageSize: BigDecimal? = null
        var packageUnit: MeasureUnit? = null
        var loneUnit: MeasureUnit? = null
        val numbers = mutableListOf<BigDecimal>()
        val nameParts = mutableListOf<String>()

        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val unitGlued = gluedQuantity(t)
            when {
                unitGlued != null -> {
                    if (unitGlued.second == MeasureUnit.PACK) { loneUnit = MeasureUnit.PACK; numbers += unitGlued.first }
                    else if (packageSize == null) { packageSize = unitGlued.first; packageUnit = unitGlued.second }
                    else numbers += unitGlued.first
                }
                NUMBER.matches(t) -> {
                    val next = tokens.getOrNull(i + 1)
                    val unit = next?.let { MeasureUnit.parseWord(it) }
                    val value = NumberParser.parse(t)
                    if (value != null && unit != null && unit != MeasureUnit.PACK && packageSize == null && !isCurrency(next)) {
                        packageSize = value; packageUnit = unit; i++
                    } else if (value != null) numbers += value
                }
                isCurrency(t) -> Unit
                MeasureUnit.parseWord(t) != null && t.length <= 8 -> {
                    val u = MeasureUnit.parseWord(t)
                    if (loneUnit == null || u == MeasureUnit.PACK) loneUnit = u
                    if (u == MeasureUnit.PACK) nameParts += t
                }
                t.any { it.isLetter() } -> nameParts += t
            }
            i++
        }

        val name = nameParts.filter { TextNormalizer.normalize(it) !in STOP }.joinToString(" ").trim()
        if (name.count { it.isLetter() } < 2) return null
        if (numbers.isEmpty() && packageSize == null) return null

        val uncertain = mutableSetOf<LineField>()
        var qty: BigDecimal? = null
        var price: Long? = null
        var total: Long? = null

        val triple = findTriple(numbers)
        if (triple != null) {
            qty = triple.first; price = Money.toMillimes(triple.second); total = Money.toMillimes(triple.third)
        } else if (packageSize != null && numbers.size >= 2 && consistent(packageSize, numbers[numbers.size - 2], numbers.last())) {
            // « Sucre 10 kg 1,850 18,500 » : 10 kg est la quantité, pas un emballage
            qty = packageSize; loneUnit = packageUnit; packageSize = null; packageUnit = null
            price = Money.toMillimes(numbers[numbers.size - 2]); total = Money.toMillimes(numbers.last())
        } else {
            when (numbers.size) {
                0 -> { uncertain += LineField.UNIT_PRICE }
                1 -> {
                    // un seul montant : prix de l'unité ou total ? on le propose comme prix, à vérifier
                    price = Money.toMillimes(numbers[0]); uncertain += LineField.UNIT_PRICE; uncertain += LineField.QUANTITY
                }
                else -> {
                    val first = numbers.first()
                    if (isSmallInteger(first)) {
                        qty = first
                        price = Money.toMillimes(numbers[1])
                        if (numbers.size >= 3) total = Money.toMillimes(numbers.last())
                    } else {
                        price = Money.toMillimes(numbers[numbers.size - 2]); total = Money.toMillimes(numbers.last())
                    }
                    uncertain += LineField.QUANTITY; uncertain += LineField.UNIT_PRICE
                    if (total != null) uncertain += LineField.TOTAL
                }
            }
        }
        if (qty == null) uncertain += LineField.QUANTITY
        val unit = when {
            loneUnit != null -> loneUnit
            packageSize != null -> MeasureUnit.PACK
            else -> { uncertain += LineField.UNIT; null }
        }
        if (name.length < 3) uncertain += LineField.NAME
        return InvoiceLine(line, name, qty, unit, packageSize, packageUnit, price, total, discount, uncertain)
    }

    // ---------- outils ----------

    fun clean(raw: String): String =
        TextNormalizer.stripBidiMarks(TextNormalizer.normalizeDigits(raw)).replace(' ', ' ').replace(Regex("[|¦]"), " ").trim()

    private fun tokenize(text: String): List<String> {
        // sépare nombre et mot collés côté gauche (« 25kg » reste entier pour gluedQuantity)
        return text.split(Regex("\\s+")).map { it.trim(':', ';', '"', '(', ')', '[', ']', '=') }
            .filter { it.isNotEmpty() && it != "-" && it != "—" }
    }

    private fun gluedQuantity(token: String): Pair<BigDecimal, MeasureUnit>? {
        val m = Regex("^(\\d+(?:[.,٫]\\d+)?)([^\\d.,٫]+)$").find(token) ?: return null
        val unit = MeasureUnit.parseWord(m.groupValues[2]) ?: return null
        val value = NumberParser.parse(m.groupValues[1]) ?: return null
        return value to unit
    }

    private fun isCurrency(token: String?): Boolean {
        if (token == null) return false
        val t = token.lowercase().trim('.')
        return t in CURRENCY || TextNormalizer.normalize(token) in CURRENCY
    }

    private fun isSmallInteger(v: BigDecimal) = v.stripTrailingZeros().scale() <= 0 && v <= BigDecimal(10000)

    private fun consistent(q: BigDecimal, p: BigDecimal, t: BigDecimal): Boolean {
        if (q.signum() <= 0 || p.signum() <= 0 || t.signum() <= 0) return false
        val product = q.multiply(p)
        val tolerance = t.multiply(BigDecimal("0.005")).max(BigDecimal("0.002"))
        return product.subtract(t).abs() <= tolerance
    }

    /** Cherche quantité × prix ≈ total parmi les nombres (ordre normal puis ordre inversé RTL). */
    fun findTriple(numbers: List<BigDecimal>): Triple<BigDecimal, BigDecimal, BigDecimal>? {
        val n = numbers.size
        if (n < 3) return null
        // ordre naturel : q avant p avant t, en partant de la fin de ligne
        for (t in n - 1 downTo 2) for (p in t - 1 downTo 1) for (q in p - 1 downTo 0)
            if (consistent(numbers[q], numbers[p], numbers[t])) return Triple(numbers[q], numbers[p], numbers[t])
        // ordre inversé (OCR qui rend l'arabe en ordre visuel)
        for (t in 0 until n - 2) for (p in t + 1 until n - 1) for (q in p + 1 until n)
            if (consistent(numbers[q], numbers[p], numbers[t])) return Triple(numbers[q], numbers[p], numbers[t])
        return null
    }

    private fun lastAmount(line: String, excludePercent: Boolean): Long? {
        var text = line
        if (excludePercent) text = PERCENT.replace(text, " ")
        text = DATE_DMY.replace(text, " ")
        val match = NUMBER.findAll(text).lastOrNull() ?: return null
        return NumberParser.parse(match.value)?.let { Money.toMillimes(it) }
    }

    fun findDate(line: String): String? {
        DATE_YMD.find(line)?.let { m ->
            val (y, mo, d) = m.destructured
            return iso(y.toInt(), mo.toInt(), d.toInt())
        }
        DATE_DMY.find(line)?.let { m ->
            val (d, mo, y) = m.destructured
            val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
            return iso(year, mo.toInt(), d.toInt())
        }
        return null
    }

    private fun iso(y: Int, m: Int, d: Int): String? {
        if (y !in 2000..2100 || m !in 1..12 || d !in 1..31) return null
        return "%04d-%02d-%02d".format(y, m, d)
    }

    private fun looksLikeProduct(line: String): Boolean = findTriple(NUMBER.findAll(PERCENT.replace(line, " ")).mapNotNull { NumberParser.parse(it.value) }.toList()) != null

    private fun containsWord(norm: String, word: String): Boolean {
        val w = TextNormalizer.normalize(word)
        if (w.isEmpty()) return false
        return Regex("(^| )" + Regex.escape(w) + "($| )").containsMatchIn(norm) ||
            Regex("(^| )(ال|و|وال|بال)" + Regex.escape(w.removePrefix("ال")) + "($| )").containsMatchIn(norm)
    }

    private fun startsWithWord(norm: String, word: String): Boolean = norm.startsWith(TextNormalizer.normalize(word))

    private fun afterLabel(line: String): String {
        val idx = line.indexOfFirst { it == ':' }
        return if (idx >= 0) line.substring(idx + 1).trim() else line.split(Regex("\\s+")).drop(1).joinToString(" ").trim()
    }

    private fun isHeader(norm: String): Boolean = HEADER_WORDS.count { containsWord(norm, it) } >= 2

    private fun isNameLike(line: String): Boolean {
        val letters = line.count { it.isLetter() }
        val digits = line.count { it.isDigit() }
        return letters >= 3 && digits <= letters / 2
    }

    /**
     * Prix réel d'une unité de base (g, ml, pièce) en millimes, remises et TVA appliquées si demandées.
     * Null si l'unité de la ligne ne se convertit pas vers [base] (l'utilisateur doit corriger).
     */
    fun pricePerBaseUnit(
        line: InvoiceLine,
        base: String,
        ingredientPackageBase: BigDecimal?,
        globalDiscountPercent: BigDecimal?,
        taxPercent: BigDecimal?
    ): BigDecimal? {
        val unitPrice = line.unitPriceMillimes ?: return null
        val pricedQuantityBase: BigDecimal = when {
            line.packageSize != null && line.packageUnit != null ->
                com.example.hesba.domain.UnitConverter.toBase(line.packageSize, line.packageUnit, base, null)
            line.unit != null ->
                com.example.hesba.domain.UnitConverter.toBase(BigDecimal.ONE, line.unit, base, ingredientPackageBase)
            else -> null
        } ?: return null
        if (pricedQuantityBase.signum() <= 0) return null
        var price = BigDecimal.valueOf(unitPrice)
        val hundred = BigDecimal(100)
        line.discountPercent?.let { price = price.multiply(hundred.subtract(it)).divide(hundred) }
        globalDiscountPercent?.let { price = price.multiply(hundred.subtract(it)).divide(hundred) }
        taxPercent?.let { price = price.multiply(hundred.add(it)).divide(hundred) }
        return price.divide(pricedQuantityBase, Money.INTERNAL_SCALE, RoundingMode.HALF_UP)
    }
}

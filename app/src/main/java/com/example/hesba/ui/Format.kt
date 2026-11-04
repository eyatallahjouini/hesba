package com.example.hesba.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import com.example.hesba.R
import com.example.hesba.domain.MeasureUnit
import com.example.hesba.domain.Money
import com.example.hesba.domain.UnitConverter
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Date
import java.util.Locale

/**
 * Affichage des nombres selon la langue de l'appli.
 * - Montants toujours avec 3 décimales (millimes) : « 1 250,500 DT », « 1,250.500 TND », « 1.250,500 د.ت ».
 * - En arabe on garde les chiffres 0-9 (usage tunisien, locale ar-TN) et on isole le nombre (bidi)
 *   pour qu'il reste lisible dans une phrase de droite à gauche.
 */
object Fmt {

    fun locale(context: Context): Locale = context.resources.configuration.locales[0]

    private fun numberLocale(context: Context): Locale {
        val l = locale(context)
        return if (l.language == "ar") Locale.forLanguageTag("ar-TN-u-nu-latn") else l
    }

    private fun bidi(context: Context, text: String): String =
        BidiFormatter.getInstance(locale(context)).unicodeWrap(text, TextDirectionHeuristicsCompat.LTR)

    private fun pattern(context: Context, pattern: String) = DecimalFormat(pattern, DecimalFormatSymbols.getInstance(numberLocale(context)))

    /** 1250 → « 1,250 » (sans devise). */
    fun amount(context: Context, millimes: Long): String =
        bidi(context, pattern(context, "#,##0.000").format(Money.toDinars(millimes)))

    /** 1250 → « 1,250 DT ». null → « — ». */
    fun money(context: Context, millimes: Long?): String =
        if (millimes == null) context.getString(R.string.value_missing)
        else context.getString(R.string.money_format, amount(context, millimes))

    /** Écart signé : « +0,120 DT », « −0,050 DT ». */
    fun signedMoney(context: Context, millimes: Long): String {
        val sign = when { millimes > 0 -> "+"; millimes < 0 -> "−"; else -> "" }
        return context.getString(R.string.money_format, bidi(context, sign + pattern(context, "#,##0.000").format(Money.toDinars(kotlin.math.abs(millimes)))))
    }

    /** Montant pour un champ de saisie (sans groupement, séparateur de la langue). */
    fun inputAmount(context: Context, millimes: Long?): String =
        if (millimes == null) "" else pattern(context, "0.000").format(Money.toDinars(millimes))

    fun number(context: Context, value: BigDecimal, maxDecimals: Int = 3): String {
        val p = pattern(context, if (maxDecimals == 0) "#,##0" else "#,##0." + "#".repeat(maxDecimals))
        return bidi(context, p.format(value.setScale(maxDecimals, RoundingMode.HALF_UP)))
    }

    fun number(context: Context, value: Double, maxDecimals: Int = 3) = number(context, BigDecimal(value.toString()), maxDecimals)

    /** Valeur pour un champ de saisie numérique (pas de groupement). */
    fun inputNumber(context: Context, value: Double): String =
        pattern(context, "0.###").format(BigDecimal(value.toString()))

    fun percent(context: Context, value: BigDecimal?): String =
        if (value == null) context.getString(R.string.value_missing)
        else context.getString(R.string.percent_format, number(context, value, 1))

    fun signedPercent(context: Context, value: BigDecimal): String {
        val sign = if (value.signum() > 0) "+" else if (value.signum() < 0) "−" else ""
        return context.getString(R.string.percent_format, bidi(context, sign + pattern(context, "#,##0.#").format(value.abs())))
    }

    fun unitLabel(context: Context, code: String?): String = when (code) {
        "g" -> context.getString(R.string.unit_g)
        "kg" -> context.getString(R.string.unit_kg)
        "mg" -> context.getString(R.string.unit_mg)
        "ml" -> context.getString(R.string.unit_ml)
        "cl" -> context.getString(R.string.unit_cl)
        "l" -> context.getString(R.string.unit_l)
        "piece" -> context.getString(R.string.unit_piece)
        "douzaine" -> context.getString(R.string.unit_dozen)
        "emballage" -> context.getString(R.string.unit_pack)
        else -> code ?: ""
    }

    fun quantity(context: Context, value: BigDecimal, unitCode: String): String =
        context.getString(R.string.quantity_format, number(context, value), unitLabel(context, unitCode))

    /** Quantité de base lisible : 1500 g → « 1,5 kg », 250 ml → « 250 ml ». */
    fun baseQuantity(context: Context, base: BigDecimal, baseUnit: String): String {
        val big = BigDecimal(1000)
        return when {
            baseUnit == "g" && base >= big -> quantity(context, UnitConverter.fromBase(base, MeasureUnit.KG, null)!!, "kg")
            baseUnit == "ml" && base >= big -> quantity(context, UnitConverter.fromBase(base, MeasureUnit.L, null)!!, "l")
            else -> quantity(context, base, baseUnit)
        }
    }

    fun baseQuantity(context: Context, base: Double, baseUnit: String) = baseQuantity(context, BigDecimal(base.toString()), baseUnit)

    fun date(context: Context, millis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale(context)).format(Date(millis))

    fun relative(millis: Long): String =
        DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
}

package com.example.hesba.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.hesba.CostCalculator
import com.example.hesba.R
import com.example.hesba.RecipeCosting
import com.example.hesba.data.Recette
import com.example.hesba.domain.Workspace
import java.io.File
import java.io.FileOutputStream

/**
 * Fiche recette en PDF (A4), partagée par le menu Android.
 * [showPrices] = false : quantités seulement (fiche à donner à un employé ou un client), sans coût ni marge.
 * StaticLayout gère l'arabe (liaison des lettres, droite à gauche).
 */
object RecipePdf {

    private const val W = 595
    private const val H = 842
    private const val M = 48f

    fun create(context: Context, ws: Workspace, recette: Recette, showPrices: Boolean): Uri {
        val rtl = context.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        val cost = ws.cost(recette)
        val doc = PdfDocument()
        var pageNumber = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNumber).create())
        var canvas = page.canvas
        var y = M

        val ink = context.getColor(R.color.brun)
        val soft = context.getColor(R.color.brun_clair)
        val accent = context.getColor(R.color.safran_texte)
        val line = context.getColor(R.color.caramel)

        fun paint(size: Float, color: Int, bold: Boolean = false, serif: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(if (serif) Typeface.SERIF else Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }

        fun newPageIfNeeded(height: Float) {
            if (y + height > H - M) {
                doc.finishPage(page)
                pageNumber++
                page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNumber).create())
                canvas = page.canvas
                y = M
            }
        }

        /** Texte sur une largeur ; [end] = aligné à la fin de la ligne (droite en français, gauche en arabe). */
        fun text(c: Canvas, s: String, x: Float, width: Float, p: TextPaint, end: Boolean = false): Float {
            val align = if (end) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, width.toInt()).setAlignment(align).build()
            c.save(); c.translate(x, y); layout.draw(c); c.restore()
            return layout.height.toFloat()
        }

        fun rule(color: Int = line, thick: Float = 1f) {
            canvas.drawRect(M, y, W - M, y + thick, Paint().apply { this.color = color })
            y += thick + 8f
        }

        val content = W - 2 * M
        // en-tête
        val business = ws.user?.nomCommerce?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name_display)
        y += text(canvas, business, M, content, paint(11f, accent, bold = true)) + 6f
        y += text(canvas, recette.nom, M, content, paint(24f, ink, bold = true, serif = true)) + 4f
        y += text(canvas, context.resources.getQuantityString(R.plurals.portions, recette.nombreParts, recette.nombreParts), M, content, paint(12f, soft)) + 14f
        rule(ink, 2f)

        // colonnes : ingrédient | quantité | coût (si prix)
        val qtyW = 120f
        val costW = if (showPrices) 100f else 0f
        val nameW = content - qtyW - costW
        fun cols(name: String, qty: String, value: String?, p: TextPaint) {
            val xs = if (!rtl) Triple(M, M + nameW, M + nameW + qtyW) else Triple(M + costW + qtyW, M + costW, M)
            val h1 = text(canvas, name, xs.first, nameW - 8f, p)
            val h2 = text(canvas, qty, xs.second, qtyW - 8f, p, end = true)
            val h3 = if (value != null && showPrices) text(canvas, value, xs.third, costW, p, end = true) else 0f
            y += maxOf(h1, h2, h3) + 6f
        }
        val head = paint(10f, soft, bold = true)
        cols(context.getString(R.string.pdf_col_ingredient), context.getString(R.string.pdf_col_quantity), context.getString(R.string.pdf_col_cost), head)
        rule()
        val body = paint(12f, ink)
        for (lc in cost.lines) {
            newPageIfNeeded(30f)
            val name = lc.ingredient?.name ?: context.getString(R.string.ingredient_deleted)
            val value = when {
                lc.issue == CostCalculator.Issue.MISSING_PRICE -> context.getString(R.string.price_missing)
                lc.costMillimes != null -> Fmt.money(context, lc.costMillimes)
                else -> context.getString(R.string.value_missing)
            }
            cols(name, Fmt.quantity(context, lc.line.quantity, lc.line.unit), value, body)
        }
        rule()

        if (showPrices) {
            fun total(label: String, value: String, strong: Boolean = false) {
                newPageIfNeeded(30f)
                val p = if (strong) paint(14f, ink, bold = true) else paint(12f, soft)
                val hl = text(canvas, label, if (rtl) M + 160f else M, content - 160f, p)
                text(canvas, value, if (rtl) M else W - M - 160f, 160f, p, end = true)
                y += hl + 6f
            }
            if (recette.gazParLotMillimes > 0) total(context.getString(R.string.field_energy), Fmt.money(context, recette.gazParLotMillimes))
            if (recette.mainOeuvreParLotMillimes > 0) total(context.getString(R.string.field_labor), Fmt.money(context, recette.mainOeuvreParLotMillimes))
            total(context.getString(R.string.label_batch_cost), Fmt.money(context, cost.batchMillimes))
            if (recette.emballageParPartMillimes > 0) total(context.getString(R.string.field_packaging), Fmt.money(context, recette.emballageParPartMillimes))
            val fixed = RecipeCosting.fixedPerPortion(ws.user)
            if (fixed > 0) total(context.getString(R.string.detail_fixed), Fmt.money(context, fixed))
            total(context.getString(R.string.label_cost_per_portion), Fmt.money(context, cost.costPerPortionMillimes), strong = true)
            total(context.getString(R.string.label_selling_price), Fmt.money(context, recette.prixVenteMillimes))
            total(context.getString(R.string.label_real_margin), Fmt.percent(context, cost.realMarginPercent))
            if (!cost.isComplete) {
                y += 6f
                y += text(canvas, context.getString(R.string.pdf_incomplete), M, content, paint(10f, context.getColor(R.color.rouge)))
            }
        }

        // pied de page
        y = H - M
        text(canvas, context.getString(R.string.pdf_footer, Fmt.date(context, System.currentTimeMillis())), M, content, paint(9f, soft))
        doc.finishPage(page)

        val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // pas d'accumulation de fiches
        val safe = recette.nom.replace(Regex("[^\\p{L}\\p{Nd}]+"), "_").trim('_').ifEmpty { "recette" }.take(40)
        val file = File(dir, "$safe.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }
}

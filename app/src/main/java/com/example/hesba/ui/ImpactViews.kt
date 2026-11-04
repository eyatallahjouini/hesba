package com.example.hesba.ui

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.example.hesba.R
import com.example.hesba.RecipeDetailActivity
import com.example.hesba.databinding.ItemImpactBinding
import com.example.hesba.domain.RecipeImpact

/** Lignes « avant → après » partagées par le simulateur et le résultat du scanner. */
object ImpactViews {

    fun fill(container: LinearLayout, impacts: List<RecipeImpact>, clickable: Boolean) {
        val ctx = container.context
        container.removeAllViews()
        for (i in impacts.sortedWith(compareByDescending<RecipeImpact> { it.belowTarget }.thenByDescending { kotlin.math.abs(it.costDelta ?: 0) })) {
            val v = ItemImpactBinding.inflate(LayoutInflater.from(ctx), container, false)
            v.impactName.text = i.recette.nom
            val before = i.before.costPerPortionMillimes
            val after = i.after.costPerPortionMillimes
            v.impactCost.text = ctx.getString(R.string.before_after_delta, Fmt.money(ctx, before), Fmt.money(ctx, after), Fmt.signedMoney(ctx, i.costDelta ?: 0))
            v.impactMargin.text = if (i.after.realMarginPercent != null)
                ctx.getString(R.string.before_after, Fmt.percent(ctx, i.before.realMarginPercent), Fmt.percent(ctx, i.after.realMarginPercent))
            else ctx.getString(R.string.advisor_no_price_short)
            v.impactMargin.setTextColor(ctx.getColor(if (i.belowTarget) R.color.hb_bad else R.color.hb_ink))
            v.impactSuggested.text = ctx.getString(R.string.before_after, Fmt.money(ctx, i.before.suggestedPriceMillimes), Fmt.money(ctx, i.after.suggestedPriceMillimes))
            v.impactTag.visibility = if (i.belowTarget) View.VISIBLE else View.GONE
            v.impactTag.setText(if (i.fallsBelowTarget) R.string.tag_falls_below else R.string.tag_below_target)
            if (clickable) v.impactRow.setOnClickListener {
                ctx.startActivity(Intent(ctx, RecipeDetailActivity::class.java).putExtra(RecipeDetailActivity.EXTRA_ID, i.recette.id))
            }
            container.addView(v.root)
        }
    }
}

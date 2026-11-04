package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hesba.databinding.ActivityListScreenBinding
import com.example.hesba.databinding.ItemLedgerBinding
import com.example.hesba.domain.Insights
import com.example.hesba.ui.BaseActivity
import com.example.hesba.ui.Fmt
import com.google.android.material.chip.Chip
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Évolution des prix : plus fortes hausses et recettes les plus touchées, calculées sur l'historique réel. */
class InsightsActivity : BaseActivity() {

    private lateinit var b: ActivityListScreenBinding
    private val days = MutableStateFlow(30)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        b = ActivityListScreenBinding.inflate(layoutInflater)
        setScreen(b.root)
        setTitleText(getString(R.string.tool_insights))
        b.txtIntro.setText(R.string.insights_intro)
        b.groupFilter.visibility = View.VISIBLE
        for ((d, label) in listOf(30 to R.string.period_30, 90 to R.string.period_90, 3650 to R.string.period_all)) {
            b.groupFilter.addView(Chip(this).apply {
                id = View.generateViewId(); setText(label); isCheckable = true; isChecked = d == 30
                setOnClickListener { days.value = d }
            })
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                days.flatMapLatest { d -> combine(repo.workspace(), repo.history(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(d.toLong()))) { w, h -> w to h } }
                    .collect { (w, h) -> render(Insights.priceChanges(w, h), Insights.affectedRecipes(w, h)) }
            }
        }
    }

    private fun section(title: Int) {
        b.content.addView(LayoutInflater.from(this).inflate(R.layout.view_section, b.content, false).apply { (this as TextView).setText(title) })
        b.content.addView(View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.hairline))
            setBackgroundColor(getColor(R.color.hb_line))
        })
    }

    private fun render(changes: List<Insights.PriceChange>, affected: List<Insights.AffectedRecipe>) {
        b.content.removeAllViews()
        val empty = changes.isEmpty() && affected.isEmpty()
        b.empty.root.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            b.empty.emptyIcon.setImageResource(R.drawable.ic_trend_up)
            b.empty.emptyTitle.setText(R.string.insights_empty_title)
            b.empty.emptyBody.setText(R.string.insights_empty_body)
            b.empty.emptyAction.setText(R.string.action_scan_invoice)
            b.empty.emptyAction.setOnClickListener { startActivity(Intent(this, ScanActivity::class.java)) }
            return
        }
        if (changes.isNotEmpty()) {
            section(R.string.insights_increases)
            for (c in changes.take(10)) {
                val v = ItemLedgerBinding.inflate(LayoutInflater.from(this), b.content, false)
                v.ledgerLabel.text = c.ingredient.nom
                v.ledgerDetail.text = getString(R.string.before_after, Fmt.money(this, c.oldPriceMillimes), Fmt.money(this, c.newPriceMillimes)) + " · " + Fmt.relative(c.date)
                v.ledgerDetail.visibility = View.VISIBLE
                v.ledgerValue.text = Fmt.signedPercent(this, c.percent)
                v.ledgerValue.setTextColor(getColor(if (c.percent.signum() > 0) R.color.hb_bad else R.color.hb_good))
                v.root.setOnClickListener { startActivity(Intent(this, NouvelIngredientActivity::class.java).putExtra(NouvelIngredientActivity.EXTRA_INGREDIENT_ID, c.ingredient.id)) }
                b.content.addView(v.root)
            }
        }
        if (affected.isNotEmpty()) {
            section(R.string.insights_recipes)
            for (a in affected.take(10)) {
                val v = ItemLedgerBinding.inflate(LayoutInflater.from(this), b.content, false)
                v.ledgerLabel.text = a.recette.nom
                v.ledgerDetail.text = getString(R.string.before_after, Fmt.money(this, a.oldCost), Fmt.money(this, a.newCost))
                v.ledgerDetail.visibility = View.VISIBLE
                v.ledgerValue.text = getString(R.string.per_portion_delta, Fmt.signedMoney(this, a.delta))
                v.ledgerValue.setTextColor(getColor(if (a.delta > 0) R.color.hb_bad else R.color.hb_good))
                v.root.setOnClickListener { startActivity(Intent(this, RecipeDetailActivity::class.java).putExtra(RecipeDetailActivity.EXTRA_ID, a.recette.id)) }
                b.content.addView(v.root)
            }
        }
    }
}

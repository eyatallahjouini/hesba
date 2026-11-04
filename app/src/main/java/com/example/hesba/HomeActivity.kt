package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hesba.databinding.ActivityHomeBinding
import com.example.hesba.databinding.ItemAlertBinding
import com.example.hesba.databinding.ItemLinkBinding
import com.example.hesba.databinding.ItemStepBinding
import com.example.hesba.domain.HomeSummary
import com.example.hesba.domain.HomeSummary.Alert
import com.example.hesba.ui.BaseActivity
import com.example.hesba.ui.Fmt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Accueil : alertes d'abord, actions rapides, chiffres clés, outils. Aucune vente ni bilan. */
class HomeActivity : BaseActivity() {

    companion object {
        const val EXTRA_WELCOME = "welcome"
        const val EXTRA_MOVED = "moved"
        private const val MAX_ALERTS = 5
    }

    private lateinit var b: ActivityHomeBinding
    private var showAllAlerts = false
    private var lastSummary: HomeSummary? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        b = ActivityHomeBinding.inflate(layoutInflater)
        setScreen(b.root)
        BottomNav.setup(this, BottomNav.Tab.HOME)

        b.txtDate.text = Fmt.date(this, System.currentTimeMillis())
        b.actionNewRecipe.setOnClickListener { startActivity(Intent(this, ComposeRecipeActivity::class.java)) }
        b.actionScan.setOnClickListener { startActivity(Intent(this, ScanActivity::class.java)) }
        b.actionAddIngredient.setOnClickListener { startActivity(Intent(this, NouvelIngredientActivity::class.java)) }
        b.figRecipes.setOnClickListener { BottomNav.open(this, BottomNav.Tab.RECIPES) }
        b.figIngredients.setOnClickListener { BottomNav.open(this, BottomNav.Tab.INGREDIENTS) }
        b.figAttention.setOnClickListener { b.root.post { b.alertsBlock.requestRectangleOnScreen(android.graphics.Rect(0, 0, b.alertsBlock.width, b.alertsBlock.height)) } }
        b.btnMoreAlerts.setOnClickListener { showAllAlerts = !showAllAlerts; lastSummary?.let { renderAlerts(it) } }

        tool(b.toolWhatIf, R.drawable.ic_simulate, R.string.tool_whatif, R.string.tool_whatif_sub, WhatIfActivity::class.java)
        tool(b.toolShopping, R.drawable.ic_cart, R.string.tool_shopping, R.string.tool_shopping_sub, ShoppingListActivity::class.java)
        tool(b.toolInsights, R.drawable.ic_trend_up, R.string.tool_insights, R.string.tool_insights_sub, InsightsActivity::class.java)
        tool(b.toolInvoices, R.drawable.ic_invoice, R.string.tool_invoices, R.string.tool_invoices_sub, InvoicesActivity::class.java)

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_WELCOME, false)) {
            val moved = intent.getIntExtra(EXTRA_MOVED, 0)
            snack(if (moved > 0) getString(R.string.home_welcome_moved, moved) else getString(R.string.home_welcome), long = true)
        }

        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(HomeSummary.RECENT_DAYS.toLong())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(repo.workspace(), repo.history(since)) { ws, h -> ws to HomeSummary.build(ws, h) }
                    .collect { (ws, summary) -> render(ws.user?.prenom, ws.user?.nomCommerce, summary) }
            }
        }
    }

    private fun tool(row: ItemLinkBinding, icon: Int, title: Int, sub: Int, target: Class<*>) {
        row.icon.setImageResource(icon)
        row.title.setText(title)
        row.subtitle.setText(sub)
        row.rowClick.setOnClickListener { startActivity(Intent(this, target)) }
    }

    private fun render(firstName: String?, business: String?, s: HomeSummary) {
        lastSummary = s
        b.txtGreeting.text = when {
            !business.isNullOrBlank() -> business
            !firstName.isNullOrBlank() -> getString(R.string.home_hello, firstName)
            else -> getString(R.string.home_hello_guest)
        }
        b.txtFigRecipes.text = Fmt.number(this, s.recipeCount.toBigDecimal(), 0)
        b.txtFigIngredients.text = Fmt.number(this, s.ingredientCount.toBigDecimal(), 0)
        b.txtFigAttention.text = Fmt.number(this, s.recipesNeedingAttention.toBigDecimal(), 0)
        b.txtFigAttention.setTextColor(getColor(if (s.recipesNeedingAttention > 0) R.color.hb_bad else R.color.hb_ink))

        val firstUse = s.ingredientCount == 0 || s.recipeCount == 0
        b.firstUse.visibility = if (firstUse) View.VISIBLE else View.GONE
        if (firstUse) renderSteps(s)
        b.alertsBlock.visibility = if (s.recipeCount == 0 && s.alerts.isEmpty()) View.GONE else View.VISIBLE
        renderAlerts(s)
    }

    private fun renderSteps(s: HomeSummary) {
        step(b.step1, 1, R.string.step1_title, R.string.step1_body, R.string.action_add, s.ingredientCount > 0, true) {
            startActivity(Intent(this, NouvelIngredientActivity::class.java))
        }
        step(b.step2, 2, R.string.step2_title, R.string.step2_body, R.string.action_create, s.recipeCount > 0, s.ingredientCount > 0) {
            startActivity(Intent(this, ComposeRecipeActivity::class.java))
        }
        step(b.step3, 3, R.string.step3_title, R.string.step3_body, R.string.action_scan, false, true) {
            startActivity(Intent(this, ScanActivity::class.java))
        }
    }

    private fun step(v: ItemStepBinding, n: Int, title: Int, body: Int, action: Int, done: Boolean, enabled: Boolean, onClick: () -> Unit) {
        v.stepNumber.text = if (done) "✓" else Fmt.number(this, n.toBigDecimal(), 0)
        v.stepNumber.contentDescription = getString(if (done) R.string.step_done else R.string.step_todo)
        v.stepTitle.setText(title)
        v.stepBody.setText(body)
        v.stepButton.setText(action)
        v.stepButton.visibility = if (done) View.GONE else View.VISIBLE
        v.stepButton.isEnabled = enabled
        v.stepButton.setOnClickListener { onClick() }
        v.stepRow.alpha = if (done) 0.6f else 1f
    }

    private fun renderAlerts(s: HomeSummary) {
        b.listAlerts.removeAllViews()
        val visible = if (showAllAlerts) s.alerts else s.alerts.take(MAX_ALERTS)
        b.txtNoAlert.visibility = if (s.alerts.isEmpty()) View.VISIBLE else View.GONE
        b.txtAlertsTitle.text = if (s.alerts.isEmpty()) getString(R.string.home_alerts)
        else getString(R.string.home_alerts_count, s.alerts.size)
        for (alert in visible) b.listAlerts.addView(alertView(alert))
        b.btnMoreAlerts.visibility = if (s.alerts.size > MAX_ALERTS) View.VISIBLE else View.GONE
        b.btnMoreAlerts.text = if (showAllAlerts) getString(R.string.action_show_less) else getString(R.string.home_alerts_all, s.alerts.size)
    }

    private fun alertView(alert: Alert): View {
        val v = ItemAlertBinding.inflate(LayoutInflater.from(this), b.listAlerts, false)
        when (alert) {
            is Alert.BelowTarget -> {
                v.alertIcon.setImageResource(R.drawable.ic_alert)
                v.alertTitle.text = alert.recette.nom
                v.alertBody.text = getString(R.string.alert_below_target, Fmt.percent(this, alert.target), Fmt.money(this, alert.cost), Fmt.money(this, alert.price))
                v.alertValue.text = Fmt.percent(this, alert.margin)
                v.alertValue.contentDescription = getString(R.string.cd_real_margin, Fmt.percent(this, alert.margin))
                v.alertRow.setOnClickListener { openRecipe(alert.recette.id) }
            }
            is Alert.Incomplete -> {
                v.alertIcon.setImageResource(R.drawable.ic_info)
                v.alertTitle.text = alert.recette.nom
                v.alertBody.text = getString(R.string.alert_incomplete, alert.missing.joinToString())
                v.alertValue.visibility = View.GONE
                v.alertRow.setOnClickListener { openRecipe(alert.recette.id) }
            }
            is Alert.NoSellingPrice -> {
                v.alertIcon.setImageResource(R.drawable.ic_info)
                v.alertIcon.setColorFilter(getColor(R.color.hb_accent_ink))
                v.alertTitle.text = alert.recette.nom
                v.alertBody.text = getString(R.string.alert_no_price, Fmt.money(this, alert.suggested))
                v.alertValue.visibility = View.GONE
                v.alertRow.setOnClickListener { openRecipe(alert.recette.id) }
            }
            is Alert.PriceChanged -> {
                v.alertIcon.setImageResource(R.drawable.ic_trend_up)
                val up = (alert.percent?.signum() ?: 0) > 0
                if (!up) v.alertIcon.setColorFilter(getColor(R.color.hb_good))
                v.alertTitle.text = alert.ingredient.nom
                v.alertBody.text = getString(
                    R.string.alert_price_changed,
                    Fmt.money(this, alert.entry.ancienPrixMillimes), Fmt.money(this, alert.entry.nouveauPrixMillimes), Fmt.relative(alert.entry.date)
                )
                if (alert.percent != null) {
                    v.alertValue.text = Fmt.signedPercent(this, alert.percent)
                    if (!up) {
                        v.alertValue.setTextColor(getColor(R.color.hb_good))
                        v.alertValue.setBackgroundResource(R.drawable.bg_tag_good)
                    }
                } else v.alertValue.visibility = View.GONE
                v.alertRow.setOnClickListener {
                    startActivity(Intent(this, NouvelIngredientActivity::class.java).putExtra(NouvelIngredientActivity.EXTRA_INGREDIENT_ID, alert.ingredient.id))
                }
            }
        }
        return v.root
    }

    private fun openRecipe(id: Long) =
        startActivity(Intent(this, RecipeDetailActivity::class.java).putExtra(RecipeDetailActivity.EXTRA_ID, id))
}

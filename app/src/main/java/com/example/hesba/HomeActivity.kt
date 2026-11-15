package com.example.hesba

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hesba.databinding.ActivityHomeBinding
import com.example.hesba.databinding.ItemAlertBinding
import com.example.hesba.domain.Greeting
import com.example.hesba.domain.HomeDashboard
import com.example.hesba.domain.HomeHeadline
import com.example.hesba.domain.HomeSummary
import com.example.hesba.domain.HomeSummary.Alert
import com.example.hesba.domain.Workspace
import com.example.hesba.ui.BaseActivity
import com.example.hesba.ui.Fmt
import com.example.hesba.ui.Motion
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Accueil en grille Bento : une idée par carte (marge moyenne, scanner, recettes à corriger,
 * dernier prix qui a bougé, une action à faire maintenant, meilleure marge, simulateur, courses).
 * Tous les chiffres viennent des données réelles ; aucune vente ni bilan.
 */
class HomeActivity : BaseActivity() {

    companion object {
        const val EXTRA_WELCOME = "welcome"
        const val EXTRA_MOVED = "moved"
        private const val STATE_ALERT = "alert_index"
        private const val STATE_ALERTS_OPEN = "alerts_open"
    }

    private lateinit var b: ActivityHomeBinding
    private var alertIndex = 0
    private var alertsOpen = false
    private var lastWs: Workspace? = null
    private var lastSummary: HomeSummary? = null
    private var entered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        b = ActivityHomeBinding.inflate(layoutInflater)
        setScreen(b.root)
        BottomNav.setup(this, BottomNav.Tab.HOME)
        alertIndex = savedInstanceState?.getInt(STATE_ALERT) ?: 0
        alertsOpen = savedInstanceState?.getBoolean(STATE_ALERTS_OPEN) ?: false

        b.btnProfile.setOnClickListener { BottomNav.open(this, BottomNav.Tab.PROFILE, BottomNav.Tab.HOME) }
        b.cardMargin.setOnClickListener { BottomNav.open(this, BottomNav.Tab.RECIPES, BottomNav.Tab.HOME) }
        b.cardScan.setOnClickListener { startActivity(Intent(this, ScanActivity::class.java)) }
        b.cardSimulator.setOnClickListener { startActivity(Intent(this, WhatIfActivity::class.java)) }
        b.cardShopping.setOnClickListener { startActivity(Intent(this, ShoppingListActivity::class.java)) }
        b.cardFix.setOnClickListener { toggleAlerts() }
        // design Minimal : bouton principal et cartes chiffres
        b.btnNewRecipe.setOnClickListener { startActivity(Intent(this, ComposeRecipeActivity::class.java)) }
        b.cardStatRecipes.setOnClickListener { BottomNav.open(this, BottomNav.Tab.RECIPES, BottomNav.Tab.HOME) }
        b.cardStatIngredients.setOnClickListener { BottomNav.open(this, BottomNav.Tab.INGREDIENTS, BottomNav.Tab.HOME) }
        b.btnNextAlert.setOnClickListener {
            alertIndex++
            Motion.tick(it)
            renderTodo()
        }

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_WELCOME, false)) {
            val moved = intent.getIntExtra(EXTRA_MOVED, 0)
            snack(if (moved > 0) resources.getQuantityString(R.plurals.home_welcome_moved, moved, moved) else getString(R.string.home_welcome), long = true)
        }

        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(HomeSummary.RECENT_DAYS.toLong())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(repo.workspace(), repo.history(since)) { ws, h -> ws to HomeSummary.build(ws, h) }
                    .collect { (ws, summary) -> render(ws, summary) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // l'heure a pu changer depuis le dernier affichage (Bonjour → Bonsoir)
        lastWs?.let { renderHeader(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_ALERT, alertIndex)
        outState.putBoolean(STATE_ALERTS_OPEN, alertsOpen)
    }

    private fun render(ws: Workspace, s: HomeSummary) {
        lastWs = ws
        lastSummary = s
        renderHeader(ws)

        b.bento.visibility = View.VISIBLE

        renderStats(s)
        renderLastCalc(ws)

        val d = HomeDashboard.build(ws, s)
        renderMargin(d)
        renderFix(s)
        renderPrice(d)
        renderTodo()
        renderBest(d)
        renderAlerts(s)
        enter(d)
    }

    // ---------- en-tête ----------

    private fun renderHeader(ws: Workspace) {
        val user = ws.user
        val business = user?.nomCommerce?.trim().orEmpty()
        val firstName = user?.prenom?.trim().orEmpty()
        val today = Fmt.date(this, System.currentTimeMillis())
        b.txtHeader.text = if (business.isNotEmpty()) getString(R.string.home_header, today, business) else today

        val hello = getString(
            when (Greeting.period(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))) {
                Greeting.Period.MORNING -> R.string.greeting_morning
                Greeting.Period.AFTERNOON -> R.string.greeting_afternoon
                Greeting.Period.EVENING -> R.string.greeting_evening
            }
        )
        b.txtGreeting.text = if (firstName.isNotEmpty()) getString(R.string.greeting_with_name, hello, firstName) else hello

        val initial = (firstName.ifEmpty { business }).firstOrNull()?.uppercaseChar()
        b.txtAvatar.text = initial?.toString().orEmpty()
        b.imgAvatar.visibility = if (initial == null) View.VISIBLE else View.GONE
    }

    // ---------- cartes du haut (design Minimal) : affichage seulement, chiffres déjà calculés ----------

    private fun renderStats(s: HomeSummary) {
        b.txtStatRecipes.text = count(s.recipeCount)
        b.txtStatIngredients.text = count(s.ingredientCount)
    }

    /** Recette modifiée en dernier : coût par part, prix conseillé et badge de marge (même calcul que la fiche recette). */
    private fun renderLastCalc(ws: Workspace) {
        val last = ws.recipes.maxByOrNull { it.dateMiseAJour }
        if (last == null) {
            b.txtLastCalcName.setText(R.string.home_last_calc)
            b.layoutLastCalcCost.visibility = View.GONE
            b.txtLastCalcSuggested.setText(R.string.home_last_calc_empty)
            b.cardLastCalc.setOnClickListener { startActivity(Intent(this, ComposeRecipeActivity::class.java)) }
            return
        }
        val c = ws.cost(last)
        b.txtLastCalcName.text = getString(R.string.home_last_calc_name, last.nom)
        b.layoutLastCalcCost.visibility = View.VISIBLE
        b.txtLastCalcCost.text = Fmt.money(this, c.costPerPortionMillimes)
        b.txtLastCalcSuggested.text = getString(R.string.home_last_calc_suggested, Fmt.money(this, c.suggestedPriceMillimes))
        val margin = c.realMarginPercent
        if (margin == null) {
            b.txtLastCalcBadge.visibility = View.GONE
        } else {
            val low = c.isBelowTarget(BigDecimal(last.margeVoulue.toString()))
            b.txtLastCalcBadge.visibility = View.VISIBLE
            b.txtLastCalcBadge.text = getString(if (low) R.string.badge_price_low else R.string.badge_margin_ok, Fmt.percent(this, margin))
            b.txtLastCalcBadge.setBackgroundResource(if (low) R.drawable.bg_badge_error else R.drawable.bg_badge_success)
            b.txtLastCalcBadge.setTextColor(getColor(if (low) R.color.hesba_error else R.color.hesba_success))
        }
        b.cardLastCalc.setOnClickListener { openRecipe(last.id) }
    }

    // ---------- cartes ----------

    private fun renderMargin(d: HomeDashboard) {
        val avg = d.averageMargin
        if (avg == null) {
            b.txtMargin.text = getString(R.string.value_missing)
            b.txtMarginSub.setText(R.string.bento_margin_none)
            b.ringMargin.set(null, null, animate = false)
            b.cardMargin.contentDescription = getString(R.string.bento_margin_label) + ", " + getString(R.string.bento_margin_none)
            return
        }
        val target = d.averageTarget
        showNumber(b.txtMargin, avg) { Fmt.percent(this, it) }
        b.txtMarginSub.text = resources.getQuantityString(R.plurals.bento_margin_sub, d.pricedCount, Fmt.percent(this, target), count(d.pricedCount))
        b.ringMargin.set(avg.toFloat(), target?.toFloat(), animate = !entered)
        b.cardMargin.contentDescription = getString(R.string.cd_bento_margin, Fmt.percent(this, avg), Fmt.percent(this, target))
    }

    private fun renderFix(s: HomeSummary) {
        val n = s.recipesNeedingAttention
        b.txtFixCount.text = Fmt.number(this, n.toBigDecimal(), 0)
        val bad = n > 0
        b.txtFixLabel.setText(if (bad) R.string.bento_fix_label else R.string.bento_fix_none)
        b.cardFix.setBackgroundResource(if (bad) R.drawable.bg_bento_bad else R.drawable.bg_bento_good)
        val ink = getColor(if (bad) R.color.hb_bento_bad_ink else R.color.hb_bento_good_ink)
        b.txtFixCount.setTextColor(ink)
        b.txtFixLabel.setTextColor(ink)
        b.imgFix.setImageResource(if (bad) R.drawable.ic_alert else R.drawable.ic_check)
        b.imgFix.setColorFilter(getColor(if (bad) R.color.hb_bento_bad_icon else R.color.hb_bento_good_ink))
        b.cardFix.isClickable = s.alerts.isNotEmpty()
        b.cardFix.contentDescription = b.txtFixCount.text.toString() + " " + b.txtFixLabel.text
    }

    private fun renderPrice(d: HomeDashboard) {
        val pc = d.topPriceChange
        val noIngredient = lastSummary?.ingredientCount == 0
        b.cardPrice.setOnClickListener {
            startActivity(Intent(this, if (noIngredient) NouvelIngredientActivity::class.java else InsightsActivity::class.java))
        }
        if (pc == null) {
            b.txtPriceName.setText(R.string.bento_prices_title)
            b.txtPriceDelta.visibility = View.GONE
            b.txtPriceFrom.text = if (noIngredient) getString(R.string.bento_prices_none)
            else getString(R.string.bento_prices_stable, count(HomeSummary.RECENT_DAYS))
            return
        }
        b.txtPriceName.text = pc.ingredient.nom
        b.txtPriceFrom.text = getString(R.string.alert_price_changed,
            Fmt.money(this, pc.entry.ancienPrixMillimes), Fmt.money(this, pc.entry.nouveauPrixMillimes), Fmt.relative(pc.entry.date))
        val pct = pc.percent
        b.txtPriceDelta.visibility = if (pct == null) View.GONE else View.VISIBLE
        if (pct != null) {
            b.txtPriceDelta.text = Fmt.signedPercent(this, pct)
            b.txtPriceDelta.setTextColor(getColor(if (pct.signum() > 0) R.color.hb_bento_bad_ink else R.color.hb_bento_good_ink))
        }
    }

    /** Une alerte à la fois, la plus importante d'abord ; « 1 / n › » passe à la suivante. */
    private fun renderTodo() {
        val ws = lastWs ?: return
        val s = lastSummary ?: return
        // premier usage : la carte guide vers le premier ingrédient, puis la première recette
        when (HomeHeadline.lead(s)) {
            HomeHeadline.Lead.FirstIngredient -> {
                todoSetup(R.string.home_lead_first_ingredient, R.string.step1_body, R.string.action_add, NouvelIngredientActivity::class.java)
                return
            }
            HomeHeadline.Lead.FirstRecipe -> {
                todoSetup(R.string.home_lead_first_recipe, R.string.step2_body, R.string.action_create, ComposeRecipeActivity::class.java)
                return
            }
            else -> Unit
        }
        val alerts = s.alerts
        if (alerts.isEmpty()) {
            b.txtTodoTitle.setText(R.string.bento_todo_none_title)
            b.txtTodoBody.setText(R.string.bento_todo_none_body)
            b.layoutTodoPath.visibility = View.GONE
            b.btnTodo.visibility = View.GONE
            b.btnNextAlert.visibility = View.GONE
            b.cardTodo.isClickable = false
            return
        }
        val i = Math.floorMod(alertIndex, alerts.size)
        alertIndex = i
        b.btnNextAlert.visibility = if (alerts.size > 1) View.VISIBLE else View.GONE
        b.btnNextAlert.text = getString(R.string.bento_todo_pager, count(i + 1), count(alerts.size)) +
            if (b.root.layoutDirection == View.LAYOUT_DIRECTION_RTL) "  ‹" else "  ›"
        b.btnNextAlert.contentDescription = getString(R.string.cd_bento_next, count(i + 1), count(alerts.size))
        b.btnTodo.visibility = View.VISIBLE
        b.cardTodo.isClickable = true

        when (val a = alerts[i]) {
            is Alert.BelowTarget -> {
                b.txtTodoTitle.text = a.recette.nom
                b.layoutTodoPath.visibility = View.VISIBLE
                b.txtTodoFrom.text = Fmt.percent(this, a.margin)
                b.txtTodoTo.text = Fmt.percent(this, a.target)
                b.txtTodoBody.text = getString(R.string.bento_todo_sold, Fmt.money(this, a.price), Fmt.money(this, a.cost))
                todoAction(a.recette.id, ws.cost(a.recette).suggestedPriceMillimes?.takeIf { it != a.price })
            }
            is Alert.Incomplete -> {
                b.txtTodoTitle.text = a.recette.nom
                b.layoutTodoPath.visibility = View.GONE
                b.txtTodoBody.text = getString(R.string.alert_incomplete, a.missing.joinToString())
                todoAction(a.recette.id, null)
            }
            is Alert.NoSellingPrice -> {
                b.txtTodoTitle.text = a.recette.nom
                b.layoutTodoPath.visibility = View.GONE
                b.txtTodoBody.text = getString(R.string.alert_no_price, Fmt.money(this, a.suggested))
                todoAction(a.recette.id, a.suggested)
            }
            is Alert.PriceChanged -> {
                b.txtTodoTitle.text = a.ingredient.nom
                b.layoutTodoPath.visibility = View.GONE
                b.txtTodoBody.text = getString(R.string.alert_price_changed,
                    Fmt.money(this, a.entry.ancienPrixMillimes), Fmt.money(this, a.entry.nouveauPrixMillimes), Fmt.relative(a.entry.date))
                b.btnTodo.setText(R.string.bento_todo_open)
                val open = View.OnClickListener { openIngredient(a.ingredient.id) }
                b.btnTodo.setOnClickListener(open)
                b.cardTodo.setOnClickListener(open)
            }
        }
    }

    private fun todoSetup(title: Int, body: Int, action: Int, target: Class<*>) {
        b.txtTodoTitle.setText(title)
        b.txtTodoBody.setText(body)
        b.layoutTodoPath.visibility = View.GONE
        b.btnNextAlert.visibility = View.GONE
        b.btnTodo.visibility = View.VISIBLE
        b.btnTodo.setText(action)
        val open = View.OnClickListener { startActivity(Intent(this, target)) }
        b.btnTodo.setOnClickListener(open)
        b.cardTodo.isClickable = true
        b.cardTodo.setOnClickListener(open)
    }

    /** Bouton de la carte « à faire » : fixer le prix conseillé en un geste, sinon ouvrir la recette. */
    private fun todoAction(recipeId: Long, suggested: Long?) {
        b.cardTodo.setOnClickListener { openRecipe(recipeId) }
        if (suggested == null) {
            b.btnTodo.setText(R.string.bento_todo_open)
            b.btnTodo.setOnClickListener { openRecipe(recipeId) }
            return
        }
        val price = Fmt.money(this, suggested)
        b.btnTodo.text = getString(R.string.bento_todo_set_price, price)
        b.btnTodo.setOnClickListener { v ->
            v.isEnabled = false
            lifecycleScope.launch {
                repo.setSellingPrice(recipeId, suggested)
                Motion.confirm(v)
                snack(getString(R.string.bento_price_applied, price))
                v.isEnabled = true
            }
        }
    }

    private fun renderBest(d: HomeDashboard) {
        val best = d.best
        if (best == null) {
            // aucune marge connue : la carte propose de composer une recette
            b.txtBestLabel.setText(R.string.action_new_recipe)
            b.txtBestValue.text = "+"
            b.txtBestName.visibility = View.GONE
            b.txtBestSub.text = getString(R.string.bento_new_recipe_sub)
            b.cardBest.setOnClickListener { startActivity(Intent(this, ComposeRecipeActivity::class.java)) }
            return
        }
        b.txtBestLabel.setText(R.string.bento_best_label)
        showNumber(b.txtBestValue, best.margin) { Fmt.percent(this, it) }
        b.txtBestName.visibility = View.VISIBLE
        b.txtBestName.text = best.recette.nom
        b.txtBestSub.text = getString(R.string.bento_best_sub, Fmt.money(this, best.priceMillimes), Fmt.money(this, best.costMillimes))
        b.cardBest.setOnClickListener { openRecipe(best.recette.id) }
    }

    // ---------- liste complète des alertes (carte « à corriger ») ----------

    private fun toggleAlerts() {
        if (lastSummary?.alerts.isNullOrEmpty()) return
        alertsOpen = !alertsOpen
        lastSummary?.let { renderAlerts(it) }
        if (alertsOpen) b.root.post { b.scroll.smoothScrollTo(0, b.alertsBlock.top) }
    }

    private fun renderAlerts(s: HomeSummary) {
        if (s.alerts.isEmpty()) alertsOpen = false
        b.alertsBlock.visibility = if (alertsOpen) View.VISIBLE else View.GONE
        if (!alertsOpen) return
        b.txtAlertsTitle.text = getString(R.string.home_alerts_count, s.alerts.size)
        b.listAlerts.removeAllViews()
        for (alert in s.alerts) b.listAlerts.addView(alertView(alert))
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
                v.alertRow.setOnClickListener { openIngredient(alert.ingredient.id) }
            }
        }
        return v.root
    }

    // ---------- mouvement ----------

    /** Première apparition : les cartes montent l'une après l'autre, les chiffres comptent jusqu'à leur valeur. */
    private fun enter(d: HomeDashboard) {
        if (entered) return
        entered = true
        val cards = listOf(b.header, b.cardStatRecipes, b.cardStatIngredients, b.cardLastCalc, b.btnNewRecipe, b.cardMargin, b.cardScan, b.cardFix, b.cardPrice, b.cardTodo, b.cardBest, b.cardSimulator, b.cardShopping)
        cards.forEachIndexed { i, v -> Motion.riseIn(v, i * Motion.STAGGER, 10f) }
        d.averageMargin?.let { countUp(b.txtMargin, it) }
        d.best?.let { countUp(b.txtBestValue, it.margin) }
    }

    /** Pendant que le chiffre compte, la nouvelle valeur est gardée et affichée à la fin. */
    private fun showNumber(view: TextView, value: BigDecimal, format: (BigDecimal) -> String) {
        if ((view.getTag(R.id.txtMargin) as? ValueAnimator)?.isRunning == true) {
            view.setTag(R.id.txtMarginSub, value)
            return
        }
        view.text = format(value)
    }

    private fun countUp(view: TextView, end: BigDecimal) {
        if (!Motion.enabled(this)) return
        val animator = ValueAnimator.ofFloat(0f, end.toFloat())
        animator.duration = 900
        animator.interpolator = Motion.decelerate
        animator.addUpdateListener {
            view.text = Fmt.percent(this, BigDecimal((it.animatedValue as Float).toDouble()).setScale(1, RoundingMode.HALF_UP))
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                view.text = Fmt.percent(this@HomeActivity, view.getTag(R.id.txtMarginSub) as? BigDecimal ?: end)
                view.setTag(R.id.txtMargin, null)
                view.setTag(R.id.txtMarginSub, null)
            }
        })
        view.setTag(R.id.txtMargin, animator)
        animator.start()
    }

    private fun count(n: Int) = Fmt.number(this, n.toBigDecimal(), 0)

    // ---------- navigation ----------

    private fun openRecipe(id: Long) =
        startActivity(Intent(this, RecipeDetailActivity::class.java).putExtra(RecipeDetailActivity.EXTRA_ID, id))

    private fun openIngredient(id: Long) =
        startActivity(Intent(this, NouvelIngredientActivity::class.java).putExtra(NouvelIngredientActivity.EXTRA_INGREDIENT_ID, id))
}

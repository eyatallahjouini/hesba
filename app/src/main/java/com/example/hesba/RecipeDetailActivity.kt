package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hesba.data.Recette
import com.example.hesba.databinding.ActivityRecipeDetailBinding
import com.example.hesba.databinding.ItemLedgerBinding
import com.example.hesba.databinding.ItemLinkBinding
import com.example.hesba.domain.Money
import com.example.hesba.domain.Workspace
import com.example.hesba.ui.BaseActivity
import com.example.hesba.ui.Dialogs
import com.example.hesba.ui.Fmt
import com.example.hesba.ui.RecipePdf
import com.example.hesba.ui.RecipePhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Fiche d'une recette : coût par part, prix pratiqué et marge réelle, conseiller de marge,
 * détail du coût, liste de courses, duplication, PDF, modification, suppression.
 */
class RecipeDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_ID = "recipe_id"
        const val EXTRA_CREATED = "created"
    }

    private lateinit var b: ActivityRecipeDetailBinding
    private var recipeId = 0L
    private var current: Recette? = null
    private var ws: Workspace? = null
    private var advisorMargin: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        b = ActivityRecipeDetailBinding.inflate(layoutInflater)
        setScreen(b.root)
        recipeId = intent.getLongExtra(EXTRA_ID, 0L)
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_CREATED, false)) snack(R.string.recipe_saved)

        link(b.actShopping, R.drawable.ic_cart, R.string.action_shopping_list, R.string.action_shopping_list_sub) {
            startActivity(Intent(this, ShoppingListActivity::class.java).putExtra(ShoppingListActivity.EXTRA_RECIPE, recipeId))
        }
        link(b.actDuplicate, R.drawable.ic_copy, R.string.action_duplicate, R.string.action_duplicate_sub) { duplicate() }
        link(b.actPdf, R.drawable.ic_share, R.string.action_share_pdf, R.string.action_share_pdf_sub) { sharePdf() }
        link(b.actEdit, R.drawable.ic_edit, R.string.action_edit_recipe, R.string.action_edit_recipe_sub) {
            startActivity(Intent(this, ComposeRecipeActivity::class.java).putExtra(ComposeRecipeActivity.EXTRA_RECETTE_ID, recipeId))
        }
        b.btnDelete.setOnClickListener { askDelete() }
        b.btnEditPrice.setOnClickListener { editPrice() }
        b.sliderMargin.addOnChangeListener { _, value, fromUser ->
            if (fromUser) { advisorMargin = value.toInt(); renderAdvisor() }
        }
        b.btnKeepMargin.setOnClickListener { keepMargin() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.workspace().collect { w ->
                    val r = w.recipes.firstOrNull { it.id == recipeId }
                    if (r == null) { finish(); return@collect }
                    ws = w
                    current = r
                    if (advisorMargin < 0) advisorMargin = r.margeVoulue.toInt().coerceIn(0, 90)
                    render(w, r)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val photo = withContext(Dispatchers.IO) { RecipePhotos.thumbnail(this@RecipeDetailActivity, recipeId, 720) }
            b.imgPhoto.visibility = if (photo != null) View.VISIBLE else View.GONE
            if (photo != null) { b.imgPhoto.setImageBitmap(photo); b.imgPhoto.clipToOutline = true }
        }
    }

    private fun link(row: ItemLinkBinding, icon: Int, title: Int, sub: Int, onClick: () -> Unit) {
        row.icon.setImageResource(icon)
        row.title.setText(title)
        row.subtitle.setText(sub)
        row.rowClick.setOnClickListener { onClick() }
    }

    private fun render(w: Workspace, r: Recette) {
        val cost = w.cost(r)
        setTitleText(r.nom)
        b.txtName.text = r.nom
        b.txtCost.text = Fmt.money(this, cost.costPerPortionMillimes)
        b.txtCostSub.text = getString(R.string.detail_cost_sub, resources.getQuantityString(R.plurals.portions, r.nombreParts, r.nombreParts), Fmt.money(this, cost.batchMillimes))

        val problems = cost.lines.filter { it.issue != null && it.issue != CostCalculator.Issue.ZERO_QUANTITY }
        b.txtIssues.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        if (problems.isNotEmpty()) {
            b.txtIssues.text = getString(R.string.detail_incomplete, problems.joinToString { it.ingredient?.name ?: getString(R.string.ingredient_deleted) })
        }

        // prix pratiqué et marge réelle
        val target = BigDecimal(r.margeVoulue.toString())
        b.txtPrice.text = Fmt.money(this, r.prixVenteMillimes)
        val margin = cost.realMarginPercent
        b.txtMargin.text = Fmt.percent(this, margin)
        b.txtMargin.setTextColor(getColor(if (margin != null && margin < target) R.color.hb_bad else R.color.hb_ink))
        b.txtProfit.text = if (r.prixVenteMillimes != null && cost.costPerPortionMillimes != null)
            Fmt.signedMoney(this, CostCalculator.profitPerPortion(r.prixVenteMillimes, cost.costPerPortionMillimes)) else getString(R.string.value_missing)
        b.lblSuggested.text = getString(R.string.label_suggested_for, Fmt.percent(this, target))
        b.txtSuggested.text = Fmt.money(this, cost.suggestedPriceMillimes)
        val suggested = cost.suggestedPriceMillimes
        b.btnApplySuggested.visibility = if (suggested != null && suggested != r.prixVenteMillimes) View.VISIBLE else View.GONE
        b.btnApplySuggested.text = getString(R.string.action_use_price, Fmt.money(this, suggested))
        b.btnApplySuggested.setOnClickListener { suggested?.let { setPrice(it) } }

        // ingrédient qui pèse le plus
        val top = cost.mostExpensive
        if (top != null && cost.ingredientsBatchExact.signum() > 0) {
            val share = top.costExact!!.multiply(BigDecimal(100)).divide(cost.ingredientsBatchExact, 0, RoundingMode.HALF_UP)
            b.txtTopIngredient.visibility = View.VISIBLE
            b.txtTopIngredient.text = getString(R.string.detail_top_ingredient, top.ingredient!!.name, Fmt.percent(this, share))
            b.txtTopIngredient.setOnClickListener {
                startActivity(Intent(this, WhatIfActivity::class.java).putExtra(WhatIfActivity.EXTRA_INGREDIENT, top.ingredient.id))
            }
        } else b.txtTopIngredient.visibility = View.GONE

        renderAdvisor()
        renderDetail(w, r, cost)
    }

    /** Conseiller de marge : déterministe, rien n'est enregistré tant que l'utilisateur ne le demande pas. */
    private fun renderAdvisor() {
        val r = current ?: return
        val w = ws ?: return
        val cost = w.cost(r).costPerPortionMillimes
        b.sliderMargin.value = advisorMargin.toFloat()
        val m = BigDecimal(advisorMargin)
        b.txtAdvisorMargin.text = Fmt.percent(this, m)
        val price = cost?.let { CostCalculator.suggestedPrice(it, m) }
        b.txtAdvisorPrice.text = Fmt.money(this, price)
        b.txtAdvisorProfit.text = if (price != null && cost != null) Fmt.money(this, price - cost) else getString(R.string.value_missing)
        val selling = r.prixVenteMillimes
        b.txtAdvisorEffect.text = when {
            price == null || cost == null -> getString(R.string.advisor_need_cost)
            selling == null -> getString(R.string.advisor_no_price)
            else -> {
                val diff = price - selling
                getString(R.string.advisor_effect, Fmt.money(this, selling), Fmt.signedMoney(this, diff))
            }
        }
        val changed = advisorMargin != r.margeVoulue.toInt() || r.margeVoulue % 1.0 != 0.0
        b.btnKeepMargin.isEnabled = changed && price != null
        b.btnKeepMargin.text = getString(R.string.action_keep_margin, Fmt.percent(this, m), Fmt.money(this, price))
    }

    private fun renderDetail(w: Workspace, r: Recette, cost: CostCalculator.RecipeCost) {
        b.listDetail.removeAllViews()
        fun row(label: String, detail: String?, value: String, strong: Boolean = false) {
            val v = ItemLedgerBinding.inflate(LayoutInflater.from(this), b.listDetail, false)
            v.ledgerLabel.text = label
            if (detail != null) { v.ledgerDetail.text = detail; v.ledgerDetail.visibility = View.VISIBLE }
            v.ledgerValue.text = value
            if (strong) {
                v.ledgerLabel.setTextAppearance(R.style.TextAppearance_Hesba_Label)
                v.ledgerValue.setTextAppearance(R.style.TextAppearance_Hesba_Money)
            }
            b.listDetail.addView(v.root)
        }
        fun rule() = b.listDetail.addView(View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.hairline)).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.sp_1); bottomMargin = resources.getDimensionPixelSize(R.dimen.sp_1)
            }
            setBackgroundColor(getColor(R.color.hb_line))
        })

        for (lc in cost.lines) {
            val ing = lc.ingredient
            val qty = Fmt.quantity(this, lc.line.quantity, lc.line.unit)
            val detail = when {
                ing == null -> getString(R.string.ingredient_deleted)
                ing.yieldPercent < BigDecimal(100) -> getString(R.string.detail_line_yield, qty, Fmt.number(this, ing.yieldPercent, 0))
                else -> qty
            }
            val value = when (lc.issue) {
                CostCalculator.Issue.MISSING_PRICE -> getString(R.string.price_missing)
                CostCalculator.Issue.UNIT_MISMATCH, CostCalculator.Issue.INVALID_PACKAGE -> getString(R.string.unit_mismatch)
                else -> Fmt.money(this, lc.costMillimes ?: 0)
            }
            row(ing?.name ?: getString(R.string.ingredient_deleted), detail, value)
        }
        if (r.gazParLotMillimes > 0) row(getString(R.string.field_energy), null, Fmt.money(this, r.gazParLotMillimes))
        if (r.mainOeuvreParLotMillimes > 0) row(getString(R.string.field_labor), null, Fmt.money(this, r.mainOeuvreParLotMillimes))
        rule()
        row(getString(R.string.label_batch_cost), null, Fmt.money(this, cost.batchMillimes), strong = true)
        row(getString(R.string.detail_divided, r.nombreParts), null, Fmt.money(this, CostCalculator.costPerPortion(cost.batchExact, r.nombreParts)))
        if (r.emballageParPartMillimes > 0) row(getString(R.string.field_packaging), null, "+ " + Fmt.money(this, r.emballageParPartMillimes))
        val fixed = RecipeCosting.fixedPerPortion(w.user)
        if (fixed > 0) row(getString(R.string.detail_fixed), getString(R.string.detail_fixed_sub), "+ " + Fmt.money(this, fixed))
        rule()
        row(getString(R.string.label_cost_per_portion), null, Fmt.money(this, cost.costPerPortionMillimes), strong = true)
    }

    // ---------- actions ----------

    private fun editPrice() {
        val r = current ?: return
        Dialogs.input(
            this, getString(R.string.dialog_price_title), getString(R.string.field_selling_price),
            initial = Fmt.inputAmount(this, r.prixVenteMillimes), money = true, suffix = getString(R.string.currency_short),
            validate = { if (Money.parseMillimes(it) == null) getString(R.string.error_amount) else null }
        ) { setPrice(Money.parseMillimes(it)!!) }
    }

    private fun setPrice(millimes: Long) = lifecycleScope.launch {
        repo.setSellingPrice(recipeId, millimes)
        snack(getString(R.string.price_updated, Fmt.money(this@RecipeDetailActivity, millimes)))
    }

    private fun keepMargin() {
        val r = current ?: return
        val cost = ws?.cost(r)?.costPerPortionMillimes ?: return
        val price = CostCalculator.suggestedPrice(cost, BigDecimal(advisorMargin)) ?: return
        Dialogs.confirm(
            this, getString(R.string.dialog_keep_margin_title),
            getString(R.string.dialog_keep_margin_body, Fmt.percent(this, BigDecimal(advisorMargin)), Fmt.money(this, price)),
            R.string.action_apply
        ) {
            lifecycleScope.launch {
                db.recetteDao().modifier(r.copy(margeVoulue = advisorMargin.toDouble(), prixVenteMillimes = price, dateMiseAJour = System.currentTimeMillis()))
                snack(R.string.margin_updated)
            }
        }
    }

    private fun duplicate() {
        val r = current ?: return
        lifecycleScope.launch {
            val id = repo.duplicateRecipe(r.id, getString(R.string.copy_name, r.nom)) ?: return@launch
            withContext(Dispatchers.IO) { RecipePhotos.copy(this@RecipeDetailActivity, r.id, id) }
            startActivity(Intent(this@RecipeDetailActivity, ComposeRecipeActivity::class.java).putExtra(ComposeRecipeActivity.EXTRA_RECETTE_ID, id))
            snack(R.string.recipe_duplicated)
        }
    }

    private fun sharePdf() {
        val r = current ?: return
        val w = ws ?: return
        val options = arrayOf(getString(R.string.pdf_with_prices), getString(R.string.pdf_without_prices))
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pdf_dialog_title)
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    val uri = withContext(Dispatchers.IO) { runCatching { RecipePdf.create(this@RecipeDetailActivity, w, r, showPrices = which == 0) }.getOrNull() }
                    if (uri == null) { snack(R.string.error_pdf); return@launch }
                    val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, r.nom).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, getString(R.string.pdf_share_title)))
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun askDelete() {
        val r = current ?: return
        Dialogs.confirm(this, getString(R.string.dialog_delete_recipe_title, r.nom), getString(R.string.dialog_delete_recipe_body), R.string.action_delete, destructive = true) {
            lifecycleScope.launch {
                repo.deleteRecipe(r)
                withContext(Dispatchers.IO) { RecipePhotos.delete(this@RecipeDetailActivity, r.id) }
                finish()
            }
        }
    }
}

package com.example.hesba

import android.os.Bundle
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.hesba.data.HesbaRepository
import com.example.hesba.data.Ingredient
import com.example.hesba.data.PrixHistorique
import com.example.hesba.databinding.ActivityWhatIfBinding
import com.example.hesba.domain.Money
import com.example.hesba.domain.NumberParser
import com.example.hesba.domain.WhatIf
import com.example.hesba.domain.Workspace
import com.example.hesba.ui.BaseActivity
import com.example.hesba.ui.Dialogs
import com.example.hesba.ui.Fmt
import com.example.hesba.ui.ImpactViews
import com.example.hesba.ui.Picker
import kotlinx.coroutines.launch

/**
 * Simulateur « et si » : changer le prix d'un ingrédient (montant, pourcentage ou nouveau prix)
 * et voir les recettes touchées. RIEN n'est enregistré sans confirmation explicite.
 */
class WhatIfActivity : BaseActivity() {

    companion object { const val EXTRA_INGREDIENT = "ingredient" }

    private lateinit var b: ActivityWhatIfBinding
    private var ws: Workspace? = null
    private var ingredient: Ingredient? = null
    private var newPrice: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        b = ActivityWhatIfBinding.inflate(layoutInflater)
        setScreen(b.root)
        setTitleText(getString(R.string.tool_whatif))

        b.btnIngredient.setOnClickListener { choose() }
        b.groupMode.setOnCheckedStateChangeListener { _, _ -> b.editValue.text?.clear(); updateHint(); simulate() }
        b.editValue.doAfterTextChanged { b.tilValue.error = null; simulate() }
        b.btnApply.setOnClickListener { confirm() }
        updateHint()

        lifecycleScope.launch {
            ws = repo.snapshot()
            val preset = intent.getLongExtra(EXTRA_INGREDIENT, 0L)
            ws?.ingredientsById?.get(preset)?.let { select(it) }
            if (ws?.ingredients.isNullOrEmpty()) {
                b.btnIngredient.isEnabled = false
                b.txtCurrent.setText(R.string.whatif_no_ingredient)
            }
        }
    }

    private fun choose() {
        val w = ws ?: return
        val options = w.ingredients.map { i ->
            val n = w.recipesUsing(i.id).size
            Picker.Option(i, i.nom, resources.getQuantityString(R.plurals.used_in, n, n) + " · " + Fmt.money(this, i.prixMillimes))
        }
        Picker.show(this, getString(R.string.whatif_choose), options, getString(R.string.pick_ingredient_empty)) { select(it) }
    }

    private fun select(i: Ingredient) {
        ingredient = i
        b.btnIngredient.text = i.nom
        b.txtCurrent.text = getString(R.string.whatif_current, Fmt.money(this, i.prixMillimes), Fmt.baseQuantity(this, i.quantiteEmballage, i.unite))
        b.blockChange.visibility = View.VISIBLE
        b.editValue.text?.clear()
        simulate()
    }

    private fun updateHint() {
        when (b.groupMode.checkedChipId) {
            R.id.modeAmount -> { b.tilValue.hint = getString(R.string.whatif_hint_amount); b.tilValue.suffixText = getString(R.string.currency_short) }
            R.id.modePrice -> { b.tilValue.hint = getString(R.string.whatif_hint_price); b.tilValue.suffixText = getString(R.string.currency_short) }
            else -> { b.tilValue.hint = getString(R.string.whatif_hint_percent); b.tilValue.suffixText = getString(R.string.percent_sign) }
        }
    }

    private fun change(): WhatIf.Change? {
        val raw = b.editValue.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val v = NumberParser.parse(raw) ?: return null
        return when (b.groupMode.checkedChipId) {
            R.id.modeAmount -> WhatIf.Change.Amount(Money.toMillimes(v))
            R.id.modePrice -> if (v.signum() < 0) null else WhatIf.Change.NewPrice(Money.toMillimes(v))
            else -> WhatIf.Change.Percent(v)
        }
    }

    private fun simulate() {
        val w = ws ?: return
        val i = ingredient ?: return
        val c = change()
        newPrice = c?.let { WhatIf.newPackagePrice(i.prixMillimes, it) }
        val p = newPrice
        val impacts = if (p != null) WhatIf.simulate(w, i.id, p) else emptyList()
        b.txtNewPrice.text = if (p != null) getString(R.string.whatif_new_price, Fmt.money(this, i.prixMillimes), Fmt.money(this, p)) else ""
        if (c != null && p == null) b.tilValue.error = getString(R.string.whatif_need_price)
        val used = w.recipesUsing(i.id).size
        b.txtImpactTitle.text = resources.getQuantityString(R.plurals.whatif_affected, used, used)
        b.txtNoImpact.visibility = if (used == 0) View.VISIBLE else View.GONE
        if (p != null) ImpactViews.fill(b.listImpacts, impacts, clickable = false) else b.listImpacts.removeAllViews()
        b.btnApply.isEnabled = p != null && p != i.prixMillimes
    }

    private fun confirm() {
        val i = ingredient ?: return
        val p = newPrice ?: return
        Dialogs.confirm(
            this, getString(R.string.whatif_confirm_title),
            getString(R.string.whatif_confirm_body, i.nom, Fmt.money(this, i.prixMillimes), Fmt.money(this, p)),
            R.string.action_apply
        ) {
            lifecycleScope.launch {
                val r = repo.applyPrices(listOf(HesbaRepository.PriceUpdate(i.id, p)), PrixHistorique.SOURCE_SIMULATION)
                Toasts.long(this@WhatIfActivity, resources.getQuantityString(R.plurals.recalculated, r.impacts.size, r.impacts.size))
                finish()
            }
        }
    }
}

package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.hesba.data.Recette
import com.example.hesba.databinding.ActivityShoppingListBinding
import com.example.hesba.databinding.ItemLedgerBinding
import com.example.hesba.domain.NumberParser
import com.example.hesba.domain.ShoppingList
import com.example.hesba.domain.Workspace
import com.example.hesba.ui.BaseActivity
import com.example.hesba.ui.Fmt
import com.example.hesba.ui.Picker
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import java.math.BigDecimal

/** Liste de courses : une recette + une quantité à produire → ingrédients, quantités à acheter, coût estimé. */
class ShoppingListActivity : BaseActivity() {

    companion object { const val EXTRA_RECIPE = "recipe" }

    private lateinit var b: ActivityShoppingListBinding
    private var ws: Workspace? = null
    private var recipe: Recette? = null
    private var result: ShoppingList.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        b = ActivityShoppingListBinding.inflate(layoutInflater)
        setScreen(b.root)
        setTitleText(getString(R.string.tool_shopping))
        b.btnRecipe.setOnClickListener { choose() }
        b.editPortions.doAfterTextChanged { b.tilPortions.error = null; compute() }
        b.btnShare.setOnClickListener { share() }

        lifecycleScope.launch {
            val w = repo.snapshot()
            ws = w
            if (w.recipes.isEmpty()) {
                b.btnRecipe.visibility = View.GONE
                b.empty.root.visibility = View.VISIBLE
                b.empty.emptyIcon.setImageResource(R.drawable.ic_cart)
                b.empty.emptyTitle.setText(R.string.shopping_empty_title)
                b.empty.emptyBody.setText(R.string.shopping_empty_body)
                b.empty.emptyAction.setText(R.string.action_new_recipe)
                b.empty.emptyAction.setOnClickListener { startActivity(Intent(this@ShoppingListActivity, ComposeRecipeActivity::class.java)); finish() }
                return@launch
            }
            w.recipes.firstOrNull { it.id == intent.getLongExtra(EXTRA_RECIPE, 0L) }?.let { select(it) }
        }
    }

    private fun choose() {
        val w = ws ?: return
        Picker.show(this, getString(R.string.shopping_choose),
            w.recipes.map { Picker.Option(it, it.nom, resources.getQuantityString(R.plurals.portions, it.nombreParts, it.nombreParts)) },
            getString(R.string.search_no_result_body)
        ) { select(it) }
    }

    private fun select(r: Recette) {
        recipe = r
        b.btnRecipe.text = r.nom
        b.blockQty.visibility = View.VISIBLE
        b.groupBatches.removeAllViews()
        for (n in listOf(1, 2, 3, 5, 10)) {
            b.groupBatches.addView(Chip(this).apply {
                text = getString(R.string.shopping_batches, n)
                contentDescription = resources.getQuantityString(R.plurals.portions, r.nombreParts * n, r.nombreParts * n)
                setOnClickListener { b.editPortions.setText((r.nombreParts * n).toString()) }
            })
        }
        b.editPortions.setText(r.nombreParts.toString())
    }

    private fun compute() {
        val w = ws ?: return
        val r = recipe ?: return
        val portions = NumberParser.parseCount(b.editPortions.text?.toString()) ?: 0   // chiffres latins ou arabes
        if (portions <= 0) {
            b.tilPortions.error = getString(R.string.error_portions)
            b.listItems.removeAllViews(); b.btnShare.isEnabled = false; result = null
            return
        }
        val res = ShoppingList.build(w, r, portions) ?: return
        result = res
        b.listItems.removeAllViews()
        for (item in res.items) {
            val v = ItemLedgerBinding.inflate(LayoutInflater.from(this), b.listItems, false)
            val i = item.ingredient
            v.ledgerLabel.text = i.nom
            val buy = Fmt.baseQuantity(this, item.grossBase, i.unite)
            val pkg = if (item.packages != null) getString(R.string.shopping_packages, item.packages, Fmt.baseQuantity(this, i.quantiteEmballage, i.unite)) else ""
            val waste = if (i.rendementPourcent < 100.0) " · " + getString(R.string.shopping_with_waste, Fmt.baseQuantity(this, item.netBase, i.unite)) else ""
            v.ledgerDetail.text = listOf(buy, pkg).filter { it.isNotEmpty() }.joinToString(" · ") + waste
            v.ledgerDetail.visibility = View.VISIBLE
            v.ledgerValue.text = Fmt.money(this, item.costMillimes)
            b.listItems.addView(v.root)
        }
        b.txtTotal.text = Fmt.money(this, res.totalMillimes)
        b.txtTotalPackages.text = Fmt.money(this, res.packagesTotalMillimes)
        // prix manquants et lignes impossibles à convertir : signalés, jamais oubliés en silence
        val warnings = listOfNotNull(
            res.missingPrices.takeIf { it.isNotEmpty() }?.let { getString(R.string.shopping_missing, it.joinToString { i -> i.nom }) },
            res.unconvertible.takeIf { it.isNotEmpty() }?.let { getString(R.string.shopping_unconvertible, it.joinToString { i -> i.nom }) }
        )
        b.txtMissing.visibility = if (warnings.isEmpty()) View.GONE else View.VISIBLE
        b.txtMissing.text = warnings.joinToString("\n")
        b.btnShare.isEnabled = res.items.isNotEmpty()
    }

    private fun share() {
        val r = recipe ?: return
        val res = result ?: return
        val portions = Fmt.number(this, BigDecimal(NumberParser.parseCount(b.editPortions.text?.toString()) ?: 0), 0)
        val text = buildString {
            appendLine(getString(R.string.shopping_share_title, r.nom, portions))
            for (item in res.items) {
                val i = item.ingredient
                val pkg = item.packages?.let { " (" + getString(R.string.shopping_packages, it, Fmt.baseQuantity(this@ShoppingListActivity, i.quantiteEmballage, i.unite)) + ")" } ?: ""
                appendLine("• ${i.nom} : ${Fmt.baseQuantity(this@ShoppingListActivity, item.grossBase, i.unite)}$pkg")
            }
            appendLine()
            append(getString(R.string.shopping_share_total, Fmt.money(this@ShoppingListActivity, res.packagesTotalMillimes)))
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), getString(R.string.action_share_list)))
    }
}

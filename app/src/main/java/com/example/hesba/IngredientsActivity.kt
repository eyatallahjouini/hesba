package com.example.hesba

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.hesba.data.Ingredient
import com.example.hesba.databinding.ActivityIngredientsBinding
import com.example.hesba.databinding.ItemIngredientBinding
import com.example.hesba.domain.Money
import com.example.hesba.domain.TextNormalizer
import com.example.hesba.ui.BaseActivity
import com.example.hesba.ui.Fmt
import com.example.hesba.ui.Motion
import com.example.hesba.ui.Transitions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.math.BigDecimal

/** Ingrédients : recherche en direct, prix payé, prix ramené au kg / L / pièce, nombre de recettes. */
class IngredientsActivity : BaseActivity() {

    private lateinit var b: ActivityIngredientsBinding
    private val query = MutableStateFlow("")
    private val adapter = Adapter()
    private val entered = Motion.Flag()

    data class Row(val ingredient: Ingredient, val usedIn: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        b = ActivityIngredientsBinding.inflate(layoutInflater)
        setScreen(b.root)
        BottomNav.setup(this, BottomNav.Tab.INGREDIENTS)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        // design Minimal : chaque ingrédient est une carte séparée de 12 dp, plus de filet entre les lignes
        b.btnAdd.setOnClickListener { Transitions.open(this, b.btnAdd, Intent(this, NouvelIngredientActivity::class.java), "add_ingredient") }
        b.search.editSearch.setHint(R.string.search_ingredients_hint)
        b.search.editSearch.doAfterTextChanged {
            query.value = it?.toString().orEmpty()
            b.search.btnClear.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        b.search.btnClear.setOnClickListener { b.search.editSearch.text.clear() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(repo.workspace(), db.ingredientDao().usages(), query) { ws, usages, q -> Triple(ws, usages.associate { it.ingredientId to it.recettes }, q) }
                    .collect { (ws, usages, q) ->
                        val all = ws.ingredients.map { Row(it, usages[it.id] ?: 0) }
                        val shown = if (q.isBlank()) all else {
                            val words = TextNormalizer.tokens(q)
                            all.filter { r -> val n = TextNormalizer.normalize(r.ingredient.nom); words.all { n.contains(it) } }
                        }
                        show(all.size, shown, q)
                    }
            }
        }
    }

    private fun show(total: Int, rows: List<Row>, q: String) {
        b.txtCount.text = if (q.isBlank()) resources.getQuantityString(R.plurals.ingredients_count, total, total)
        else resources.getQuantityString(R.plurals.results_count, rows.size, rows.size)
        adapter.submitList(rows) { if (rows.isNotEmpty()) Motion.enterOnce(b.list, entered) }
        val empty = rows.isEmpty()
        b.empty.root.visibility = if (empty) View.VISIBLE else View.GONE
        b.slate.visibility = if (empty) View.GONE else View.VISIBLE
        if (!empty) return
        if (total == 0) {
            b.empty.emptyIcon.setImageResource(R.drawable.ic_ingredients)
            b.empty.emptyTitle.setText(R.string.ingredients_empty_title)
            b.empty.emptyBody.setText(R.string.ingredients_empty_body)
            b.empty.emptyAction.setText(R.string.action_add_ingredient)
            b.empty.emptyAction.setOnClickListener { startActivity(Intent(this, NouvelIngredientActivity::class.java)) }
        } else {
            b.empty.emptyIcon.setImageResource(R.drawable.ic_search)
            b.empty.emptyTitle.text = getString(R.string.search_no_result_title, q.trim())
            b.empty.emptyBody.setText(R.string.search_no_result_ingredient)
            b.empty.emptyAction.text = getString(R.string.action_create_named, q.trim())
            b.empty.emptyAction.setOnClickListener {
                startActivity(Intent(this, NouvelIngredientActivity::class.java).putExtra(NouvelIngredientActivity.EXTRA_NAME, q.trim()))
            }
        }
    }

    companion object {
        /** « 1,200 DT / kg » : prix ramené à 1 kg, 1 L ou 1 pièce, rendement compris. */
        fun normalizedPrice(context: Context, i: Ingredient): String? {
            val unitPrice = CostCalculator.unitPrice(i.prixMillimes, BigDecimal(i.quantiteEmballage.toString()), BigDecimal(i.rendementPourcent.toString())) ?: return null
            val (factor, unit) = when (i.unite) { "g" -> 1000 to "kg"; "ml" -> 1000 to "l"; else -> 1 to "piece" }
            val per = Money.roundMillimes(unitPrice.multiply(BigDecimal(factor)))
            return context.getString(R.string.price_per_unit, Fmt.money(context, per), Fmt.unitLabel(context, unit))
        }
    }

    private inner class Adapter : ListAdapter<Row, Holder>(Diff) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemIngredientBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    }

    private inner class Holder(val v: ItemIngredientBinding) : RecyclerView.ViewHolder(v.root) {
        fun bind(row: Row) {
            val ctx = this@IngredientsActivity
            val i = row.ingredient
            v.name.text = i.nom
            val parts = mutableListOf(Fmt.baseQuantity(ctx, i.quantiteEmballage, i.unite))
            if (i.rendementPourcent < 100.0) parts += getString(R.string.yield_short, Fmt.number(ctx, i.rendementPourcent, 0))
            parts += if (row.usedIn == 0) getString(R.string.used_in_none) else resources.getQuantityString(R.plurals.used_in, row.usedIn, row.usedIn)
            v.details.text = parts.joinToString(" · ")
            if (i.prixMillimes == null) {
                v.price.setText(R.string.price_missing)
                v.price.setTextColor(getColor(R.color.hesba_error))
                v.unitPrice.text = ""
            } else {
                v.price.text = Fmt.money(ctx, i.prixMillimes)
                v.price.setTextColor(getColor(R.color.hesba_text_primary))
                v.unitPrice.text = normalizedPrice(ctx, i)
            }
            v.row.contentDescription = listOf(i.nom, v.price.text, v.details.text, v.unitPrice.text).filter { it.isNotBlank() }.joinToString(", ")
            v.row.transitionName = null
            v.row.setOnClickListener {
                Transitions.open(ctx, v.row, Intent(ctx, NouvelIngredientActivity::class.java).putExtra(NouvelIngredientActivity.EXTRA_INGREDIENT_ID, i.id), "ingredient_${i.id}")
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.ingredient.id == b.ingredient.id
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    }
}

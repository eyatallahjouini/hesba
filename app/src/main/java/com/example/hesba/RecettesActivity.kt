package com.example.hesba

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Outline
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.hesba.databinding.ActivityRecipesBinding
import com.example.hesba.databinding.ItemRecipeBinding
import com.example.hesba.domain.RecipeSearch
import com.example.hesba.domain.Workspace
import com.example.hesba.ui.BaseActivity
import com.example.hesba.ui.Fmt
import com.example.hesba.ui.RecipePhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/** Mes recettes : recherche fixée en haut (en direct, sans accents ni casse, arabe), tri, états vides. */
class RecettesActivity : BaseActivity() {

    private lateinit var b: ActivityRecipesBinding
    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(RecipeSearch.Sort.NAME)
    private val adapter = Adapter()
    private val thumbs = LruCache<Long, Bitmap>(40)
    private var total = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        b = ActivityRecipesBinding.inflate(layoutInflater)
        setScreen(b.root)
        BottomNav.setup(this, BottomNav.Tab.RECIPES)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.btnNew.setOnClickListener { startActivity(Intent(this, ComposeRecipeActivity::class.java)) }

        b.search.editSearch.setHint(R.string.search_recipes_hint)
        b.search.editSearch.doAfterTextChanged {
            query.value = it?.toString().orEmpty()
            b.search.btnClear.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        b.search.editSearch.setOnEditorActionListener { v, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0); true
            } else false
        }
        b.search.btnClear.setOnClickListener { b.search.editSearch.text.clear() }
        b.groupSort.setOnCheckedStateChangeListener { _, ids ->
            sort.value = when (ids.firstOrNull()) {
                R.id.sortCost -> RecipeSearch.Sort.COST
                R.id.sortMargin -> RecipeSearch.Sort.MARGIN
                else -> RecipeSearch.Sort.NAME
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(repo.workspace(), query, sort) { ws, q, s -> Triple(ws, q, s) }.collect { (ws, q, s) -> show(ws, q, s) }
            }
        }
    }

    private suspend fun show(ws: Workspace, q: String, s: RecipeSearch.Sort) {
        val all = ws.recipes.map { RecipeSearch.Item(it, ws.cost(it)) }
        total = all.size
        val result = RecipeSearch.apply(all, q, s)
        withContext(Dispatchers.IO) {
            result.forEach { item -> if (thumbs.get(item.recette.id) == null) RecipePhotos.thumbnail(this@RecettesActivity, item.recette.id)?.let { thumbs.put(item.recette.id, it) } }
        }
        b.txtCount.text = when {
            q.isBlank() -> resources.getQuantityString(R.plurals.recipes_count, total, total)
            else -> resources.getQuantityString(R.plurals.results_count, result.size, result.size)
        }
        adapter.submitList(result)
        val empty = result.isEmpty()
        b.empty.root.visibility = if (empty) View.VISIBLE else View.GONE
        b.list.visibility = if (empty) View.GONE else View.VISIBLE
        b.columns.visibility = if (empty) View.INVISIBLE else View.VISIBLE
        b.groupSort.visibility = if (total < 2) View.GONE else View.VISIBLE
        if (empty) {
            b.empty.emptyIcon.setImageResource(if (total == 0) R.drawable.ic_recipes else R.drawable.ic_search)
            if (total == 0) {
                b.empty.emptyTitle.setText(R.string.recipes_empty_title)
                b.empty.emptyBody.setText(if (ws.ingredients.isEmpty()) R.string.recipes_empty_no_ingredient else R.string.recipes_empty_body)
                if (ws.ingredients.isEmpty()) {
                    b.empty.emptyAction.setText(R.string.action_add_ingredient)
                    b.empty.emptyAction.setOnClickListener { startActivity(Intent(this, NouvelIngredientActivity::class.java)) }
                } else {
                    b.empty.emptyAction.setText(R.string.action_new_recipe)
                    b.empty.emptyAction.setOnClickListener { startActivity(Intent(this, ComposeRecipeActivity::class.java)) }
                }
            } else {
                b.empty.emptyTitle.text = getString(R.string.search_no_result_title, q.trim())
                b.empty.emptyBody.setText(R.string.search_no_result_body)
                b.empty.emptyAction.setText(R.string.action_clear_search)
                b.empty.emptyAction.setOnClickListener { b.search.editSearch.text.clear() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        thumbs.evictAll()   // une photo a pu changer dans l'éditeur
    }

    private inner class Adapter : ListAdapter<RecipeSearch.Item, Holder>(Diff) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemRecipeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    }

    private inner class Holder(val v: ItemRecipeBinding) : RecyclerView.ViewHolder(v.root) {
        fun bind(item: RecipeSearch.Item) {
            val ctx = this@RecettesActivity
            val r = item.recette
            val c = item.cost
            v.name.text = r.nom
            v.monogram.text = r.nom.trim().take(1).uppercase()
            val photo = thumbs.get(r.id)
            v.photo.visibility = if (photo != null) View.VISIBLE else View.GONE
            if (photo != null) {
                v.photo.setImageBitmap(photo)
                v.photo.outlineProvider = Rounded
                v.photo.clipToOutline = true
            }
            v.details.text = resources.getQuantityString(R.plurals.portions, r.nombreParts, r.nombreParts)
            v.cost.text = Fmt.money(ctx, c.costPerPortionMillimes)
            v.price.text = if (r.prixVenteMillimes != null) getString(R.string.list_price, Fmt.money(ctx, r.prixVenteMillimes))
            else getString(R.string.list_no_price)

            val target = BigDecimal(r.margeVoulue.toString())
            val margin = c.realMarginPercent
            v.tag.visibility = View.VISIBLE
            when {
                !c.isComplete -> {
                    v.tag.setText(R.string.tag_incomplete)
                    style(v, R.drawable.bg_tag, R.color.hb_ink)
                }
                margin == null -> v.tag.visibility = View.GONE
                margin < target -> {
                    v.tag.text = getString(R.string.tag_margin, Fmt.percent(ctx, margin))
                    style(v, R.drawable.bg_tag_bad, R.color.hb_bad)
                }
                else -> {
                    v.tag.text = getString(R.string.tag_margin, Fmt.percent(ctx, margin))
                    style(v, R.drawable.bg_tag_good, R.color.hb_good)
                }
            }
            v.row.contentDescription = getString(
                R.string.cd_recipe_row, r.nom, Fmt.money(ctx, c.costPerPortionMillimes),
                if (margin != null) Fmt.percent(ctx, margin) else getString(R.string.value_missing)
            )
            v.row.setOnClickListener {
                startActivity(Intent(ctx, RecipeDetailActivity::class.java).putExtra(RecipeDetailActivity.EXTRA_ID, r.id))
            }
        }

        private fun style(v: ItemRecipeBinding, bg: Int, color: Int) {
            v.tag.setBackgroundResource(bg)
            v.tag.setTextColor(getColor(color))
        }
    }

    private object Diff : DiffUtil.ItemCallback<RecipeSearch.Item>() {
        override fun areItemsTheSame(a: RecipeSearch.Item, b: RecipeSearch.Item) = a.recette.id == b.recette.id
        override fun areContentsTheSame(a: RecipeSearch.Item, b: RecipeSearch.Item) = a == b
    }

    object Rounded : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, view.resources.displayMetrics.density * 10)
        }
    }
}

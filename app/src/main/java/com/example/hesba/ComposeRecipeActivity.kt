package com.example.hesba

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupMenu
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.Recette
import com.example.hesba.data.Utilisateur
import com.example.hesba.databinding.ActivityComposeRecipeBinding
import com.example.hesba.databinding.ItemRecipeLineBinding
import com.example.hesba.domain.MeasureUnit
import com.example.hesba.domain.Money
import com.example.hesba.domain.NumberParser
import com.example.hesba.ui.BaseActivity
import com.example.hesba.ui.Fmt
import com.example.hesba.ui.Picker
import com.example.hesba.ui.RecipePhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/**
 * Créer / modifier une recette : coût calculé en direct à chaque frappe (CostCalculator),
 * conversions d'unités par ligne, coûts cachés facultatifs, marge voulue et prix de vente.
 */
class ComposeRecipeActivity : BaseActivity() {

    companion object {
        const val EXTRA_RECETTE_ID = "EXTRA_RECETTE_ID"
        const val DEFAULT_MARGIN = 40
    }

    /** Brouillon conservé pendant une rotation ou un changement de langue. */
    class Draft : ViewModel() {
        data class Line(val ingredientId: Long, var quantity: String, var unit: String)
        val lines = mutableListOf<Line>()
        var loaded = false
        var editing: Recette? = null
        var photo: Bitmap? = null
        var priceTouched = false
    }

    private val draft: Draft by viewModels()
    private lateinit var b: ActivityComposeRecipeBinding
    private var ingredients: Map<Long, Ingredient> = emptyMap()
    private var user: Utilisateur? = null
    private var lastSuggested: Long? = null
    private var settingPrice = false

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { RecipePhotos.decode(this@ComposeRecipeActivity, uri, 1280) }
            if (bitmap == null) snack(R.string.error_photo_open) else { draft.photo = bitmap; showPhoto(bitmap) }
        }
    }

    private val createIngredient = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = result.data?.getLongExtra(NouvelIngredientActivity.EXTRA_INGREDIENT_ID, 0L) ?: 0L
        if (result.resultCode == RESULT_OK && id > 0) lifecycleScope.launch {
            reloadIngredients()
            ingredients[id]?.let { addLine(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        b = ActivityComposeRecipeBinding.inflate(layoutInflater)
        setScreen(b.root)
        val editId = intent.getLongExtra(EXTRA_RECETTE_ID, 0L)
        setTitleText(getString(if (editId > 0) R.string.compose_title_edit else R.string.compose_title_new))

        b.photoZone.setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        b.btnAddLine.setOnClickListener { chooseIngredient() }
        b.btnSave.setOnClickListener { save() }
        b.btnUseSuggested.setOnClickListener { lastSuggested?.let { setPrice(it) } }
        listOf(b.editPortions, b.editPrep, b.editPackaging, b.editEnergy, b.editLabor, b.editMargin).forEach {
            it.doAfterTextChanged { clearErrors(); recalculate() }
        }
        b.editName.doAfterTextChanged { b.tilName.error = null }
        b.editPrice.doAfterTextChanged {
            if (!settingPrice) draft.priceTouched = !it.isNullOrBlank()
            recalculate()
        }

        lifecycleScope.launch {
            user = repo.currentUser()
            reloadIngredients()
            if (!draft.loaded) {
                draft.loaded = true
                if (editId > 0) loadRecipe(editId) else b.editMargin.setText(DEFAULT_MARGIN.toString())
            }
            rebuildLines()
            draft.photo?.let { showPhoto(it) } ?: draft.editing?.let { r ->
                withContext(Dispatchers.IO) { RecipePhotos.thumbnail(this@ComposeRecipeActivity, r.id, 600) }?.let { showPhoto(it) }
            }
            recalculate()
        }
    }

    private suspend fun reloadIngredients() {
        ingredients = db.ingredientDao().listePour(repo.userId).associateBy { it.id }
    }

    private suspend fun loadRecipe(id: Long) {
        val r = db.recetteDao().parId(id)
        if (r == null || r.utilisateurId != repo.userId) {
            snack(R.string.error_recipe_not_found); finish(); return
        }
        draft.editing = r
        b.editName.setText(r.nom)
        b.editPortions.setText(r.nombreParts.toString())
        if (r.tempsPreparationMinutes > 0) b.editPrep.setText(r.tempsPreparationMinutes.toString())
        if (r.emballageParPartMillimes > 0) b.editPackaging.setText(Fmt.inputAmount(this, r.emballageParPartMillimes))
        if (r.gazParLotMillimes > 0) b.editEnergy.setText(Fmt.inputAmount(this, r.gazParLotMillimes))
        if (r.mainOeuvreParLotMillimes > 0) b.editLabor.setText(Fmt.inputAmount(this, r.mainOeuvreParLotMillimes))
        b.editMargin.setText(Fmt.inputNumber(this, r.margeVoulue))
        r.prixVenteMillimes?.let { setPrice(it); draft.priceTouched = true }
        draft.lines.clear()
        db.ligneRecetteDao().listePour(id).forEach { draft.lines += Draft.Line(it.ingredientId, Fmt.inputNumber(this, it.quantite), it.unite) }
    }

    // ---------- lignes ----------

    private fun chooseIngredient() {
        val used = draft.lines.map { it.ingredientId }.toSet()
        val options = ingredients.values.filter { it.id !in used }.sortedBy { it.nom.lowercase() }.map {
            Picker.Option(it, it.nom, priceLabel(it))
        }
        Picker.show(
            this, getString(R.string.pick_ingredient_title), options,
            emptyText = getString(if (ingredients.isEmpty()) R.string.pick_ingredient_none else R.string.pick_ingredient_empty),
            onCreate = { name ->
                createIngredient.launch(
                    Intent(this, NouvelIngredientActivity::class.java)
                        .putExtra(NouvelIngredientActivity.EXTRA_NAME, name)
                        .putExtra(NouvelIngredientActivity.EXTRA_RETURN, true)
                )
            }
        ) { addLine(it) }
    }

    private fun priceLabel(i: Ingredient): String =
        if (i.prixMillimes == null) getString(R.string.price_missing)
        else getString(R.string.package_price, Fmt.money(this, i.prixMillimes), Fmt.baseQuantity(this, i.quantiteEmballage, i.unite))

    private fun addLine(ingredient: Ingredient) {
        if (draft.lines.any { it.ingredientId == ingredient.id }) { snack(R.string.error_line_duplicate); return }
        val unit = when (ingredient.unite) { "g" -> "g"; "ml" -> "ml"; else -> "piece" }
        draft.lines += Draft.Line(ingredient.id, "", unit)
        rebuildLines()
        b.listLines.getChildAt(b.listLines.childCount - 1)?.findViewById<View>(R.id.lineQty)?.requestFocus()
        recalculate()
    }

    private fun rebuildLines() {
        b.listLines.removeAllViews()
        for (line in draft.lines) {
            val ing = ingredients[line.ingredientId]
            val v = ItemRecipeLineBinding.inflate(LayoutInflater.from(this), b.listLines, false)
            v.lineName.text = ing?.nom ?: getString(R.string.ingredient_deleted)
            v.lineInfo.text = ing?.let { priceInfo(it) } ?: ""
            v.lineQty.isSaveEnabled = false   // les lignes sont reconstruites depuis le brouillon
            v.lineQty.setText(line.quantity)
            v.lineUnit.text = Fmt.unitLabel(this, line.unit)
            v.lineUnit.contentDescription = getString(R.string.cd_unit, Fmt.unitLabel(this, line.unit))
            v.lineQty.doAfterTextChanged { line.quantity = it?.toString().orEmpty(); v.tilQty.error = null; recalculate() }
            v.lineUnit.setOnClickListener {
                val base = ing?.unite ?: return@setOnClickListener
                val menu = PopupMenu(this, v.lineUnit)
                val units = MeasureUnit.compatibleWith(base)
                units.forEachIndexed { i, u ->
                    val label = if (u == MeasureUnit.PACK) getString(R.string.unit_pack_of, Fmt.baseQuantity(this, ing.quantiteEmballage, base)) else Fmt.unitLabel(this, u.code)
                    menu.menu.add(0, i, i, label)
                }
                menu.setOnMenuItemClickListener { item ->
                    line.unit = units[item.itemId].code
                    v.lineUnit.text = Fmt.unitLabel(this, line.unit)
                    recalculate(); true
                }
                menu.show()
            }
            v.lineRemove.contentDescription = getString(R.string.cd_remove_line, v.lineName.text)
            v.lineRemove.setOnClickListener {
                draft.lines.remove(line)
                rebuildLines(); recalculate()
            }
            v.root.tag = line
            b.listLines.addView(v.root)
        }
    }

    private fun priceInfo(i: Ingredient): String {
        val p = priceLabel(i)
        return if (i.rendementPourcent < 100.0) getString(R.string.line_info_yield, p, Fmt.number(this, i.rendementPourcent, 0)) else p
    }

    // ---------- calcul en direct ----------

    private fun millimes(text: CharSequence?): Long = text?.toString()?.let { Money.parseMillimes(it) } ?: 0L
    private fun int(text: CharSequence?): Int = text?.toString()?.let { NumberParser.parse(it) }?.toInt() ?: 0
    private fun marginInput(): BigDecimal? = b.editMargin.text?.toString()?.let { NumberParser.parse(it) }

    private fun draftRecipe(): Recette = Recette(
        id = draft.editing?.id ?: 0,
        utilisateurId = repo.userId,
        nom = b.editName.text.toString().trim(),
        nombreParts = int(b.editPortions.text),
        margeVoulue = (marginInput() ?: BigDecimal(DEFAULT_MARGIN)).toDouble(),
        prixVenteMillimes = b.editPrice.text?.toString()?.let { Money.parseMillimes(it) },
        tempsPreparationMinutes = int(b.editPrep.text),
        emballageParPartMillimes = millimes(b.editPackaging.text),
        gazParLotMillimes = millimes(b.editEnergy.text),
        mainOeuvreParLotMillimes = millimes(b.editLabor.text)
    )

    private fun draftLines(): List<LigneRecette> = draft.lines.map {
        LigneRecette(recetteId = 0, ingredientId = it.ingredientId, quantite = NumberParser.parse(it.quantity)?.toDouble() ?: 0.0, unite = it.unit)
    }

    private fun recalculate() {
        val recipe = draftRecipe()
        val cost = RecipeCosting.compute(recipe, draftLines(), ingredients, user)
        // coût par ligne
        for (i in 0 until b.listLines.childCount) {
            val view = b.listLines.getChildAt(i)
            val line = view.tag as? Draft.Line ?: continue
            val lc = cost.lines.firstOrNull { it.line.ingredientId == line.ingredientId }
            val costView = view.findViewById<android.widget.TextView>(R.id.lineCost)
            costView.text = when (lc?.issue) {
                CostCalculator.Issue.MISSING_PRICE -> getString(R.string.price_missing)
                CostCalculator.Issue.UNIT_MISMATCH, CostCalculator.Issue.INVALID_PACKAGE -> getString(R.string.unit_mismatch)
                CostCalculator.Issue.ZERO_QUANTITY, null -> if (lc?.costMillimes != null) Fmt.money(this, lc.costMillimes) else getString(R.string.value_missing)
                else -> getString(R.string.value_missing)
            }
            costView.setTextColor(getColor(if (lc?.issue == null || lc.issue == CostCalculator.Issue.ZERO_QUANTITY) R.color.hb_ink else R.color.hb_bad))
        }
        b.txtBatch.text = Fmt.money(this, cost.batchMillimes)
        b.txtPerPortion.text = Fmt.money(this, cost.costPerPortionMillimes)
        lastSuggested = cost.suggestedPriceMillimes

        val margin = marginInput()
        b.tilMargin.error = if (margin != null && (margin.signum() < 0 || margin >= BigDecimal(100))) getString(R.string.error_margin_range) else null

        val suggested = cost.suggestedPriceMillimes
        b.txtPriceHelp.text = if (suggested != null) getString(R.string.compose_suggested, Fmt.money(this, suggested), Fmt.percent(this, margin ?: BigDecimal(DEFAULT_MARGIN)))
        else getString(R.string.compose_suggested_none)
        b.btnUseSuggested.visibility = if (suggested != null && draft.priceTouched && suggested != recipe.prixVenteMillimes) View.VISIBLE else View.GONE
        b.btnUseSuggested.text = getString(R.string.action_use_price, Fmt.money(this, suggested))
        if (!draft.priceTouched && suggested != null) setPrice(suggested)

        val real = cost.realMarginPercent
        b.txtSummaryLine.text = when {
            cost.costPerPortionMillimes == null -> getString(R.string.compose_need_portions)
            !cost.isComplete && cost.issues.contains(CostCalculator.Issue.MISSING_PRICE) -> getString(R.string.compose_incomplete)
            real != null -> getString(R.string.compose_summary, Fmt.money(this, recipe.prixVenteMillimes), Fmt.percent(this, real))
            else -> ""
        }

        val labor = RecipeCosting.laborEstimate(user, recipe.tempsPreparationMinutes)
        b.btnLaborEstimate.visibility = if (labor != null && labor != recipe.mainOeuvreParLotMillimes) View.VISIBLE else View.GONE
        b.btnLaborEstimate.text = getString(R.string.action_labor_estimate, Fmt.money(this, labor))
        b.btnLaborEstimate.setOnClickListener { labor?.let { b.editLabor.setText(Fmt.inputAmount(this, it)) } }
        val fixed = RecipeCosting.fixedPerPortion(user)
        b.txtFixedNote.visibility = if (fixed > 0) View.VISIBLE else View.GONE
        if (fixed > 0) b.txtFixedNote.text = getString(R.string.compose_fixed_note, Fmt.money(this, fixed))
    }

    private fun setPrice(millimes: Long) {
        settingPrice = true
        b.editPrice.setText(Fmt.inputAmount(this, millimes))
        settingPrice = false
    }

    private fun clearErrors() {
        b.tilPortions.error = null
        b.txtLinesError.visibility = View.GONE
    }

    // ---------- enregistrement ----------

    private fun save() {
        val recipe = draftRecipe()
        var ok = true
        if (recipe.nom.isEmpty()) { b.tilName.error = getString(R.string.error_recipe_name); ok = false }
        if (recipe.nombreParts <= 0) { b.tilPortions.error = getString(R.string.error_portions); ok = false }
        val lines = draftLines()
        val valid = lines.filter { it.quantite > 0 }
        if (valid.isEmpty()) {
            b.txtLinesError.setText(if (lines.isEmpty()) R.string.error_no_line else R.string.error_line_quantity)
            b.txtLinesError.visibility = View.VISIBLE
            ok = false
        }
        val margin = marginInput()
        if (margin == null || margin.signum() < 0 || margin >= BigDecimal(100)) { b.tilMargin.error = getString(R.string.error_margin_range); ok = false }
        if (!ok) { snack(R.string.error_check_fields); return }

        // lignes à quantité vide : on prévient au lieu de les perdre en silence
        if (valid.size < lines.size) {
            for (i in 0 until b.listLines.childCount) {
                val view = b.listLines.getChildAt(i)
                val line = view.tag as? Draft.Line ?: continue
                if ((NumberParser.parse(line.quantity)?.signum() ?: 0) <= 0)
                    view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilQty).error = getString(R.string.error_quantity)
            }
            snack(R.string.error_line_quantity)
            return
        }

        val cost = RecipeCosting.compute(recipe, lines, ingredients, user)
        val price = recipe.prixVenteMillimes ?: cost.suggestedPriceMillimes
        b.btnSave.isEnabled = false
        lifecycleScope.launch {
            val id = repo.saveRecipe(draft.editing, recipe.copy(prixVenteMillimes = price), lines)
            val photoOk = draft.photo?.let { p -> withContext(Dispatchers.IO) { RecipePhotos.save(this@ComposeRecipeActivity, p, id) } } ?: true
            if (!photoOk) snack(R.string.error_photo_save)
            if (draft.editing == null) {
                startActivity(Intent(this@ComposeRecipeActivity, RecipeDetailActivity::class.java).putExtra(RecipeDetailActivity.EXTRA_ID, id).putExtra(RecipeDetailActivity.EXTRA_CREATED, true))
            }
            finish()
        }
    }

    private fun showPhoto(bitmap: Bitmap) {
        b.imgPhoto.setImageBitmap(bitmap)
        b.imgPhoto.visibility = View.VISIBLE
        b.photoZone.clipToOutline = true
        b.txtPhotoHint.visibility = View.GONE
        b.txtPhotoChange.visibility = View.VISIBLE
    }
}

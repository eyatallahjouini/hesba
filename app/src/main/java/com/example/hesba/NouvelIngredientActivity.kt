package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hesba.data.HesbaRepository.IngredientSave
import com.example.hesba.data.Ingredient
import com.example.hesba.data.PrixHistorique
import com.example.hesba.databinding.ActivityNouvelIngredientBinding
import com.example.hesba.databinding.ItemLedgerBinding
import com.example.hesba.domain.MeasureUnit
import com.example.hesba.domain.Money
import com.example.hesba.domain.NumberParser
import com.example.hesba.domain.UnitConverter
import com.example.hesba.ui.BaseActivity
import com.example.hesba.ui.Dialogs
import com.example.hesba.ui.Fmt
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import java.math.BigDecimal

/**
 * Ajouter / modifier un ingrédient (écran d'Aya, repris) :
 * prix de l'emballage, rendement utilisable, autres noms, historique des prix, recettes qui l'utilisent.
 */
class NouvelIngredientActivity : BaseActivity() {

    companion object {
        const val EXTRA_INGREDIENT_ID = "EXTRA_INGREDIENT_ID"
        const val EXTRA_NAME = "name"
        const val EXTRA_RETURN = "return"   // renvoie l'id créé à l'écran appelant
        // liste déroulante des unités : même ordre que les puces du layout
        private val UNIT_CHIPS = listOf(R.id.unitKg, R.id.unitG, R.id.unitL, R.id.unitMl, R.id.unitPiece)
        private val UNIT_LABELS = listOf(R.string.unit_kg, R.string.unit_g, R.string.unit_l, R.string.unit_ml, R.string.unit_piece)
    }

    private lateinit var b: ActivityNouvelIngredientBinding
    private var existing: Ingredient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        b = ActivityNouvelIngredientBinding.inflate(layoutInflater)
        setScreen(b.root)
        val id = intent.getLongExtra(EXTRA_INGREDIENT_ID, 0L)
        setTitleText(getString(if (id > 0) R.string.ingredient_title_edit else R.string.ingredient_title_new))
        if (savedInstanceState == null) {
            intent.getStringExtra(EXTRA_NAME)?.let { b.editName.setText(it) }
            b.editYield.setText("100")
        }

        listOf(b.editQuantity, b.editPrice, b.editYield).forEach { it.doAfterTextChanged { clearErrors(); live() } }
        b.editName.doAfterTextChanged { b.tilName.error = null }
        b.groupUnit.setOnCheckedStateChangeListener { _, _ -> syncUnitDropdown(); updateQuantityHint(); live() }
        // design Minimal : l'unité se choisit dans une liste déroulante, qui coche la puce correspondante
        // (le groupe de puces, caché, reste la valeur lue par le reste du code)
        b.dropUnit.setSimpleItems(unitLabels().toTypedArray())
        b.dropUnit.setOnItemClickListener { _, _, position, _ -> b.groupUnit.check(UNIT_CHIPS[position]) }
        syncUnitDropdown()
        b.btnSave.setOnClickListener { save() }
        b.btnDelete.setOnClickListener { askDelete() }
        b.btnAddSynonym.setOnClickListener { addSynonym() }
        updateQuantityHint()
        live()

        if (id > 0) lifecycleScope.launch {
            val ing = db.ingredientDao().parId(id)
            if (ing == null || ing.utilisateurId != repo.userId) { snack(R.string.error_ingredient_not_found); finish(); return@launch }
            existing = ing
            if (savedInstanceState == null) fill(ing)
            b.btnDelete.visibility = View.VISIBLE
            b.blockSynonyms.visibility = View.VISIBLE
            follow(ing.id)
        }
    }

    private fun fill(i: Ingredient) {
        b.editName.setText(i.nom)
        val q = BigDecimal(i.quantiteEmballage.toString())
        val (chip, shown) = when {
            i.unite == "g" && q >= BigDecimal(1000) -> R.id.unitKg to UnitConverter.fromBase(q, MeasureUnit.KG, null)!!
            i.unite == "g" -> R.id.unitG to q
            i.unite == "ml" && q >= BigDecimal(1000) -> R.id.unitL to UnitConverter.fromBase(q, MeasureUnit.L, null)!!
            i.unite == "ml" -> R.id.unitMl to q
            else -> R.id.unitPiece to q
        }
        b.groupUnit.check(chip)
        b.editQuantity.setText(Fmt.inputNumber(this, shown.toDouble()))
        b.editPrice.setText(Fmt.inputAmount(this, i.prixMillimes))
        b.editYield.setText(Fmt.inputNumber(this, i.rendementPourcent))
        live()
    }

    /** Synonymes, historique et utilisation, mis à jour en direct. */
    private fun follow(id: Long) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repo.synonyms().collect { list ->
                        b.groupSynonyms.removeAllViews()
                        list.filter { it.ingredientId == id }.forEach { s ->
                            b.groupSynonyms.addView(Chip(this@NouvelIngredientActivity).apply {
                                text = s.texte
                                isCloseIconVisible = true
                                closeIconContentDescription = getString(R.string.cd_remove_synonym, s.texte)
                                setOnCloseIconClickListener { lifecycleScope.launch { repo.deleteSynonym(s) } }
                            })
                        }
                    }
                }
                launch {
                    db.prixHistoriqueDao().pourIngredient(id).collect { showHistory(it) }
                }
                launch {
                    repo.workspace().collect { ws ->
                        val used = ws.recipesUsing(id)
                        b.blockUsage.visibility = if (used.isEmpty()) View.GONE else View.VISIBLE
                        b.txtUsageTitle.text = resources.getQuantityString(R.plurals.used_in, used.size, used.size)
                        b.txtUsage.text = used.joinToString(" · ") { it.nom }
                    }
                }
            }
        }
    }

    private fun showHistory(entries: List<PrixHistorique>) {
        b.blockHistory.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        b.listHistory.removeAllViews()
        entries.take(12).forEach { h ->
            val v = ItemLedgerBinding.inflate(LayoutInflater.from(this), b.listHistory, false)
            v.ledgerLabel.text = Fmt.date(this, h.date)
            val source = when (h.source) {
                PrixHistorique.SOURCE_FACTURE -> getString(R.string.source_invoice)
                PrixHistorique.SOURCE_SIMULATION -> getString(R.string.source_simulation)
                else -> getString(R.string.source_manual)
            }
            v.ledgerDetail.text = getString(R.string.history_detail, source, Fmt.money(this, h.ancienPrixMillimes), Fmt.money(this, h.nouveauPrixMillimes))
            v.ledgerDetail.visibility = View.VISIBLE
            val pct = com.example.hesba.domain.HomeSummary.percentChange(h)
            v.ledgerValue.text = pct?.let { Fmt.signedPercent(this, it) } ?: ""
            v.ledgerValue.setTextColor(getColor(if ((pct?.signum() ?: 0) > 0) R.color.hb_bad else R.color.hb_good))
            b.listHistory.addView(v.root)
        }
    }

    // ---------- saisie ----------

    private fun unitLabels() = UNIT_LABELS.map { getString(it) }

    /** Affiche dans la liste déroulante l'unité cochée dans le groupe de puces. */
    private fun syncUnitDropdown() {
        val index = UNIT_CHIPS.indexOf(b.groupUnit.checkedChipId).coerceAtLeast(0)
        b.dropUnit.setText(unitLabels()[index], false)
    }

    private fun unit(): MeasureUnit = when (b.groupUnit.checkedChipId) {
        R.id.unitG -> MeasureUnit.G
        R.id.unitL -> MeasureUnit.L
        R.id.unitMl -> MeasureUnit.ML
        R.id.unitPiece -> MeasureUnit.PIECE
        else -> MeasureUnit.KG
    }

    private fun updateQuantityHint() {
        b.tilQuantity.suffixText = Fmt.unitLabel(this, unit().code)
    }

    private fun quantityBase(): BigDecimal? {
        val q = NumberParser.parseOrNull(b.editQuantity.text?.toString()) ?: return null
        if (q.signum() <= 0) return null
        return UnitConverter.toBase(q, unit(), unit().base!!, null)
    }

    private fun yieldPct(): BigDecimal? = NumberParser.parseOrNull(b.editYield.text?.toString())

    private fun live() {
        val qty = quantityBase()
        val price = b.editPrice.text?.toString()?.let { Money.parseMillimes(it) }
        val y = yieldPct() ?: BigDecimal(100)
        val base = unit().base!!
        val unitPrice = if (qty != null && y.signum() > 0 && y <= BigDecimal(100)) CostCalculator.unitPrice(price, qty, y) else null
        if (unitPrice == null) {
            b.txtLive.setText(R.string.ingredient_live_empty)
            return
        }
        val (factor, label) = when (base) { "g" -> 1000 to "kg"; "ml" -> 1000 to "l"; else -> 1 to "piece" }
        val per = Money.roundMillimes(unitPrice.multiply(BigDecimal(factor)))
        b.txtLive.text = if (y < BigDecimal(100)) {
            val usable = qty!!.multiply(y).divide(BigDecimal(100))
            getString(
                R.string.ingredient_live_yield,
                Fmt.baseQuantity(this, qty, base), Fmt.baseQuantity(this, usable, base), Fmt.money(this, per), Fmt.unitLabel(this, label)
            )
        } else getString(R.string.ingredient_live, Fmt.money(this, per), Fmt.unitLabel(this, label))
    }

    private fun clearErrors() {
        b.tilQuantity.error = null; b.tilPrice.error = null; b.tilYield.error = null
    }

    private fun save() {
        val name = b.editName.text.toString().trim()
        val qty = quantityBase()
        val price = b.editPrice.text?.toString()?.let { Money.parseMillimes(it) }
        val y = yieldPct()
        var ok = true
        if (name.isEmpty()) { b.tilName.error = getString(R.string.error_ingredient_name); ok = false }
        if (qty == null) { b.tilQuantity.error = getString(R.string.error_quantity); ok = false }
        if (price == null || price <= 0) { b.tilPrice.error = getString(R.string.error_price); ok = false }
        if (y == null || y.signum() <= 0 || y > BigDecimal(100)) { b.tilYield.error = getString(R.string.error_yield); ok = false }
        if (!ok) return

        val draft = Ingredient(
            nom = name, unite = unit().base!!, quantiteEmballage = qty!!.toDouble(),
            prixMillimes = price, rendementPourcent = y!!.toDouble()
        )
        b.btnSave.isEnabled = false
        lifecycleScope.launch {
            when (val r = repo.saveIngredient(existing, draft)) {
                is IngredientSave.Duplicate -> {
                    b.btnSave.isEnabled = true
                    b.tilName.error = getString(R.string.error_ingredient_duplicate, r.existing.nom)
                }
                is IngredientSave.UnitLocked -> {
                    b.btnSave.isEnabled = true
                    Dialogs.info(this@NouvelIngredientActivity, getString(R.string.dialog_unit_locked_title), getString(R.string.dialog_unit_locked_body, r.recipes.joinToString { it.nom }))
                }
                is IngredientSave.Saved -> {
                    if (intent.getBooleanExtra(EXTRA_RETURN, false)) {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_INGREDIENT_ID, r.id))
                        finish()
                    } else if (existing == null) {
                        snack(getString(R.string.ingredient_added, name))
                        // on reste sur la page, prête pour l'ingrédient suivant (comportement d'Aya)
                        b.editName.text?.clear(); b.editQuantity.text?.clear(); b.editPrice.text?.clear(); b.editYield.setText("100")
                        b.btnSave.isEnabled = true
                        b.editName.requestFocus()
                    } else {
                        if (r.recalculated > 0) Toasts.long(this@NouvelIngredientActivity, resources.getQuantityString(R.plurals.recalculated, r.recalculated, r.recalculated))
                        finish()
                    }
                }
            }
        }
    }

    private fun addSynonym() {
        val ing = existing ?: return
        Dialogs.input(
            this, getString(R.string.dialog_synonym_title, ing.nom), getString(R.string.field_synonym),
            helper = getString(R.string.helper_synonym),
            validate = { if (it.isBlank()) getString(R.string.error_required) else null }
        ) { text -> lifecycleScope.launch { repo.learnSynonym(text, ing.id) } }
    }

    private fun askDelete() {
        val ing = existing ?: return
        lifecycleScope.launch {
            val used = repo.recipesUsing(ing.id)
            if (used.isNotEmpty()) {
                Dialogs.info(this@NouvelIngredientActivity, getString(R.string.dialog_delete_blocked_title), getString(R.string.dialog_delete_blocked_body, ing.nom, used.joinToString { it.nom }))
                return@launch
            }
            Dialogs.confirm(this@NouvelIngredientActivity, getString(R.string.dialog_delete_ingredient_title, ing.nom), getString(R.string.dialog_delete_ingredient_body), R.string.action_delete, destructive = true) {
                lifecycleScope.launch {
                    val still = runCatching { repo.deleteIngredient(ing) }.getOrElse { listOf() }
                    if (still.isNotEmpty()) snack(R.string.error_delete_used) else finish()
                }
            }
        }
    }
}

/** Message qui survit à la fermeture de l'écran (le Snackbar disparaîtrait avec lui). */
object Toasts {
    fun long(context: android.content.Context, message: CharSequence) =
        android.widget.Toast.makeText(context.applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
}

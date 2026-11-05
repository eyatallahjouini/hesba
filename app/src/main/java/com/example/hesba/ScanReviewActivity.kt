package com.example.hesba

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.hesba.auth.SessionManager
import com.example.hesba.data.Facture
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.HesbaRepository
import com.example.hesba.data.PrixHistorique
import com.example.hesba.databinding.ActivityScanReviewBinding
import com.example.hesba.databinding.DialogScanLineBinding
import com.example.hesba.databinding.ItemScanLineBinding
import com.example.hesba.domain.MeasureUnit
import com.example.hesba.domain.Money
import com.example.hesba.domain.NumberParser
import com.example.hesba.domain.RecipeImpact
import com.example.hesba.domain.TextNormalizer
import com.example.hesba.domain.Workspace
import com.example.hesba.domain.invoice.IngredientMatcher
import com.example.hesba.domain.invoice.InvoiceReview
import com.example.hesba.domain.invoice.InvoiceReview.Action
import com.example.hesba.domain.invoice.LineField
import com.example.hesba.domain.invoice.MatchCandidate
import com.example.hesba.domain.invoice.MatchConfidence
import com.example.hesba.domain.invoice.ParsedInvoice
import com.example.hesba.ocr.InvoiceImages
import com.example.hesba.ocr.OcrLanguage
import com.example.hesba.ocr.OcrRouter
import com.example.hesba.ui.BaseActivity
import com.example.hesba.ui.Dialogs
import com.example.hesba.ui.Fmt
import com.example.hesba.ui.Motion
import com.example.hesba.ui.ImpactViews
import com.example.hesba.ui.Picker
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Facture lue → vérification ligne par ligne → confirmation → mise à jour en UNE transaction.
 * Aucun prix n'est modifié sans « Mettre à jour » explicite ; les champs incertains sont marqués.
 */
class ScanReviewActivity : BaseActivity() {

    companion object {
        const val EXTRA_FILES = "files"
        const val EXTRA_LANGUAGE = "language"
    }

    sealed class State {
        object Reading : State()
        data class Failed(val message: Int) : State()
        object Ready : State()
        object Saving : State()
        data class Done(val updated: Int, val created: Int, val impacts: List<RecipeImpact>) : State()
    }

    class Model(app: Application) : AndroidViewModel(app) {
        val state = MutableStateFlow<State>(State.Reading)
        var files: List<File> = emptyList()
        var started = false
        var invoice: ParsedInvoice? = null
        var language = "fr"
        var rawText = ""
        var ws: Workspace? = null
        var lines: List<InvoiceReview.Line> = emptyList()
        var options = InvoiceReview.Options(null, false, null, false)
        var supplier = ""
        var date = ""
        var thumbnail: Bitmap? = null
        /** Image de la 1re page, montrée pendant la lecture. */
        val preview = MutableStateFlow<Bitmap?>(null)
        /** Les lignes trouvées ont-elles déjà été présentées une à une ? */
        var linesRevealed = false

        private val db get() = HesbaDatabase.getInstance(getApplication())
        private val repo get() = HesbaRepository(db, SessionManager(getApplication()).getUserId())

        fun start(paths: List<String>, lang: OcrLanguage) {
            if (started) return
            started = true
            files = paths.map(::File)
            viewModelScope.launch {
                val router = OcrRouter(getApplication())
                try {
                    val bitmaps = withContext(Dispatchers.IO) { files.mapNotNull { InvoiceImages.load(it) } }
                    if (bitmaps.isEmpty()) { state.value = State.Failed(R.string.review_error_image); return@launch }
                    thumbnail = Bitmap.createScaledBitmap(bitmaps[0], 240, (240f * bitmaps[0].height / bitmaps[0].width).toInt().coerceAtLeast(1), true)
                    val pw = minOf(900, bitmaps[0].width)
                    preview.value = Bitmap.createScaledBitmap(bitmaps[0], pw, (pw.toFloat() * bitmaps[0].height / bitmaps[0].width).toInt().coerceAtLeast(1), true)
                    val outcome = withContext(Dispatchers.Default) { router.read(bitmaps, lang) }
                    rawText = outcome.result.rows.joinToString("\n") { it.text }
                    if (rawText.count { it.isLetterOrDigit() } < 8) { state.value = State.Failed(R.string.review_error_no_text); return@launch }
                    val inv = outcome.invoice
                    invoice = inv
                    language = outcome.language
                    val w = repo.snapshot()
                    ws = w
                    val matcher = IngredientMatcher(w.ingredients.map { MatchCandidate(it.id, it.nom) }, repo.synonymList().map { it.texteNormalise to it.ingredientId })
                    options = InvoiceReview.Options(inv.taxPercent, false, inv.discountPercent, false)
                    lines = InvoiceReview.initial(inv, matcher, w.ingredientsById, options)
                    supplier = inv.supplier.value.orEmpty()
                    date = inv.date.value.orEmpty()
                    state.value = State.Ready
                } catch (e: Exception) {
                    state.value = State.Failed(R.string.review_error_ocr)
                } finally {
                    router.close()
                }
            }
        }

        fun evaluation(l: InvoiceReview.Line) = InvoiceReview.evaluate(l, ws?.ingredientsById?.get(l.ingredientId), options)

        fun update(index: Int, transform: (InvoiceReview.Line) -> InvoiceReview.Line) {
            lines = lines.map { if (it.index == index) transform(it) else it }
        }

        /** Lignes prêtes à être appliquées (acceptées, sans problème, sans doublon). */
        fun ready(): List<InvoiceReview.Line> {
            val dup = InvoiceReview.duplicates(lines)
            return lines.filter { it.action == Action.ACCEPT && it.index !in dup && evaluation(it).problem == null }
        }

        fun confirm() {
            val toApply = ready()
            state.value = State.Saving
            viewModelScope.launch {
                val r = repo
                val result = db.withTransaction {
                    val kept = files.firstOrNull()
                    val factureId = r.saveInvoice(
                        Facture(
                            utilisateurId = r.userId, fournisseur = supplier.trim(), dateFacture = date.trim(),
                            imagePath = kept?.absolutePath, langue = language, nombreLignes = lines.size, totalMillimes = invoice?.totalMillimes
                        )
                    )
                    var created = 0
                    val updates = mutableListOf<HesbaRepository.PriceUpdate>()
                    for (l in toApply) {
                        val ev = evaluation(l)
                        val price = ev.newPriceMillimes ?: continue
                        if (l.createNew) {
                            val virtual = InvoiceReview.newIngredientFor(l) ?: continue
                            val id = r.createIngredient(virtual.copy(prixMillimes = price))
                            created++
                            if (TextNormalizer.normalize(l.source.name) != TextNormalizer.normalize(virtual.nom)) r.learnSynonym(l.source.name, id)
                        } else {
                            val id = l.ingredientId ?: continue
                            updates += HesbaRepository.PriceUpdate(id, price)
                            // on retient l'association confirmée : la prochaine facture sera reconnue directement
                            if (l.match != MatchConfidence.EXACT) r.learnSynonym(l.source.name, id)
                        }
                    }
                    val applied = r.applyPrices(updates, PrixHistorique.SOURCE_FACTURE, factureId)
                    State.Done(applied.updatedIngredients, created, applied.impacts)
                }
                // pages supplémentaires d'un PDF : seule la première est gardée comme justificatif
                withContext(Dispatchers.IO) { files.drop(1).forEach { it.delete() } }
                state.value = result
            }
        }

        fun discard() = files.forEach { it.delete() }
    }

    private val model: Model by viewModels()
    private lateinit var b: ActivityScanReviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        b = ActivityScanReviewBinding.inflate(layoutInflater)
        setScreen(b.root)
        setTitleText(getString(R.string.review_title))
        val files = intent.getStringArrayExtra(EXTRA_FILES)?.toList().orEmpty()
        val lang = runCatching { OcrLanguage.valueOf(intent.getStringExtra(EXTRA_LANGUAGE) ?: "") }.getOrDefault(OcrLanguage.AUTO)
        if (lang == OcrLanguage.ARABIC) b.txtLoading.setText(R.string.review_reading_ar)
        model.start(files, lang)

        onBackPressedDispatcher.addCallback(this) {
            when (model.state.value) {
                is State.Ready -> Dialogs.confirm(this@ScanReviewActivity, getString(R.string.review_discard_title), getString(R.string.review_discard_body), R.string.action_discard, destructive = true) {
                    model.discard(); finish()
                }
                is State.Saving -> Unit
                is State.Done -> goHome()
                else -> { model.discard(); finish() }
            }
        }

        b.editSupplier.doAfterTextChanged { model.supplier = it?.toString().orEmpty() }
        b.editDate.doAfterTextChanged { model.date = it?.toString().orEmpty() }
        b.checkTax.setOnCheckedChangeListener { _, c -> model.options = model.options.copy(includeTax = c); renderLines() }
        b.checkDiscount.setOnCheckedChangeListener { _, c -> model.options = model.options.copy(applyDiscount = c); renderLines() }
        b.btnAcceptAll.setOnClickListener {
            model.lines.forEach { l -> if (model.evaluation(l).problem == null && (l.ingredientId != null || l.createNew)) model.update(l.index) { it.copy(action = Action.ACCEPT) } }
            renderLines()
        }
        b.btnRawText.setOnClickListener { Dialogs.info(this, getString(R.string.review_raw_text), model.rawText) }
        b.imgThumb.setOnClickListener { showImage() }
        b.btnConfirm.setOnClickListener { onConfirm() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.preview.collect { bmp ->
                    if (bmp != null) b.imgScanning.setImageBitmap(bmp)
                    renderScanStage(model.state.value)
                }
            }
        }
    }

    /** Pendant la lecture : la facture et la ligne qui la parcourt ; elle s'arrête dès que l'OCR a fini. */
    private fun renderScanStage(s: State) {
        val reading = s is State.Reading && model.preview.value != null
        b.scanStage.visibility = if (reading) View.VISIBLE else View.GONE
        if (reading) b.scanLine.start() else b.scanLine.stop()
        // l'indicateur classique reste pour l'enregistrement, et quand les animations sont coupées
        b.progress.visibility = if (s is State.Saving || !reading || !b.scanLine.isRunning) View.VISIBLE else View.GONE
    }

    private fun render(s: State) {
        renderScanStage(s)
        b.blockLoading.visibility = if (s is State.Reading || s is State.Saving) View.VISIBLE else View.GONE
        b.error.root.visibility = if (s is State.Failed) View.VISIBLE else View.GONE
        b.scrollReview.visibility = if (s is State.Ready) View.VISIBLE else View.GONE
        b.scrollResult.visibility = if (s is State.Done) View.VISIBLE else View.GONE
        b.footer.visibility = if (s is State.Ready || s is State.Done) View.VISIBLE else View.GONE
        when (s) {
            is State.Saving -> b.txtLoading.setText(R.string.review_saving)
            is State.Failed -> {
                b.error.emptyIcon.setImageResource(R.drawable.ic_alert)
                b.error.emptyTitle.setText(R.string.review_error_title)
                b.error.emptyBody.setText(s.message)
                b.error.emptyAction.setText(R.string.review_retry)
                b.error.emptyAction.setOnClickListener { model.discard(); startActivity(Intent(this, ScanActivity::class.java)); finish() }
            }
            is State.Ready -> renderReview()
            is State.Done -> renderDone(s)
            else -> Unit
        }
    }

    private fun renderReview() {
        val inv = model.invoice ?: return
        model.thumbnail?.let { b.imgThumb.setImageBitmap(it) }
        b.txtSummary.text = getString(
            R.string.review_summary, resources.getQuantityString(R.plurals.lines_count, inv.lines.size, inv.lines.size),
            getString(if (model.language == "ar") R.string.scan_lang_ar else R.string.scan_lang_fr)
        )
        if (b.editSupplier.text.isNullOrEmpty()) b.editSupplier.setText(model.supplier)
        if (b.editDate.text.isNullOrEmpty()) b.editDate.setText(model.date)
        b.tilSupplier.helperText = if (inv.supplier.uncertain && model.supplier.isNotEmpty()) getString(R.string.review_to_check) else null
        b.tilDate.helperText = if (model.date.isEmpty()) getString(R.string.review_date_missing) else null
        inv.taxPercent?.let {
            b.checkTax.visibility = View.VISIBLE
            b.checkTax.text = getString(R.string.review_add_tax, Fmt.percent(this, it))
        }
        inv.discountPercent?.let {
            b.checkDiscount.visibility = View.VISIBLE
            b.checkDiscount.text = getString(R.string.review_apply_discount, Fmt.percent(this, it))
        }
        renderLines()
    }

    private fun renderLines() {
        b.listLines.removeAllViews()
        val dup = InvoiceReview.duplicates(model.lines)
        b.txtLinesTitle.text = getString(R.string.review_lines, model.lines.size)
        if (model.lines.isEmpty()) {
            b.listLines.addView(layoutInflater.inflate(R.layout.view_section, b.listLines, false).apply {
                (this as android.widget.TextView).setText(R.string.review_no_lines)
                setTextAppearance(R.style.TextAppearance_Hesba_Body_Soft)
            })
        }
        for (l in model.lines) b.listLines.addView(lineView(l, l.index in dup))
        if (!model.linesRevealed && model.lines.isNotEmpty()) {
            model.linesRevealed = true
            for (i in 0 until b.listLines.childCount) Motion.riseIn(b.listLines.getChildAt(i), 120L + i * 90L, 14f)
        }
        val ready = model.ready()
        b.btnConfirm.isEnabled = ready.isNotEmpty()
        b.btnConfirm.text = if (ready.isEmpty()) getString(R.string.review_confirm_none) else resources.getQuantityString(R.plurals.review_confirm, ready.size, ready.size)
        val ignored = model.lines.size - ready.size
        b.txtFooter.text = if (ignored > 0) resources.getQuantityString(R.plurals.review_ignored, ignored, ignored) else getString(R.string.review_footer_all)
        b.btnAcceptAll.isEnabled = model.lines.any { it.action == Action.IGNORE && model.evaluation(it).problem == null && (it.ingredientId != null || it.createNew) }
    }

    private fun lineView(l: InvoiceReview.Line, duplicate: Boolean): View {
        val v = ItemScanLineBinding.inflate(LayoutInflater.from(this), b.listLines, false)
        val ing = model.ws?.ingredientsById?.get(l.ingredientId)
        val ev = model.evaluation(l)
        v.txtRead.text = getString(R.string.review_read, l.source.raw)

        v.btnMatch.text = when {
            l.createNew -> getString(R.string.review_new_ingredient, l.name)
            ing != null -> ing.nom
            else -> getString(R.string.review_choose_ingredient)
        }
        v.btnMatch.contentDescription = getString(R.string.cd_match, v.btnMatch.text)
        v.btnMatch.setOnClickListener { chooseMatch(l) }
        when {
            l.createNew -> tag(v.tagMatch, R.string.review_tag_new, R.drawable.bg_tag, R.color.hb_ink)
            l.match == MatchConfidence.EXACT || l.edited && ing != null -> tag(v.tagMatch, R.string.review_tag_sure, R.drawable.bg_tag_good, R.color.hb_good)
            l.match == MatchConfidence.HIGH -> tag(v.tagMatch, R.string.review_tag_likely, R.drawable.bg_tag_good, R.color.hb_good)
            ing != null -> tag(v.tagMatch, R.string.review_tag_check, R.drawable.bg_tag_bad, R.color.hb_bad)
            else -> tag(v.tagMatch, R.string.review_tag_none, R.drawable.bg_tag_bad, R.color.hb_bad)
        }

        // quantité et prix lus, champs incertains encadrés en pointillés
        val qty = l.quantity?.let { Fmt.number(this, it) } ?: getString(R.string.value_missing)
        val unit = describeUnit(l)
        v.txtQuantity.text = getString(R.string.review_quantity, qty, unit, Fmt.money(this, l.unitPriceMillimes))
        if (l.uncertain.any { it == LineField.QUANTITY || it == LineField.UNIT || it == LineField.UNIT_PRICE }) {
            v.txtQuantity.setBackgroundResource(R.drawable.bg_uncertain)
            v.txtQuantity.contentDescription = v.txtQuantity.text.toString() + ". " + getString(R.string.review_to_check)
        }
        val target = ing ?: if (l.createNew) InvoiceReview.newIngredientFor(l) else null
        v.txtPriceLabel.text = if (target != null) getString(R.string.review_price_for, Fmt.baseQuantity(this, target.quantiteEmballage, target.unite)) else ""
        v.txtPrices.text = when {
            ev.newPriceMillimes == null -> ""
            ev.oldPriceMillimes == null -> Fmt.money(this, ev.newPriceMillimes)
            else -> getString(R.string.before_after, Fmt.money(this, ev.oldPriceMillimes), Fmt.money(this, ev.newPriceMillimes))
        }
        if (ev.diff != null) {
            v.txtDelta.text = getString(R.string.review_delta, Fmt.signedMoney(this, ev.diff!!), ev.percent?.let { Fmt.signedPercent(this, it) } ?: "")
            v.txtDelta.setTextColor(getColor(if (ev.diff!! > 0) R.color.hb_bad else if (ev.diff!! < 0) R.color.hb_good else R.color.hb_ink_soft))
        }
        val problem = when {
            duplicate -> getString(R.string.review_problem_duplicate)
            ev.problem == InvoiceReview.Problem.NO_INGREDIENT -> getString(R.string.review_problem_no_ingredient)
            ev.problem == InvoiceReview.Problem.NO_PRICE -> getString(R.string.review_problem_no_price)
            ev.problem == InvoiceReview.Problem.UNIT_MISMATCH -> getString(R.string.review_problem_unit, target?.let { Fmt.unitLabel(this, it.unite) } ?: "")
            else -> null
        }
        v.txtProblem.visibility = if (problem != null) View.VISIBLE else View.GONE
        v.txtProblem.text = problem

        v.toggleAction.check(if (l.action == Action.ACCEPT) R.id.btnAccept else R.id.btnIgnore)
        v.btnAccept.isEnabled = ev.problem == null
        v.toggleAction.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            model.update(l.index) { it.copy(action = if (id == R.id.btnAccept) Action.ACCEPT else Action.IGNORE) }
            b.listLines.post { renderLines() }
        }
        v.btnEdit.setOnClickListener { editLine(l) }
        v.lineRoot.alpha = if (l.action == Action.IGNORE) 0.75f else 1f
        return v.root
    }

    private fun describeUnit(l: InvoiceReview.Line): String {
        val base = l.unit?.let { u -> if (u == MeasureUnit.PACK) getString(R.string.unit_pack) else Fmt.unitLabel(this, u.code) } ?: getString(R.string.review_unit_unknown)
        return if (l.packageSize != null && l.packageUnit != null) getString(R.string.review_pack_of, base, Fmt.quantity(this, l.packageSize, l.packageUnit.code)) else base
    }

    private fun tag(v: android.widget.TextView, text: Int, bg: Int, color: Int) {
        v.setText(text); v.setBackgroundResource(bg); v.setTextColor(getColor(color))
    }

    private fun chooseMatch(l: InvoiceReview.Line) {
        val w = model.ws ?: return
        val options = w.ingredients.map { Picker.Option(it.id, it.nom, Fmt.money(this, it.prixMillimes) + " · " + Fmt.baseQuantity(this, it.quantiteEmballage, it.unite)) }
        Picker.show(
            this, getString(R.string.review_choose_for, l.name), options, getString(R.string.pick_ingredient_empty),
            onCreate = { name ->
                val newName = name.ifBlank { l.name }
                model.update(l.index) { it.copy(createNew = true, ingredientId = null, name = newName, edited = true, action = Action.IGNORE) }
                renderLines()
            },
            createLabel = { getString(R.string.action_create_named, it) }
        ) { id ->
            model.update(l.index) { it.copy(ingredientId = id, createNew = false, match = MatchConfidence.LOW, edited = true) }
            renderLines()
        }
    }

    private fun editLine(l: InvoiceReview.Line) {
        val d = DialogScanLineBinding.inflate(LayoutInflater.from(this))
        d.editName.setText(l.name)
        d.editQty.setText(l.quantity?.let { Fmt.inputNumber(this, it.toDouble()) } ?: "")
        d.editUnitPrice.setText(Fmt.inputAmount(this, l.unitPriceMillimes))
        d.editPackage.setText(l.packageSize?.let { Fmt.inputNumber(this, it.toDouble()) } ?: "")
        val units = listOf(MeasureUnit.KG, MeasureUnit.G, MeasureUnit.L, MeasureUnit.ML, MeasureUnit.PIECE, MeasureUnit.PACK)
        val chips = units.associateWith { u ->
            Chip(this).apply {
                id = View.generateViewId(); isCheckable = true
                text = if (u == MeasureUnit.PACK) getString(R.string.unit_pack) else Fmt.unitLabel(this@ScanReviewActivity, u.code)
                isChecked = l.unit == u
            }.also { d.groupUnit.addView(it) }
        }
        val packUnits = listOf(MeasureUnit.KG, MeasureUnit.G, MeasureUnit.L, MeasureUnit.ML, MeasureUnit.PIECE)
        val packChips = packUnits.associateWith { u ->
            Chip(this).apply {
                id = View.generateViewId(); isCheckable = true
                text = Fmt.unitLabel(this@ScanReviewActivity, u.code); isChecked = l.packageUnit == u
            }.also { d.groupPackageUnit.addView(it) }
        }
        fun packVisible() {
            val pack = chips[MeasureUnit.PACK]!!.isChecked
            d.tilPackage.visibility = if (pack) View.VISIBLE else View.GONE
            d.groupPackageUnit.visibility = if (pack) View.VISIBLE else View.GONE
        }
        d.groupUnit.setOnCheckedStateChangeListener { _, _ -> packVisible() }
        packVisible()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.review_edit_title)
            .setView(d.root)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = d.editName.text.toString().trim()
            val priceText = d.editUnitPrice.text.toString()
            val price = if (priceText.isBlank()) null else Money.parseMillimes(priceText)
            if (name.isEmpty()) { d.tilName.error = getString(R.string.error_required); return@setOnClickListener }
            if (priceText.isNotBlank() && price == null) { d.tilUnitPrice.error = getString(R.string.error_amount); return@setOnClickListener }
            val unit = chips.entries.firstOrNull { it.value.isChecked }?.key
            val packSize = if (unit == MeasureUnit.PACK) NumberParser.parseOrNull(d.editPackage.text.toString()) else null
            val packUnit = if (unit == MeasureUnit.PACK) packChips.entries.firstOrNull { it.value.isChecked }?.key else null
            model.update(l.index) {
                it.copy(
                    name = name, quantity = NumberParser.parseOrNull(d.editQty.text.toString()), unitPriceMillimes = price,
                    unit = unit, packageSize = packSize?.takeIf { s -> s.signum() > 0 }, packageUnit = packUnit, edited = true
                )
            }
            dialog.dismiss()
            renderLines()
        }
    }

    private fun onConfirm() {
        val ready = model.ready()
        if (ready.isEmpty()) return
        val increases = ready.count { (model.evaluation(it).diff ?: 0) > 0 }
        Dialogs.confirm(
            this, getString(R.string.review_confirm_title),
            resources.getQuantityString(R.plurals.review_confirm_body, ready.size, ready.size, increases),
            R.string.action_update
        ) { model.confirm() }
    }

    private fun renderDone(s: State.Done) {
        setTitleText(getString(R.string.result_title))
        b.txtResultTitle.text = resources.getQuantityString(R.plurals.result_updated, s.updated, s.updated)
        b.txtResultBody.text = if (s.created > 0) resources.getQuantityString(R.plurals.result_created, s.created, s.created) else getString(R.string.result_body)
        val below = s.impacts.filter { it.fallsBelowTarget }
        b.txtResultAlert.visibility = if (below.isEmpty()) View.GONE else View.VISIBLE
        b.txtResultAlert.text = resources.getQuantityString(R.plurals.result_below, below.size, below.size, below.joinToString { it.recette.nom })
        b.txtNoImpact.visibility = if (s.impacts.isEmpty()) View.VISIBLE else View.GONE
        ImpactViews.fill(b.listImpacts, s.impacts, clickable = true)
        b.txtFooter.text = getString(R.string.result_footer)
        b.btnConfirm.isEnabled = true
        b.btnConfirm.setText(R.string.action_done)
        b.btnConfirm.setOnClickListener { goHome() }
    }

    private fun showImage() {
        val f = model.files.firstOrNull() ?: return
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { InvoiceImages.load(f) } ?: return@launch
            val iv = ImageView(this@ScanReviewActivity).apply { setImageBitmap(bmp); adjustViewBounds = true; contentDescription = getString(R.string.cd_invoice_image) }
            MaterialAlertDialogBuilder(this@ScanReviewActivity).setView(iv).setPositiveButton(R.string.action_close, null).show()
        }
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}

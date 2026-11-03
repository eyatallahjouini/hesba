package com.example.hesba

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.example.hesba.auth.SessionManager
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.Recette
import com.example.hesba.data.Utilisateur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class ComposeRecipeActivity : AppCompatActivity() {

    private class Row(var ingredient: Ingredient, val editQty: EditText, val txtCost: TextView, val view: View)

    private val rows = mutableListOf<Row>()
    private var allIngredients = emptyList<Ingredient>()
    private var margin = 0.40   // 40 % par défaut

    private lateinit var session: SessionManager
    private var currentUser: Utilisateur? = null

    private var editing: Recette? = null
    private var photo: Bitmap? = null

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadPhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_compose_recipe)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        session = SessionManager(this)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAddIngredient).setOnClickListener { chooseIngredient() }
        findViewById<View>(R.id.photoZone).apply {
            clipToOutline = true
            setOnClickListener {
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }

        findViewById<EditText>(R.id.editPrepTime).doAfterTextChanged { recalculate() }
        findViewById<EditText>(R.id.editYield).doAfterTextChanged { recalculate() }
        findViewById<EditText>(R.id.editMargin).doAfterTextChanged { recalculate() }

        findViewById<EditText>(R.id.editRecipeName).doAfterTextChanged { text ->
            val name = text.toString().trim()
            findViewById<TextView>(R.id.txtRecipeName).text = name.ifEmpty { "Nouvelle recette" }
        }

        findViewById<View>(R.id.btnSaveRecipe).setOnClickListener { saveRecipe() }

        followUserData()
        followIngredients()
        updateEmptyMessage()

        val recipeId = intent.getLongExtra("EXTRA_RECETTE_ID", 0L)
        if (recipeId > 0L) {
            loadForEdit(recipeId)
        } else {
            recalculate()
        }
    }

    override fun onResume() {
        super.onResume()
        // Synchronisation dynamique : recharger l'utilisateur et recalculer dès que l'écran redevient visible
        followUserData()
        recalculate()
    }

    private fun followUserData() {
        val currentUserId = session.getUserId()
        lifecycleScope.launch {
            HesbaDatabase.getInstance(this@ComposeRecipeActivity)
                .utilisateurDao()
                .parId(currentUserId)
                .collect { user ->
                    currentUser = user
                    recalculate()
                }
        }
    }

    private fun loadForEdit(recipeId: Long) {
        lifecycleScope.launch {
            val db = HesbaDatabase.getInstance(this@ComposeRecipeActivity)
            val recette = db.recetteDao().parId(recipeId)
            if (recette == null) {
                toast("Recette introuvable")
                finish()
                return@launch
            }
            editing = recette
            margin = recette.margeVoulue / 100.0

            findViewById<TextView>(R.id.txtComposeSubtitle).text = "Modifier la recette"
            findViewById<TextView>(R.id.btnSaveRecipe).text = "Enregistrer les modifications"
            findViewById<EditText>(R.id.editRecipeName).setText(recette.nom)
            findViewById<EditText>(R.id.editYield).setText(recette.nombreParts.toString())
            findViewById<EditText>(R.id.editMargin).setText(number(recette.margeVoulue))

            val editPrepTime = findViewById<EditText>(R.id.editPrepTime)
            editPrepTime?.setText(recette.tempsPreparationMinutes.toString())

            val currentUserId = session.getUserId()
            val ingredientsById = db.ingredientDao().getIngredientsParUtilisateur(currentUserId).first().associateBy { it.id }

            for (ligne in db.ligneRecetteDao().parRecette(recipeId).first()) {
                val ingredient = ingredientsById[ligne.ingredientId] ?: continue
                addRow(ingredient, number(ligne.quantite))
            }

            val existing = withContext(Dispatchers.IO) {
                val file = File(File(filesDir, "photos"), "recette_$recipeId.jpg")
                if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            }
            if (existing != null) showPhoto(existing)

            recalculate()
        }
    }

    private fun number(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString()
        else String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

    private fun followIngredients() {
        lifecycleScope.launch {
            val currentUserId = session.getUserId()
            HesbaDatabase.getInstance(this@ComposeRecipeActivity)
                .ingredientDao().getIngredientsParUtilisateur(currentUserId)
                .collect { ingredients ->
                    allIngredients = ingredients
                    val byId = ingredients.associateBy { it.id }
                    rows.forEach { row -> byId[row.ingredient.id]?.let { row.ingredient = it } }
                    recalculate()
                }
        }
    }

    private fun chooseIngredient() {
        if (allIngredients.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Aucun ingrédient")
                .setMessage("Tu n'as encore aucun ingrédient. Crée-le d'abord avec son prix d'achat.")
                .setPositiveButton("Créer un ingrédient") { _, _ ->
                    startActivity(Intent(this, NouvelIngredientActivity::class.java))
                }
                .setNegativeButton("Annuler", null)
                .show()
            return
        }
        val chosenIds = rows.map { it.ingredient.id }.toSet()
        val available = allIngredients.filter { it.id !in chosenIds }
        if (available.isEmpty()) {
            toast("Tous tes ingrédients sont déjà dans la recette")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choisir un ingrédient")
            .setItems(available.map { "${it.nom} (${unitLabel(it.unite)})" }.toTypedArray()) { _, position ->
                askQuantity(available[position])
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun askQuantity(ingredient: Ingredient) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Quantité en ${unitLabel(ingredient.unite)}"
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, 0, pad, 0)
            addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Quantité : ${ingredient.nom}")
            .setMessage("Quantité utilisée dans la recette (${unitLabel(ingredient.unite)})")
            .setView(container)
            .setPositiveButton("Ajouter") { _, _ ->
                val qty = input.text.toString().replace(',', '.').trim().toDoubleOrNull() ?: 0.0
                if (qty > 0.0) addRow(ingredient, input.text.toString().trim())
                else toast("Quantité invalide")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun addRow(ingredient: Ingredient, qty: String) {
        val list = findViewById<LinearLayout>(R.id.listLines)
        val view = LayoutInflater.from(this).inflate(R.layout.item_recipe_line, list, false)
        view.findViewById<TextView>(R.id.txtIngredientName).text = ingredient.nom
        view.findViewById<TextView>(R.id.txtUnit).text = unitLabel(ingredient.unite)
        val editQty = view.findViewById<EditText>(R.id.editQty)
        editQty.setText(qty)
        editQty.doAfterTextChanged { recalculate() }
        val row = Row(ingredient, editQty, view.findViewById(R.id.txtLineCost), view)

        view.findViewById<View>(R.id.btnRemoveLine).setOnClickListener {
            rows.remove(row)
            list.removeView(view)
            updateEmptyMessage()
            recalculate()
        }
        rows.add(row)
        list.addView(view)
        updateEmptyMessage()
        recalculate()
    }

    private fun updateEmptyMessage() {
        findViewById<View>(R.id.txtNoIngredient).visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun saveRecipe() {
        val name = findViewById<EditText>(R.id.editRecipeName).text.toString().trim()
        val pieces = readNumber(findViewById(R.id.editYield)).toInt()
        val usedRows = rows.filter { readNumber(it.editQty) > 0.0 }

        val marginInput = readNumber(findViewById(R.id.editMargin))
        margin = if (marginInput in 0.0..99.0) marginInput / 100.0 else 0.40

        val prepTimeMin = readNumber(findViewById(R.id.editPrepTime)).toInt()

        when {
            name.isEmpty() -> { toast("Donne un nom à la recette"); return }
            pieces <= 0 -> { toast("Le nombre de parts doit être supérieur à 0"); return }
            usedRows.isEmpty() -> { toast("Ajoute au moins un ingrédient avec sa quantité"); return }
        }

        val (perPiece, price) = computeRecipeCostAndPrice(usedRows, pieces.toDouble())

        findViewById<View>(R.id.btnSaveRecipe).isEnabled = false
        lifecycleScope.launch {
            val db = HesbaDatabase.getInstance(this@ComposeRecipeActivity)
            val userId = session.getUserId()
            val chosenPhoto = photo
            val old = editing

            val photoSaved = db.withTransaction {
                val recipeId = if (old == null) {
                    db.recetteDao().inserer(
                        Recette(
                            utilisateurId = userId,
                            nom = name,
                            nombreParts = pieces,
                            margeVoulue = margin * 100.0,
                            coutParPart = perPiece,
                            prixVente = price,
                            tempsPreparationMinutes = prepTimeMin
                        )
                    )
                } else {
                    db.recetteDao().modifier(
                        old.copy(
                            nom = name,
                            nombreParts = pieces,
                            margeVoulue = margin * 100.0,
                            coutParPart = perPiece,
                            prixVente = price,
                            tempsPreparationMinutes = prepTimeMin
                        )
                    )
                    db.ligneRecetteDao().supprimerParRecette(old.id)
                    old.id
                }
                db.ligneRecetteDao().insererToutes(usedRows.map { row ->
                    LigneRecette(
                        recetteId = recipeId,
                        ingredientId = row.ingredient.id,
                        quantite = readNumber(row.editQty),
                        unite = row.ingredient.unite
                    )
                })
                if (chosenPhoto == null) true
                else withContext(Dispatchers.IO) { savePhoto(chosenPhoto, recipeId) }
            }
            if (!photoSaved) toast("La photo n'a pas pu être enregistrée")
            toast(
                if (old == null) "Recette « $name » enregistrée · ${money(perPiece)} par part"
                else "Recette « $name » modifiée · ${money(perPiece)} par part"
            )
            finish()
        }
    }

    // Calcul du coût complet (ingrédients + main-d'œuvre + loyer/charges selon le volume mensuel global)
    private fun computeRecipeCostAndPrice(usedRows: List<Row>, pieces: Double): Pair<Double, Double> {
        // 1. Si aucun ingrédient n'est présent, le coût et le prix sont à 0
        if (usedRows.isEmpty()) {
            return Pair(0.0, 0.0)
        }

        // 2. Coût direct des ingrédients
        val ingredientCosts = usedRows.map { row ->
            CostCalculator.lineCost(
                readNumber(row.editQty),
                CostCalculator.pricePerUnit(row.ingredient.prixPaye, row.ingredient.quantiteEmballage)
            )
        }
        val ingredientBatchCost = CostCalculator.recipeCost(ingredientCosts)

        val user = currentUser

        // 3. Identification du type d'activité (par défaut "domicile" en mode invité/sans profil)
        val activite = user?.activite ?: "domicile"

        // 4. Temps de préparation dynamique saisi par l'utilisateur (converti en heures)
        val prepTimeMin = readNumber(findViewById(R.id.editPrepTime)).toInt()
        val tempsPreparationHeures = prepTimeMin / 60.0

        // 5. Calcul de la main-d'œuvre
        val coutMainOeuvre = when (activite) {
            "avec_employe" -> {
                val nbEmployes = if ((user?.nombreEmployes ?: 0) > 0) user!!.nombreEmployes else 1
                val salaireMoyen = if ((user?.salaireMoyenEmploye ?: 0.0) > 0.0) user!!.salaireMoyenEmploye else 1200.0
                val masseSalariale = nbEmployes * salaireMoyen
                CostCalculator.laborCost(masseSalariale, 160.0, tempsPreparationHeures)
            }
            "sans_employe" -> {
                CostCalculator.laborCost(1500.0, 160.0, tempsPreparationHeures)
            }
            else -> 0.0 // domicile (0 DT de main-d'œuvre)
        }

        // 6. Récupération des charges du local
        val rent = user?.loyerMensuel ?: 0.0
        val volumeMensuel = if ((user?.volumeMensuel ?: 0) > 0) user!!.volumeMensuel.toDouble() else 300.0

        val chargesFixesPart = when (activite) {
            "sans_employe", "avec_employe" -> {
                if (rent > 0.0 && volumeMensuel > 0.0) {
                    CostCalculator.fixedCostPerUnit(rent, volumeMensuel)
                } else 0.0
            }
            else -> 0.0 // domicile (0 DT de loyer)
        }

        // 7. Calcul final du coût par part et du prix conseillé
        val totalBatchCost = ingredientBatchCost + coutMainOeuvre
        val safePieces = if (pieces > 0.0) pieces else 1.0
        val perPiece = CostCalculator.costPerPiece(totalBatchCost, safePieces) + chargesFixesPart
        val price = CostCalculator.sellingPriceHT(perPiece, margin)

        return Pair(perPiece, price)
    }

    private fun recalculate() {
        val lineCosts = rows.map { row ->
            val qty = readNumber(row.editQty)
            val pricePerUnit = CostCalculator.pricePerUnit(row.ingredient.prixPaye, row.ingredient.quantiteEmballage)
            val cost = CostCalculator.lineCost(qty, pricePerUnit)
            row.txtCost.text = money(cost)
            cost
        }

        val ingredientBatchCost = CostCalculator.recipeCost(lineCosts)
        val pieces = readNumber(findViewById(R.id.editYield))

        val marginInput = readNumber(findViewById(R.id.editMargin))
        margin = if (marginInput in 0.0..99.0) marginInput / 100.0 else 0.40

        val (perPiece, price) = computeRecipeCostAndPrice(rows, pieces)

        findViewById<TextView>(R.id.txtBatchCost).text = money(ingredientBatchCost)
        findViewById<TextView>(R.id.txtCostPerPiece).text = money(perPiece)

        val marginPercentInt = (margin * 100).toInt()
        findViewById<TextView>(R.id.txtAdvisedPrice).text =
            "Prix conseillé (marge $marginPercentInt %) : ${money(price)}"

        val top = CostCalculator.mostExpensiveIndex(lineCosts)
        findViewById<TextView>(R.id.txtMostExpensive).text = if (top == -1) {
            "Ingrédient le plus cher : —"
        } else {
            val share = if (ingredientBatchCost > 0) (lineCosts[top] / ingredientBatchCost * 100).toInt() else 0
            "Ingrédient le plus cher : ${rows[top].ingredient.nom} (${money(lineCosts[top])}, $share % du lot)"
        }
    }

    private fun loadPhoto(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { decodeScaled(uri, 1080) }.getOrNull() }
            if (bitmap == null) {
                toast("Impossible d'ouvrir cette photo")
                return@launch
            }
            photo = bitmap
            showPhoto(bitmap)
        }
    }

    private fun showPhoto(bitmap: Bitmap) {
        findViewById<ImageView>(R.id.imgRecipePhoto).apply {
            setImageBitmap(bitmap)
            visibility = View.VISIBLE
        }
        findViewById<View>(R.id.photoPlaceholder).visibility = View.GONE
        findViewById<View>(R.id.txtChangePhoto).visibility = View.VISIBLE
    }

    private fun decodeScaled(uri: Uri, maxSize: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = maxSize.toFloat() / maxOf(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun savePhoto(bitmap: Bitmap, recipeId: Long): Boolean = runCatching {
        val dir = File(filesDir, "photos").apply { mkdirs() }
        FileOutputStream(File(dir, "recette_$recipeId.jpg")).use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
        }
    }.getOrDefault(false)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun readNumber(edit: EditText?): Double =
        edit?.text?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0

    private fun money(value: Double) = String.format(Locale.FRANCE, "%.2f dt", value)

    private fun unitLabel(unit: String) = if (unit == "piece") "pièces" else unit
}
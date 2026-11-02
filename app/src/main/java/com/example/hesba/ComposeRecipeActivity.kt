package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.Recette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

// Nouvelle recette : même fonctionnement que l'écran d'Aya (NouvelleRecetteActivity).
// La recette démarre vide ; l'utilisateur choisit chaque ingrédient dans sa liste puis tape la quantité.
class ComposeRecipeActivity : AppCompatActivity() {

    // une ligne de l'écran = un ingrédient choisi + son champ de quantité + son coût affiché
    private class Row(var ingredient: Ingredient, val editQty: EditText, val txtCost: TextView)

    private val rows = mutableListOf<Row>()
    private var allIngredients = emptyList<Ingredient>()
    private val margin = 0.40   // 40 %, fixe pour l'instant

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_compose_recipe)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAddIngredient).setOnClickListener { chooseIngredient() }

        findViewById<EditText>(R.id.editYield).doAfterTextChanged { recalculate() }
        findViewById<EditText>(R.id.editRecipeName).doAfterTextChanged { text ->
            val name = text.toString().trim()
            findViewById<TextView>(R.id.txtRecipeName).text = name.ifEmpty { "Nouvelle recette" }
        }
        findViewById<View>(R.id.btnSaveRecipe).setOnClickListener { saveRecipe() }

        followIngredients()
        updateEmptyMessage()
        recalculate()
    }

    // suit la table ingredient : un ingrédient créé devient choisissable,
    // un prix changé met à jour les lignes déjà ajoutées
    private fun followIngredients() {
        lifecycleScope.launch {
            HesbaDatabase.getInstance(this@ComposeRecipeActivity).ingredientDao().tous().collect { ingredients ->
                allIngredients = ingredients
                val byId = ingredients.associateBy { it.id }
                rows.forEach { row -> byId[row.ingredient.id]?.let { row.ingredient = it } }
                recalculate()
            }
        }
    }

    // « + Ajouter un ingrédient » : liste des ingrédients pas encore dans la recette
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
        rows.add(Row(ingredient, editQty, view.findViewById(R.id.txtLineCost)))
        list.addView(view)
        updateEmptyMessage()
        recalculate()
    }

    private fun updateEmptyMessage() {
        findViewById<View>(R.id.txtNoIngredient).visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    // enregistre la recette puis ses lignes (ingrédient + quantité, jamais le prix)
    private fun saveRecipe() {
        val name = findViewById<EditText>(R.id.editRecipeName).text.toString().trim()
        val pieces = readNumber(findViewById(R.id.editYield)).toInt()
        val usedRows = rows.filter { readNumber(it.editQty) > 0.0 }

        when {
            name.isEmpty() -> { toast("Donne un nom à la recette"); return }
            pieces <= 0 -> { toast("Le nombre de parts doit être supérieur à 0"); return }
            usedRows.isEmpty() -> { toast("Ajoute au moins un ingrédient avec sa quantité"); return }
        }

        val batch = CostCalculator.recipeCost(usedRows.map { row ->
            CostCalculator.lineCost(
                readNumber(row.editQty),
                CostCalculator.pricePerUnit(row.ingredient.prixPaye, row.ingredient.quantiteEmballage)
            )
        })
        val perPiece = CostCalculator.costPerPiece(batch, pieces.toDouble())
        val price = CostCalculator.sellingPriceHT(perPiece, margin)

        findViewById<View>(R.id.btnSaveRecipe).isEnabled = false
        lifecycleScope.launch {
            val db = HesbaDatabase.getInstance(this@ComposeRecipeActivity)
            // pas encore de connexion : on prend l'utilisateur enregistré, sinon 1 (comme NouvelleRecetteActivity)
            val userId = db.utilisateurDao().utilisateurCourant().first()?.id?.toInt() ?: 1
            db.withTransaction {
                val recipeId = db.recetteDao().inserer(
                    Recette(
                        utilisateurId = userId,
                        nom = name,
                        nombreParts = pieces,
                        margeVoulue = margin * 100,
                        coutParPart = perPiece,
                        prixVente = price
                    )
                )
                db.ligneRecetteDao().insererToutes(usedRows.map { row ->
                    LigneRecette(
                        recetteId = recipeId,
                        ingredientId = row.ingredient.id,
                        quantite = readNumber(row.editQty),
                        unite = row.ingredient.unite
                    )
                })
            }
            toast("Recette « $name » enregistrée · ${money(perPiece)} par part")
            finish()
        }
    }

    private fun recalculate() {
        val lineCosts = rows.map { row ->
            val qty = readNumber(row.editQty)
            val pricePerUnit = CostCalculator.pricePerUnit(row.ingredient.prixPaye, row.ingredient.quantiteEmballage)
            val cost = CostCalculator.lineCost(qty, pricePerUnit)
            row.txtCost.text = money(cost)
            cost
        }
        val batch = CostCalculator.recipeCost(lineCosts)
        val pieces = readNumber(findViewById(R.id.editYield))
        val perPiece = CostCalculator.costPerPiece(batch, pieces)
        val price = CostCalculator.sellingPriceHT(perPiece, margin)

        findViewById<TextView>(R.id.txtBatchCost).text = money(batch)
        findViewById<TextView>(R.id.txtCostPerPiece).text = money(perPiece)
        findViewById<TextView>(R.id.txtAdvisedPrice).text =
            "Prix conseillé (marge ${(margin * 100).toInt()} %) : ${money(price)}"

        // conseiller de marge : l'ingrédient qui pèse le plus dans le coût du lot
        val top = CostCalculator.mostExpensiveIndex(lineCosts)
        findViewById<TextView>(R.id.txtMostExpensive).text = if (top == -1) {
            "Ingrédient le plus cher : —"
        } else {
            val share = (lineCosts[top] / batch * 100).toInt()
            "Ingrédient le plus cher : ${rows[top].ingredient.nom} (${money(lineCosts[top])}, $share % du lot)"
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun readNumber(edit: EditText): Double =
        edit.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun money(value: Double) = String.format(Locale.FRANCE, "%.2f dt", value)

    private fun unitLabel(unit: String) = if (unit == "piece") "pièces" else unit
}

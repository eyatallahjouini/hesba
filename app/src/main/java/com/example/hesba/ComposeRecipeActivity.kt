package com.example.hesba
import androidx.lifecycle.lifecycleScope
import com.example.hesba.data.HesbaDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import android.widget.Toast
import androidx.room.withTransaction
import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.Recette
import java.util.Locale

class ComposeRecipeActivity : AppCompatActivity() {

    // une ligne de l'écran = un ingrédient + son champ de quantité + son coût affiché
    private class Row(val ingredient: Ingredient, val editQty: EditText, val txtCost: TextView)

    private val rows = mutableListOf<Row>()
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

        val list = findViewById<LinearLayout>(R.id.listLines)
        list.removeAllViews()   // enlève les lignes d'exemple du dessin
        loadFromDatabase(list)

        findViewById<EditText>(R.id.editYield).doAfterTextChanged { recalculate() }
        findViewById<EditText>(R.id.editRecipeName).doAfterTextChanged { text ->
            val name = text.toString().trim()
            findViewById<TextView>(R.id.txtRecipeName).text = name.ifEmpty { "Nouvelle recette" }
        }
        findViewById<View>(R.id.btnSaveRecipe).setOnClickListener { saveRecipe() }
        recalculate()
    }

    // enregistre la recette puis ses lignes (ingrédient + quantité, jamais le prix)
    private fun saveRecipe() {
        val name = findViewById<EditText>(R.id.editRecipeName).text.toString().trim()
        val pieces = readNumber(findViewById(R.id.editYield)).toInt()
        val usedRows = rows.filter { readNumber(it.editQty) > 0.0 }

        when {
            name.isEmpty() -> { toast("Donne un nom à la recette"); return }
            usedRows.isEmpty() -> { toast("Mets une quantité pour au moins un ingrédient"); return }
            pieces <= 0 -> { toast("Le nombre de pièces doit être supérieur à 0"); return }
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
            toast("Recette « $name » enregistrée")
            finish()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun loadFromDatabase(list: LinearLayout) {
        lifecycleScope.launch {
            val dao = HesbaDatabase.getInstance(this@ComposeRecipeActivity).ingredientDao()
            var ingredients = dao.tous().first()
            if (ingredients.isEmpty()) {
                dao.inserer(Ingredient(nom = "Harissa", unite = "g", quantiteEmballage = 1000.0, prixPaye = 6.0))
                dao.inserer(Ingredient(nom = "Pain", unite = "piece", quantiteEmballage = 1.0, prixPaye = 0.25))
                ingredients = dao.tous().first()
            }
            for (ingredient in ingredients) addRow(list, ingredient)
            recalculate()
        }
    }

    private fun addRow(list: LinearLayout, ingredient: Ingredient) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_recipe_line, list, false)
        view.findViewById<TextView>(R.id.txtIngredientName).text = ingredient.nom
        view.findViewById<TextView>(R.id.txtUnit).text = unitLabel(ingredient.unite)
        val editQty = view.findViewById<EditText>(R.id.editQty)
        editQty.setText("")
        val txtCost = view.findViewById<TextView>(R.id.txtLineCost)
        txtCost.text = money(0.0)
        editQty.doAfterTextChanged { recalculate() }
        rows.add(Row(ingredient, editQty, txtCost))
        list.addView(view)
    }

    // le cœur : on appelle TES formules
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

    private fun readNumber(edit: EditText): Double =
        edit.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun money(value: Double) = String.format(Locale.FRANCE, "%.2f dt", value)

    private fun unitLabel(unit: String) = if (unit == "piece") "pièces" else unit
}
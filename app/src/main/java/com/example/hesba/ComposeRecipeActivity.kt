package com.example.hesba

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
import com.example.hesba.data.Ingredient
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
        for (ingredient in loadIngredients()) addRow(list, ingredient)

        findViewById<EditText>(R.id.editYield).doAfterTextChanged { recalculate() }
        recalculate()
    }

    // ÉTAPE 1 : liste écrite à la main (sans base de données)
    private fun loadIngredients(): List<Ingredient> = listOf(
        Ingredient(nom = "Harissa", unite = "g", quantiteEmballage = 1000.0, prixPaye = 6.0),
        Ingredient(nom = "Pain", unite = "piece", quantiteEmballage = 1.0, prixPaye = 0.25)
    )

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
    }

    private fun readNumber(edit: EditText): Double =
        edit.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun money(value: Double) = String.format(Locale.FRANCE, "%.2f dt", value)

    private fun unitLabel(unit: String) = if (unit == "piece") "pièces" else unit
}
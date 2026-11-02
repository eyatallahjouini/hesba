package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.Ingredient
import kotlinx.coroutines.launch
import java.util.Locale

// Onglet « Ingrédients » : liste des ingrédients et de leur prix d'achat
class IngredientsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_ingredients)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        BottomNav.setup(this, BottomNav.Tab.INGREDIENTS)

        // le formulaire d'ajout est celui d'Aya (NouvelIngredientActivity) : on l'ouvre sans le modifier
        findViewById<View>(R.id.btnAddIngredient).setOnClickListener {
            startActivity(Intent(this, NouvelIngredientActivity::class.java))
        }

        val list = findViewById<LinearLayout>(R.id.listIngredients)

        val db = HesbaDatabase.getInstance(this)
        lifecycleScope.launch {
            db.ingredientDao().tous().collect { ingredients -> show(list, ingredients) }
        }
    }

    private fun show(list: LinearLayout, ingredients: List<Ingredient>) {
        list.removeAllViews()
        val count = ingredients.size
        findViewById<TextView>(R.id.txtIngredientCount).text =
            "$count ingrédient${if (count > 1) "s" else ""} · prix d'achat en dinars"
        list.visibility = if (ingredients.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.txtNoIngredient).visibility = if (ingredients.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        for (ingredient in ingredients) {
            val view = inflater.inflate(R.layout.item_ingredient, list, false)
            view.findViewById<TextView>(R.id.txtIngredientName).text = ingredient.nom
            view.findViewById<TextView>(R.id.txtIngredientUnit).text = packageLabel(ingredient)
            view.findViewById<TextView>(R.id.txtUpdated).visibility = View.GONE
            view.findViewById<TextView>(R.id.txtPrice).text = money(ingredient.prixPaye)
            view.setOnClickListener { askNewPrice(ingredient) }
            list.addView(view)
        }
    }

    // appui sur un ingrédient : changer son prix d'achat, puis recalculer les recettes qui l'utilisent
    private fun askNewPrice(ingredient: Ingredient) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.2f", ingredient.prixPaye))
            selectAll()
        }
        val margin = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(margin, 0, margin, 0)
            addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        AlertDialog.Builder(this)
            .setTitle("Nouveau prix : ${ingredient.nom}")
            .setMessage("Prix payé pour ${packageLabel(ingredient)} (en dinars)")
            .setView(container)
            .setPositiveButton("Enregistrer") { _, _ ->
                val price = input.text.toString().replace(',', '.').trim().toDoubleOrNull()
                if (price == null || price <= 0.0) {
                    Toast.makeText(this, "Prix invalide", Toast.LENGTH_SHORT).show()
                } else if (price != ingredient.prixPaye) {
                    savePrice(ingredient, price)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun savePrice(ingredient: Ingredient, price: Double) {
        lifecycleScope.launch {
            val db = HesbaDatabase.getInstance(this@IngredientsActivity)
            db.ingredientDao().modifier(
                ingredient.copy(prixPaye = price, dateMiseAJour = System.currentTimeMillis())
            )
            val count = RecipeCosting.refreshRecipesUsing(db, ingredient.id)
            val message = when (count) {
                0 -> "Prix mis à jour"
                1 -> "Prix mis à jour · 1 recette recalculée"
                else -> "Prix mis à jour · $count recettes recalculées"
            }
            Toast.makeText(this@IngredientsActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    // 1000 g -> « 1 kg », 500 ml -> « 500 ml », 30 piece -> « 30 pièces »
    private fun packageLabel(ingredient: Ingredient): String {
        val q = ingredient.quantiteEmballage
        return when (ingredient.unite) {
            "g" -> if (q >= 1000) "${number(q / 1000)} kg" else "${number(q)} g"
            "ml" -> if (q >= 1000) "${number(q / 1000)} L" else "${number(q)} ml"
            else -> if (q > 1) "${number(q)} pièces" else "${number(q)} pièce"
        }
    }

    private fun number(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString()
        else String.format(Locale.FRANCE, "%.2f", value).trimEnd('0').trimEnd(',')

    private fun money(value: Double) = String.format(Locale.FRANCE, "%.2f dt", value)
}

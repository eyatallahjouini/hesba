package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
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
        list.removeAllViews()   // enlève les ingrédients d'exemple du dessin

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

        val inflater = LayoutInflater.from(this)
        for (ingredient in ingredients) {
            val view = inflater.inflate(R.layout.item_ingredient, list, false)
            view.findViewById<TextView>(R.id.txtIngredientName).text = ingredient.nom
            view.findViewById<TextView>(R.id.txtIngredientUnit).text = packageLabel(ingredient)
            view.findViewById<TextView>(R.id.txtUpdated).visibility = View.GONE
            view.findViewById<TextView>(R.id.txtPrice).text = money(ingredient.prixPaye)
            list.addView(view)
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

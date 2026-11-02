package com.example.hesba

import android.content.Intent
import android.database.sqlite.SQLiteConstraintException
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
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
import kotlinx.coroutines.flow.first
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
            // ligne ou crayon : modifier · corbeille : supprimer
            view.findViewById<View>(R.id.rowIngredient).setOnClickListener { openEdit(ingredient) }
            view.findViewById<View>(R.id.btnEditIngredient).setOnClickListener { openEdit(ingredient) }
            view.findViewById<View>(R.id.btnDeleteIngredient).setOnClickListener { askDelete(ingredient) }
            list.addView(view)
        }
    }

    // ouvre la page d'Aya en mode « Modifier »
    private fun openEdit(ingredient: Ingredient) {
        startActivity(
            Intent(this, NouvelIngredientActivity::class.java)
                .putExtra("EXTRA_INGREDIENT_ID", ingredient.id)
        )
    }

    // la base refuse de supprimer un ingrédient utilisé (LigneRecette : RESTRICT) : on prévient avant
    private fun askDelete(ingredient: Ingredient) {
        lifecycleScope.launch {
            val db = HesbaDatabase.getInstance(this@IngredientsActivity)
            val usedIn = db.recetteDao().toutes().first().filter { recette ->
                db.ligneRecetteDao().parRecette(recette.id).first().any { it.ingredientId == ingredient.id }
            }
            if (usedIn.isNotEmpty()) {
                AlertDialog.Builder(this@IngredientsActivity)
                    .setTitle("Suppression impossible")
                    .setMessage(
                        "« ${ingredient.nom} » est utilisé dans : ${usedIn.joinToString { it.nom }}.\n\n" +
                            "Retire-le d'abord de ces recettes."
                    )
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }
            AlertDialog.Builder(this@IngredientsActivity)
                .setTitle("Supprimer « ${ingredient.nom} » ?")
                .setMessage("Cette action est définitive.")
                .setPositiveButton("Supprimer") { _, _ -> delete(ingredient) }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    private fun delete(ingredient: Ingredient) {
        lifecycleScope.launch {
            val db = HesbaDatabase.getInstance(this@IngredientsActivity)
            try {
                db.ingredientDao().supprimer(ingredient)
                Toast.makeText(this@IngredientsActivity, "« ${ingredient.nom} » supprimé", Toast.LENGTH_SHORT).show()
            } catch (e: SQLiteConstraintException) {
                // ajouté à une recette entre-temps
                Toast.makeText(this@IngredientsActivity, "Utilisé dans une recette : suppression impossible", Toast.LENGTH_LONG).show()
            }
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

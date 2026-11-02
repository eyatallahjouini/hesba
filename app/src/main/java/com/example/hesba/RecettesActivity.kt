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
import com.example.hesba.data.Recette
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

// Onglet « Recettes » : liste des recettes enregistrées dans la base
class RecettesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_recipes)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        BottomNav.setup(this, BottomNav.Tab.RECETTES)

        findViewById<View>(R.id.btnNewRecipe).setOnClickListener {
            startActivity(Intent(this, ComposeRecipeActivity::class.java))
        }

        val list = findViewById<LinearLayout>(R.id.listRecipes)
        list.removeAllViews()   // enlève les recettes d'exemple du dessin

        val db = HesbaDatabase.getInstance(this)
        lifecycleScope.launch {
            // la liste se redessine quand une recette OU le prix d'un ingrédient change
            combine(db.recetteDao().toutes(), db.ingredientDao().tous()) { recettes, ingredients ->
                recettes to ingredients
            }.collect { (recettes, ingredients) -> show(db, list, recettes, ingredients) }
        }
    }

    private suspend fun show(
        db: HesbaDatabase,
        list: LinearLayout,
        recettes: List<Recette>,
        ingredients: List<Ingredient>
    ) {
        val ingredientsById = ingredients.associateBy { it.id }
        // coût recalculé en direct avec les prix actuels (sinon valeur enregistrée si la recette n'a pas de lignes)
        val results = recettes.map { recette ->
            val lignes = db.ligneRecetteDao().parRecette(recette.id).first()
            if (lignes.isEmpty()) RecipeCosting.Result(recette.coutParPart, recette.prixVente ?: 0.0)
            else RecipeCosting.compute(recette, lignes, ingredientsById)
        }
        list.removeAllViews()
        val count = recettes.size
        findViewById<TextView>(R.id.txtRecipeCount).text =
            if (count <= 1) "$count recette enregistrée" else "$count recettes enregistrées"

        val inflater = LayoutInflater.from(this)
        for ((recette, result) in recettes.zip(results)) {
            val view = inflater.inflate(R.layout.item_recipe, list, false)
            view.findViewById<TextView>(R.id.txtName).text = recette.nom
            view.findViewById<TextView>(R.id.txtServings).text = "${recette.nombreParts} parts"
            view.findViewById<TextView>(R.id.txtMargin).text = "▲ Marge ${recette.margeVoulue.toInt()} %"
            view.findViewById<TextView>(R.id.txtCost).text = money(result.costPerPiece)
            view.findViewById<TextView>(R.id.txtPrice).text = money(result.advisedPrice)
            list.addView(view)
        }
    }

    private fun money(value: Double) = String.format(Locale.FRANCE, "%.2f dt", value)
}

package com.example.hesba

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.os.Bundle
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
        // photos lues hors du fil principal : photos/recette_<id>.jpg (seulement si la recette en a une)
        val photos = withContext(Dispatchers.IO) { recettes.associate { it.id to loadThumbnail(it.id) } }
        list.removeAllViews()
        val count = recettes.size
        findViewById<TextView>(R.id.txtRecipeCount).text =
            if (count <= 1) "$count recette enregistrée" else "$count recettes enregistrées"
        findViewById<View>(R.id.txtNoRecipe).visibility = if (count == 0) View.VISIBLE else View.GONE
        list.visibility = if (count == 0) View.GONE else View.VISIBLE

        val inflater = LayoutInflater.from(this)
        for ((recette, result) in recettes.zip(results)) {
            val view = inflater.inflate(R.layout.item_recipe, list, false)
            photos[recette.id]?.let { bitmap ->
                view.findViewById<ImageView>(R.id.imgRecipePhoto).apply {
                    setImageBitmap(bitmap)
                    outlineProvider = CircleOutline
                    clipToOutline = true
                    visibility = View.VISIBLE
                }
            }
            view.findViewById<TextView>(R.id.txtName).text = recette.nom
            view.findViewById<TextView>(R.id.txtServings).text = "${recette.nombreParts} parts"
            // même règle que la liste d'Aya : vert à partir de 20 %, rouge en dessous
            val margin = recette.margeVoulue.toInt()
            val badge = view.findViewById<TextView>(R.id.txtMargin)
            if (margin >= 20) {
                badge.text = "▲ Marge $margin %"
                badge.setBackgroundResource(R.drawable.bg_badge_green)
                badge.setTextColor(getColor(R.color.vert))
            } else {
                badge.text = "▼ Marge $margin %"
                badge.setBackgroundResource(R.drawable.bg_badge_red)
                badge.setTextColor(getColor(R.color.rouge))
            }
            view.findViewById<TextView>(R.id.txtCost).text = money(result.costPerPiece)
            view.findViewById<TextView>(R.id.txtPrice).text = money(result.advisedPrice)
            // carte ou crayon : modifier · corbeille : supprimer
            view.findViewById<View>(R.id.cardRecipe).setOnClickListener { openEdit(recette) }
            view.findViewById<View>(R.id.btnEditRecipe).setOnClickListener { openEdit(recette) }
            view.findViewById<View>(R.id.btnDeleteRecipe).setOnClickListener { askDelete(recette) }
            list.addView(view)
        }
    }

    // ouvre « Nouvelle recette » en mode « Modifier »
    private fun openEdit(recette: Recette) {
        startActivity(
            Intent(this, ComposeRecipeActivity::class.java).putExtra("EXTRA_RECETTE_ID", recette.id)
        )
    }

    private fun askDelete(recette: Recette) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer « ${recette.nom} » ?")
            .setMessage("La recette, ses ingrédients et sa photo seront supprimés. Tes ingrédients restent dans ta liste.")
            .setPositiveButton("Supprimer") { _, _ -> delete(recette) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // les lignes partent avec la recette (LigneRecette : CASCADE) ; la photo est effacée à la main
    private fun delete(recette: Recette) {
        lifecycleScope.launch {
            HesbaDatabase.getInstance(this@RecettesActivity).recetteDao().supprimer(recette)
            withContext(Dispatchers.IO) { photoFile(recette.id).delete() }
            Toast.makeText(this@RecettesActivity, "« ${recette.nom} » supprimée", Toast.LENGTH_SHORT).show()
        }
    }

    private fun photoFile(recipeId: Long) = File(File(filesDir, "photos"), "recette_$recipeId.jpg")

    // petite version de la photo (environ 150 px) pour ne pas charger l'image entière
    private fun loadThumbnail(recipeId: Long): Bitmap? {
        val file = photoFile(recipeId)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 150) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // découpe la photo en rond
    private object CircleOutline : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    private fun money(value: Double) = String.format(Locale.FRANCE, "%.2f dt", value)
}

package com.example.hesba

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.hesba.auth.SessionManager
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.Recette
import com.example.hesba.data.Utilisateur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// Onglet « Recettes » : liste des recettes enregistrées avec calcul dynamique selon le profil utilisateur
class RecettesActivity : AppCompatActivity() {

    private lateinit var session: SessionManager

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

        session = SessionManager(this)

        findViewById<View>(R.id.btnNewRecipe).setOnClickListener {
            startActivity(Intent(this, ComposeRecipeActivity::class.java))
        }

        val list = findViewById<LinearLayout>(R.id.listRecipes)
        val db = HesbaDatabase.getInstance(this)
        val currentUserId = session.getUserId()

        lifecycleScope.launch {
            // Écoute combinée : Recettes + Ingrédients + Profil Utilisateur
            combine(
                db.recetteDao().getRecettesParUtilisateur(currentUserId),
                db.ingredientDao().getIngredientsParUtilisateur(currentUserId),
                db.utilisateurDao().parId(currentUserId)
            ) { recettes, ingredients, user ->
                Triple(recettes, ingredients, user)
            }.collect { (recettes, ingredients, user) ->
                show(db, list, recettes, ingredients, user)
            }
        }
    }

    private suspend fun show(
        db: HesbaDatabase,
        list: LinearLayout,
        recettes: List<Recette>,
        ingredients: List<Ingredient>,
        user: Utilisateur?
    ) {
        val ingredientsById = ingredients.associateBy { it.id }

        // Calcul dynamique synchronisé avec les données du profil actuel
        val results = recettes.map { recette ->
            val lignes = db.ligneRecetteDao().parRecette(recette.id).first()
            computeDynamicCostAndPrice(recette, lignes, ingredientsById, user)
        }

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

            // Affichage des prix calculés en parfaite harmonie avec ComposeRecipeActivity et ProfilActivity
            view.findViewById<TextView>(R.id.txtCost).text = money(result.first)
            view.findViewById<TextView>(R.id.txtPrice).text = money(result.second)

            view.findViewById<View>(R.id.cardRecipe).setOnClickListener { openEdit(recette) }
            view.findViewById<View>(R.id.btnEditRecipe).setOnClickListener { openEdit(recette) }
            view.findViewById<View>(R.id.btnDeleteRecipe).setOnClickListener { askDelete(recette) }
            list.addView(view)
        }
    }

    // Calcul dynamique miroir à celui de ComposeRecipeActivity
    private fun computeDynamicCostAndPrice(
        recette: Recette,
        lignes: List<LigneRecette>,
        ingredientsById: Map<Long, Ingredient>,
        user: Utilisateur?
    ): Pair<Double, Double> {
        // 1. Si la recette ne contient aucun ingrédient, le coût et le prix sont 0,00 dt
        if (lignes.isEmpty()) {
            return Pair(0.0, 0.0)
        }

        // 2. Coût direct des ingrédients
        val coutIngredientsBatch = lignes.sumOf { ligne ->
            val ing = ingredientsById[ligne.ingredientId]
            if (ing != null) {
                val prixUnitaire = CostCalculator.pricePerUnit(ing.prixPaye, ing.quantiteEmballage)
                CostCalculator.lineCost(ligne.quantite, prixUnitaire)
            } else 0.0
        }

        // 3. Activité (par défaut "domicile" si l'utilisateur est null/non connecté)
        val activite = user?.activite ?: "domicile"
        val prepTimeMin = recette.tempsPreparationMinutes
        val tempsHeures = prepTimeMin / 60.0

        // 4. Calcul de la main-d'œuvre
        val coutMainOeuvre = when (activite) {
            "avec_employe" -> {
                val nbEmp = if ((user?.nombreEmployes ?: 0) > 0) user!!.nombreEmployes else 1
                val salaire = if ((user?.salaireMoyenEmploye ?: 0.0) > 0.0) user!!.salaireMoyenEmploye else 1200.0
                CostCalculator.laborCost(nbEmp * salaire, 160.0, tempsHeures)
            }
            "sans_employe" -> CostCalculator.laborCost(1500.0, 160.0, tempsHeures)
            else -> 0.0 // domicile
        }

        // 5. Charges fixes (Loyer) par part
        val rent = user?.loyerMensuel ?: 0.0
        val volumeMensuel = if ((user?.volumeMensuel ?: 0) > 0) user!!.volumeMensuel.toDouble() else 300.0

        val chargesFixesPart = when (activite) {
            "sans_employe", "avec_employe" -> {
                if (rent > 0.0 && volumeMensuel > 0.0) {
                    CostCalculator.fixedCostPerUnit(rent, volumeMensuel)
                } else 0.0
            }
            else -> 0.0 // domicile
        }

        // 6. Coût complet par part et Prix de vente conseillé
        val safePieces = if (recette.nombreParts > 0) recette.nombreParts.toDouble() else 1.0
        val totalBatch = coutIngredientsBatch + coutMainOeuvre
        val coutParPart = CostCalculator.costPerPiece(totalBatch, safePieces) + chargesFixesPart

        val margeDecimale = recette.margeVoulue / 100.0
        val prixVente = CostCalculator.sellingPriceHT(coutParPart, margeDecimale)

        return Pair(coutParPart, prixVente)
    }

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

    private fun delete(recette: Recette) {
        lifecycleScope.launch {
            HesbaDatabase.getInstance(this@RecettesActivity).recetteDao().supprimer(recette)
            withContext(Dispatchers.IO) { photoFile(recette.id).delete() }
            Toast.makeText(this@RecettesActivity, "« ${recette.nom} » supprimée", Toast.LENGTH_SHORT).show()
        }
    }

    private fun photoFile(recipeId: Long) = File(File(filesDir, "photos"), "recette_$recipeId.jpg")

    private fun loadThumbnail(recipeId: Long): Bitmap? {
        val file = photoFile(recipeId)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 150) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private object CircleOutline : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    private fun money(value: Double) = String.format(Locale.FRANCE, "%.2f dt", value)
}
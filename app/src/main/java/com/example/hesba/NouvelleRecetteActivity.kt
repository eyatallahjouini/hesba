package com.example.hesba
import java.util.Locale
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.Recette
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class NouvelleRecetteActivity : AppCompatActivity() {

    private lateinit var db: HesbaDatabase
    private val utilisateurIdConnecte: Long = 1L

    // Liste des ingrédients ajoutés à la recette (Ingrédient + Quantité utilisée)
    private val ingredientsSelectionnes = mutableListOf<Pair<Ingredient, Double>>()
    private lateinit var adapter: IngredientAdapter

    // Récepteur pour récupérer l'ingrédient renvoyé par NouvelIngredientActivity
    private val ajoutIngredientLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            val id = data.getLongExtra("EXTRA_INGREDIENT_ID", 0L)
            val nom = data.getStringExtra("EXTRA_INGREDIENT_NOM") ?: ""
            val unite = data.getStringExtra("EXTRA_INGREDIENT_UNITE") ?: "g"
            val quantiteEmballage = data.getDoubleExtra("EXTRA_INGREDIENT_QTE_EMBALLAGE", 0.0)
            val prixPaye = data.getDoubleExtra("EXTRA_INGREDIENT_PRIX", 0.0)
            val quantiteUtilisee = data.getDoubleExtra("EXTRA_INGREDIENT_QTE_UTILISEE", quantiteEmballage)

            val ingredient = Ingredient(
                id = id,
                nom = nom,
                unite = unite,
                quantiteEmballage = quantiteEmballage,
                prixPaye = prixPaye
            )

            // Ajout dans la liste locale et rafraîchissement du RecyclerView
            ingredientsSelectionnes.add(Pair(ingredient, quantiteUtilisee))
            adapter.notifyItemInserted(ingredientsSelectionnes.size - 1)

            afficherToast("Ingrédient '$nom' ajouté !")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nouvelle_recette)

        db = HesbaDatabase.getInstance(this)

        val btnRetour = findViewById<ImageButton>(R.id.btnRetour)
        val etNomRecette = findViewById<EditText>(R.id.etNomRecette)
        val etNombreParts = findViewById<EditText>(R.id.etNombreParts)
        val rvIngredients = findViewById<RecyclerView>(R.id.rvIngredients)
        val btnAjouterIngredient = findViewById<Button>(R.id.btnAjouterIngredient)
        val btnCalculer = findViewById<Button>(R.id.btnCalculer)

        btnRetour?.setOnClickListener { finish() }

        // Configuration de l'adaptateur avec la fonction de mise à jour des quantités
        adapter = IngredientAdapter(ingredientsSelectionnes) { position, nouvelleQuantite ->
            val oldPair = ingredientsSelectionnes[position]
            ingredientsSelectionnes[position] = Pair(oldPair.first, nouvelleQuantite)
        }

        rvIngredients.layoutManager = LinearLayoutManager(this)
        rvIngredients.adapter = adapter

        btnAjouterIngredient.setOnClickListener {
            afficherDialogueSelectionIngredient()
        }

        btnCalculer.setOnClickListener {
            val nomRecette = etNomRecette.text.toString().trim()
            val partsText = etNombreParts.text.toString().trim()

            if (nomRecette.isEmpty()) {
                afficherToast("Veuillez saisir un nom de recette")
                return@setOnClickListener
            }

            if (ingredientsSelectionnes.isEmpty()) {
                afficherToast("Veuillez sélectionner au moins un ingrédient")
                return@setOnClickListener
            }

            val nombreParts = partsText.toIntOrNull() ?: 1

            enregistrerRecetteComplete(nomRecette, nombreParts, ingredientsSelectionnes)
        }
    }

    private fun enregistrerRecetteComplete(
        nomRecette: String,
        nbParts: Int,
        ingredients: List<Pair<Ingredient, Double>> // Contient (Ingredient, Quantité_Saisie_Dans_Recette)
    ) {
        lifecycleScope.launch {
            var coutTotalRecette = 0.0

            // 1. Calcul basé UNIQUEMENT sur la quantité modifiée pour la recette
            ingredients.forEach { (ingredient, quantiteUtiliseeDansRecette) ->
                if (ingredient.quantiteEmballage > 0 && quantiteUtiliseeDansRecette > 0) {
                    // Coût unitaire par unité d'emballage (ex: 16 DT / 1 kg = 16 DT/kg)
                    val prixParUnite = ingredient.prixPaye / ingredient.quantiteEmballage

                    // Coût réel pour la quantité exacte utilisée dans la recette
                    val coutIngredientRecette = prixParUnite * quantiteUtiliseeDansRecette
                    coutTotalRecette += coutIngredientRecette
                }
            }

            // 2. Calcul du coût par part
            val coutParPartCalculated = if (nbParts > 0) coutTotalRecette / nbParts else coutTotalRecette

            // 3. Calcul du prix de vente suggéré selon la marge (Marge = 40% par défaut)
            val margeDefaut = 40.0
            val tauxMarge = margeDefaut / 100.0 // 0.40
            val prixVenteParPartCalculated = if (tauxMarge < 1.0) coutParPartCalculated / (1.0 - tauxMarge) else coutParPartCalculated
            // 4. Instanciation de l'objet Recette avec les valeurs calculées
            val nouvelleRecette = Recette(
                utilisateurId = utilisateurIdConnecte.toInt(),
                nom = nomRecette,
                nombreParts = nbParts,
                margeVoulue = margeDefaut,
                coutParPart = coutParPartCalculated,
                prixVente = prixVenteParPartCalculated
            )

            // Insertion dans la BDD Room
            val recetteIdGenerer = db.recetteDao().inserer(nouvelleRecette)

            // 5. Enregistrement dans la table LigneRecette avec la quantité modifiée
            val lignesRecette = ingredients.map { (ingredient, quantiteUtiliseeDansRecette) ->
                LigneRecette(
                    recetteId = recetteIdGenerer,
                    ingredientId = ingredient.id,
                    quantite = quantiteUtiliseeDansRecette, // Quantité modifiée conservée en BDD
                    unite = ingredient.unite
                )
            }

            db.ligneRecetteDao().insererToutes(lignesRecette)

            afficherToast("Recette enregistrée ! Coût/part : ${String.format(Locale.US, "%.3f", coutParPartCalculated)} DT")

            // Si cet écran est le seul de l'app, finish() la quitterait : on ouvre la liste avant
            if (isTaskRoot) {
                startActivity(Intent(this@NouvelleRecetteActivity, MesRecettesActivity::class.java))
            }
            finish()
        }
    }

    private fun afficherToast(message: String) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 150)
        toast.show()
    }
    private fun afficherDialogueSelectionIngredient() {
        lifecycleScope.launch {
            // 1. Récupération de tous les ingrédients enregistrés dans la BDD Room
            val tousLesIngredients = db.ingredientDao().tous().first()

            if (tousLesIngredients.isEmpty()) {
                afficherToast("Aucun ingrédient disponible. Créez-en un via le menu principal !")
                return@launch
            }

            // Tableau des noms d'ingrédients à afficher dans la liste
            val nomsIngredients = tousLesIngredients.map { "${it.nom} (${it.unite})" }.toTypedArray()

            // 2. Création de la boîte de dialogue de sélection
            androidx.appcompat.app.AlertDialog.Builder(this@NouvelleRecetteActivity)
                .setTitle("Sélectionner un ingrédient")
                .setItems(nomsIngredients) { _, position ->
                    val ingredientChoisi = tousLesIngredients[position]
                    demanderQuantitePourRecette(ingredientChoisi)
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    // Boîte de dialogue pour saisir la quantité utilisée dans la recette
    private fun demanderQuantitePourRecette(ingredient: Ingredient) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Quantité en ${ingredient.unite}"
        }

        val container = android.widget.FrameLayout(this).apply {
            val params = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 50
                rightMargin = 50
            }
            addView(input, params)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Quantité pour : ${ingredient.nom}")
            .setMessage("Saisissez la quantité nécessaire (${ingredient.unite}) :")
            .setView(container)
            .setPositiveButton("Ajouter") { _, _ ->
                val qte = input.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                if (qte > 0) {
                    // Ajout à la liste de la recette
                    ingredientsSelectionnes.add(Pair(ingredient, qte))
                    adapter.notifyItemInserted(ingredientsSelectionnes.size - 1)
                    afficherToast("${ingredient.nom} ajouté !")
                } else {
                    afficherToast("Veuillez saisir une quantité valide")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // Adaptateur personnalisé permettant d'éditer la quantité directement dans la liste
    private class IngredientAdapter(
        private val list: List<Pair<Ingredient, Double>>,
        private val onQuantiteChanged: (position: Int, nouvelleQte: Double) -> Unit
    ) : RecyclerView.Adapter<IngredientAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvNom: TextView = view.findViewById(android.R.id.text1)
            val etQuantite: EditText = view.findViewById(android.R.id.edit)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            // Création d'une vue de ligne personnalisée avec un nom et un champ de saisie
            val context = parent.context
            val layout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(16, 16, 16, 16)
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvNom = TextView(context).apply {
                id = android.R.id.text1
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                textSize = 16f
                setTextColor(context.resources.getColor(android.R.color.black, context.theme))
            }

            val etQuantite = EditText(context).apply {
                id = android.R.id.edit
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    200,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                textSize = 14f
            }

            layout.addView(tvNom)
            layout.addView(etQuantite)

            return ViewHolder(layout)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val (ingredient, quantite) = list[position]
            holder.tvNom.text = "${ingredient.nom} (${ingredient.unite})"
            holder.etQuantite.setText(quantite.toString())

            holder.etQuantite.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val nouvelleQte = s.toString().toDoubleOrNull() ?: 0.0
                    onQuantiteChanged(holder.adapterPosition, nouvelleQte)
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        override fun getItemCount(): Int = list.size
    }
}
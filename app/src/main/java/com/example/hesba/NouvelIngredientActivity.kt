package com.example.hesba

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.Ingredient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

class NouvelIngredientActivity : AppCompatActivity() {

    private lateinit var etNom: EditText
    private lateinit var etQuantite: EditText
    private lateinit var etPrix: EditText
    private lateinit var tvPrixCalcul: TextView
    private lateinit var btnEnregistrer: Button

    private var uniteChoisie = "kg" // Unité affichée à l'utilisateur ("kg", "l", "piece")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nouvel_ingredient)

        etNom = findViewById(R.id.etNomIngredient)
        etQuantite = findViewById(R.id.etQuantite)
        etPrix = findViewById(R.id.etPrix)
        tvPrixCalcul = findViewById(R.id.tvPrixUnitaireCalcul)
        btnEnregistrer = findViewById(R.id.btnEnregistrer)

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack?.setOnClickListener { finish() }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                calculerPrixUnitaire()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        etQuantite.addTextChangedListener(textWatcher)
        etPrix.addTextChangedListener(textWatcher)

        val btnKg = findViewById<Button>(R.id.btnKg)
        val btnLitre = findViewById<Button>(R.id.btnLitre)
        val btnPiece = findViewById<Button>(R.id.btnPiece)

        btnKg?.setOnClickListener {
            uniteChoisie = "kg"
            calculerPrixUnitaire()
        }
        btnLitre?.setOnClickListener {
            uniteChoisie = "l"
            calculerPrixUnitaire()
        }
        btnPiece?.setOnClickListener {
            uniteChoisie = "piece"
            calculerPrixUnitaire()
        }

        btnEnregistrer.setOnClickListener { enregistrerIngredient() }
    }

    private fun calculerPrixUnitaire() {
        val qte = etQuantite.text.toString().replace(",", ".").trim().toDoubleOrNull() ?: 0.0
        val prix = etPrix.text.toString().replace(",", ".").trim().toDoubleOrNull() ?: 0.0

        // kg -> prix par gramme, l -> prix par millilitre, piece -> prix par pièce
        val (facteur, libelle) = when (uniteChoisie) {
            "kg" -> 1000.0 to "g"
            "l" -> 1000.0 to "ml"
            else -> 1.0 to "pièce"
        }

        if (qte > 0 && prix > 0) {
            val prixParUnite = prix / (qte * facteur)
            tvPrixCalcul.text = String.format(Locale.FRANCE, "%.4f dt / %s", prixParUnite, libelle)
        } else {
            tvPrixCalcul.text = String.format(Locale.FRANCE, "0,0000 dt / %s", libelle)
        }
    }

    private fun enregistrerIngredient() {
        val nom = etNom.text.toString().trim()
        val qteSaisie = etQuantite.text.toString().replace(",", ".").trim().toDoubleOrNull() ?: 0.0
        val prix = etPrix.text.toString().replace(",", ".").trim().toDoubleOrNull() ?: 0.0

        if (nom.isEmpty() || qteSaisie <= 0 || prix <= 0) {
            afficherToast("Veuillez remplir tous les champs correctement")
            return
        }

        // Conversion en unité de base : 1 kg -> 1000 g, 1 l -> 1000 ml
        val (quantiteBase, uniteBase) = when (uniteChoisie) {
            "kg" -> qteSaisie * 1000 to "g"
            "l" -> qteSaisie * 1000 to "ml"
            else -> qteSaisie to "piece"
        }

        lifecycleScope.launch {
            val db = HesbaDatabase.getInstance(this@NouvelIngredientActivity)

            val existeDeja = db.ingredientDao().tous().first().any {
                it.nom.equals(nom, ignoreCase = true)
            }

            if (existeDeja) {
                afficherToast("L'ingrédient '$nom' existe déjà dans votre stock !")
                return@launch
            }

            db.ingredientDao().inserer(
                Ingredient(
                    nom = nom,
                    unite = uniteBase,
                    quantiteEmballage = quantiteBase,
                    prixPaye = prix
                )
            )

            afficherToast("Ingrédient '$nom' ajouté avec succès !")

            // On reste sur la page : champs vidés, prêt pour un autre ingrédient
            etNom.text.clear()
            etQuantite.text.clear()
            etPrix.text.clear()
            etNom.requestFocus()
        }
    }

    private fun afficherToast(message: String) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 150)
        toast.show()
    }
}
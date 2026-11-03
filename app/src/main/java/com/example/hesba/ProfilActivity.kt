package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.hesba.auth.PasswordHasher
import com.example.hesba.auth.SessionManager
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.Recette
import com.example.hesba.data.Utilisateur
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ProfilActivity : AppCompatActivity() {

    private lateinit var session: SessionManager
    private var currentUser: Utilisateur? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_profil)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        BottomNav.setup(this, BottomNav.Tab.PROFIL)

        session = SessionManager(this)
        val currentUserId = session.getUserId()

        val btnCreateAccount = findViewById<Button>(R.id.btnCreateAccount)
        val btnLogout = findViewById<Button>(R.id.btnLogout)

        val rgActivite = findViewById<RadioGroup>(R.id.rgActivity)
        val layoutRentField = findViewById<LinearLayout>(R.id.layoutRentField)
        val editRent = findViewById<EditText>(R.id.editRent)
        val editMonthlyVolume = findViewById<EditText>(R.id.editMonthlyVolume)
        val layoutEmployeeFields = findViewById<LinearLayout>(R.id.layoutEmployeeFields)
        val editEmpCount = findViewById<EditText>(R.id.editEmpCount)
        val editEmpSalary = findViewById<EditText>(R.id.editEmpSalary)
        val btnSaveProfile = findViewById<Button>(R.id.btnSaveProfile)

        val editCurrentPassword = findViewById<EditText>(R.id.editCurrentPassword)
        val editNewPassword = findViewById<EditText>(R.id.editNewPassword)
        val btnChangePassword = findViewById<Button>(R.id.btnChangePassword)

        rgActivite?.setOnCheckedChangeListener { _, checkedId ->
            val isLocal = (checkedId == R.id.rbLocalSolo || checkedId == R.id.rbLocalStaff)
            layoutRentField?.visibility = if (isLocal) View.VISIBLE else View.GONE
            layoutEmployeeFields?.visibility = if (checkedId == R.id.rbLocalStaff) View.VISIBLE else View.GONE
        }

        btnCreateAccount?.setOnClickListener {
            session.logout()
            val intent = Intent(this, RegisterActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        btnLogout?.setOnClickListener {
            session.logout()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        btnSaveProfile?.setOnClickListener {
            if (currentUserId == SessionManager.ID_UTILISATEUR_INVITE) {
                Toast.makeText(this, "Crée un compte pour enregistrer ton profil", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val checkedId = rgActivite?.checkedRadioButtonId ?: -1
            val activiteCode = when (checkedId) {
                R.id.rbHome -> "domicile"
                R.id.rbLocalSolo -> "sans_employe"
                R.id.rbLocalStaff -> "avec_employe"
                else -> "domicile"
            }

            val rentInput = editRent?.text.toString().replace(',', '.').toDoubleOrNull()
            val rentAmount = if (activiteCode != "domicile") {
                rentInput ?: 900.0
            } else 0.0

            val volumeInput = editMonthlyVolume?.text.toString().toIntOrNull()
            val monthlyVolume = if (activiteCode != "domicile") {
                volumeInput ?: 500
            } else 300

            val nbEmp = if (activiteCode == "avec_employe") {
                editEmpCount?.text.toString().toIntOrNull() ?: 1
            } else 0

            val salaireEmp = if (activiteCode == "avec_employe") {
                editEmpSalary?.text.toString().replace(',', '.').toDoubleOrNull() ?: 1200.0
            } else 0.0

            if (activiteCode != "domicile" && rentAmount <= 0.0) {
                Toast.makeText(this, "Indique le montant du loyer mensuel", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (activiteCode != "domicile" && monthlyVolume <= 0) {
                Toast.makeText(this, "Indique une estimation du volume mensuel", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (activiteCode == "avec_employe" && nbEmp <= 0) {
                Toast.makeText(this, "Indique le nombre d'employés", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val db = HesbaDatabase.getInstance(this@ProfilActivity)
                val user = currentUser ?: return@launch
                val updatedUser = user.copy(
                    activite = activiteCode,
                    loyerMensuel = rentAmount,
                    volumeMensuel = monthlyVolume,
                    nombreEmployes = nbEmp,
                    salaireMoyenEmploye = salaireEmp
                )
                // 1. Mise à jour de l'utilisateur en BDD
                db.utilisateurDao().modifier(updatedUser)

                // 2. Mise à jour physique en BDD des colonnes coutParPart et prixVente de TOUTES les recettes de cet utilisateur
                val recettes = db.recetteDao().getRecettesParUtilisateur(updatedUser.id).first()
                val ingredientsMap = db.ingredientDao().getIngredientsParUtilisateur(updatedUser.id).first().associateBy { it.id }

                for (recette in recettes) {
                    val lignes = db.ligneRecetteDao().parRecette(recette.id).first()
                    val (nouveauCout, nouveauPrix) = computeDynamicCostAndPrice(recette, lignes, ingredientsMap, updatedUser)

                    db.recetteDao().modifier(
                        recette.copy(
                            coutParPart = nouveauCout,
                            prixVente = nouveauPrix
                        )
                    )
                }

                currentUser = updatedUser
                afficherUtilisateur(updatedUser)
                Toast.makeText(this@ProfilActivity, "Profil et prix des recettes mis à jour !", Toast.LENGTH_SHORT).show()
            }
        }

        btnChangePassword?.setOnClickListener {
            if (currentUserId == SessionManager.ID_UTILISATEUR_INVITE) {
                Toast.makeText(this, "Action indisponible en mode essai", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentPwd = editCurrentPassword?.text.toString()
            val newPwd = editNewPassword?.text.toString() ?: ""

            val user = currentUser ?: return@setOnClickListener

            if (!PasswordHasher.verify(currentPwd, user.motDePasseHash)) {
                Toast.makeText(this, "Mot de passe actuel incorrect", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newPwd.length < 6) {
                Toast.makeText(this, "Nouveau mot de passe : 6 caractères minimum", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val db = HesbaDatabase.getInstance(this@ProfilActivity)
                val newHash = PasswordHasher.hash(newPwd)
                db.utilisateurDao().modifierMotDePasse(currentUserId, newHash)
                Toast.makeText(this@ProfilActivity, "Mot de passe modifié avec succès !", Toast.LENGTH_SHORT).show()

                editCurrentPassword?.text?.clear()
                editNewPassword?.text?.clear()
            }
        }

        if (currentUserId == SessionManager.ID_UTILISATEUR_INVITE) {
            afficherModeInvite()
        } else {
            val db = HesbaDatabase.getInstance(this)
            lifecycleScope.launch {
                db.utilisateurDao().parId(currentUserId).collect { user ->
                    if (user != null) {
                        currentUser = user
                        afficherUtilisateur(user)
                    } else {
                        afficherModeInvite()
                    }
                }
            }
        }
    }

    // Calcule le coût complet par part et le prix de vente conseillé selon les nouvelles données du profil
    private fun computeDynamicCostAndPrice(
        recette: Recette,
        lignes: List<LigneRecette>,
        ingredientsById: Map<Long, Ingredient>,
        user: Utilisateur
    ): Pair<Double, Double> {
        val coutIngredientsBatch = lignes.sumOf { ligne ->
            val ing = ingredientsById[ligne.ingredientId]
            if (ing != null) {
                val prixUnitaire = CostCalculator.pricePerUnit(ing.prixPaye, ing.quantiteEmballage)
                CostCalculator.lineCost(ligne.quantite, prixUnitaire)
            } else 0.0
        }

        val activite = user.activite
        val prepTimeMin = if (recette.tempsPreparationMinutes > 0) recette.tempsPreparationMinutes else 30
        val tempsHeures = prepTimeMin / 60.0

        val coutMainOeuvre = when (activite) {
            "avec_employe" -> {
                val nbEmp = if (user.nombreEmployes > 0) user.nombreEmployes else 1
                val salaire = if (user.salaireMoyenEmploye > 0.0) user.salaireMoyenEmploye else 1200.0
                CostCalculator.laborCost(nbEmp * salaire, 160.0, tempsHeures)
            }
            "sans_employe" -> CostCalculator.laborCost(1500.0, 160.0, tempsHeures)
            else -> 0.0
        }

        val rent = user.loyerMensuel
        val volumeMensuel = if (user.volumeMensuel > 0) user.volumeMensuel.toDouble() else 500.0

        val chargesFixesPart = when (activite) {
            "sans_employe", "avec_employe" -> {
                if (rent > 0.0 && volumeMensuel > 0.0) {
                    CostCalculator.fixedCostPerUnit(rent, volumeMensuel)
                } else 0.0
            }
            else -> 0.0
        }

        val safePieces = if (recette.nombreParts > 0) recette.nombreParts.toDouble() else 1.0
        val totalBatch = coutIngredientsBatch + coutMainOeuvre
        val coutParPart = CostCalculator.costPerPiece(totalBatch, safePieces) + chargesFixesPart

        val margeDecimale = recette.margeVoulue / 100.0
        val prixVente = CostCalculator.sellingPriceHT(coutParPart, margeDecimale)

        return Pair(coutParPart, prixVente)
    }

    private fun afficherUtilisateur(user: Utilisateur) {
        findViewById<View>(R.id.cardProfile)?.visibility = View.VISIBLE
        findViewById<View>(R.id.txtNoProfile)?.visibility = View.GONE
        findViewById<Button>(R.id.btnCreateAccount)?.visibility = View.GONE
        findViewById<Button>(R.id.btnLogout)?.text = "Se déconnecter"

        findViewById<TextView>(R.id.txtInitial)?.text =
            user.prenom.trim().take(1).uppercase().ifEmpty { "?" }
        findViewById<TextView>(R.id.txtFirstName)?.text = user.prenom
        findViewById<TextView>(R.id.txtEmail)?.text = user.email

        val activiteLabel = when (user.activite) {
            "sans_employe" -> "Local sans employé (Loyer : ${user.loyerMensuel} dt · Vol : ${user.volumeMensuel} parts/mois)"
            "avec_employe" -> "Local avec employés (${user.nombreEmployes} emp. · Loyer : ${user.loyerMensuel} dt · Vol : ${user.volumeMensuel} parts/mois)"
            else -> "À domicile"
        }
        findViewById<TextView>(R.id.txtActivity)?.text = activiteLabel

        val rgActivite = findViewById<RadioGroup>(R.id.rgActivity)
        val layoutRentField = findViewById<LinearLayout>(R.id.layoutRentField)
        val editRent = findViewById<EditText>(R.id.editRent)
        val editMonthlyVolume = findViewById<EditText>(R.id.editMonthlyVolume)
        val layoutEmployeeFields = findViewById<LinearLayout>(R.id.layoutEmployeeFields)
        val editEmpCount = findViewById<EditText>(R.id.editEmpCount)
        val editEmpSalary = findViewById<EditText>(R.id.editEmpSalary)

        when (user.activite) {
            "sans_employe" -> {
                rgActivite?.check(R.id.rbLocalSolo)
                layoutRentField?.visibility = View.VISIBLE
                layoutEmployeeFields?.visibility = View.GONE
                editRent?.setText(if (user.loyerMensuel > 0.0) user.loyerMensuel.toString() else "900")
                editMonthlyVolume?.setText(if (user.volumeMensuel > 0) user.volumeMensuel.toString() else "500")
            }
            "avec_employe" -> {
                rgActivite?.check(R.id.rbLocalStaff)
                layoutRentField?.visibility = View.VISIBLE
                layoutEmployeeFields?.visibility = View.VISIBLE
                editRent?.setText(if (user.loyerMensuel > 0.0) user.loyerMensuel.toString() else "900")
                editMonthlyVolume?.setText(if (user.volumeMensuel > 0) user.volumeMensuel.toString() else "500")
                editEmpCount?.setText(if (user.nombreEmployes > 0) user.nombreEmployes.toString() else "1")
                editEmpSalary?.setText(if (user.salaireMoyenEmploye > 0.0) user.salaireMoyenEmploye.toString() else "1200")
            }
            else -> {
                rgActivite?.check(R.id.rbHome)
                layoutRentField?.visibility = View.GONE
                layoutEmployeeFields?.visibility = View.GONE
            }
        }
    }

    private fun afficherModeInvite() {
        findViewById<View>(R.id.cardProfile)?.visibility = View.VISIBLE
        findViewById<View>(R.id.txtNoProfile)?.visibility = View.VISIBLE
        findViewById<Button>(R.id.btnCreateAccount)?.visibility = View.VISIBLE
        findViewById<Button>(R.id.btnLogout)?.text = "Quitter le mode essai"

        findViewById<TextView>(R.id.txtInitial)?.text = "?"
        findViewById<TextView>(R.id.txtFirstName)?.text = "Invité"
        findViewById<TextView>(R.id.txtEmail)?.text = "Mode essai (non connecté)"
        findViewById<TextView>(R.id.txtActivity)?.text = "Non renseignée"
    }
}
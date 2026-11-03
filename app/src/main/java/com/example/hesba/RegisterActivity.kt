package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hesba.auth.AuthRepository
import com.example.hesba.auth.AuthResult
import com.example.hesba.auth.SessionManager
import com.example.hesba.data.HesbaDatabase
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        val repo = AuthRepository(HesbaDatabase.getInstance(this).utilisateurDao())
        val session = SessionManager(this)

        findViewById<View>(R.id.btnBack)?.setOnClickListener {
            finish()
        }

        val etPrenom = findViewById<EditText>(R.id.editName)
        val etEmail = findViewById<EditText>(R.id.editEmail)
        val etPassword = findViewById<EditText>(R.id.editPassword)
        val rgActivite = findViewById<RadioGroup>(R.id.rgActivity)

        // Champs dynamiques pour le Loyer et le Volume mensuel
        val layoutRentField = findViewById<LinearLayout>(R.id.layoutRentField)
        val editRent = findViewById<EditText>(R.id.editRent)
        val editMonthlyVolume = findViewById<EditText>(R.id.editMonthlyVolume)

        // Champs dynamiques pour les Employés
        val layoutEmployeeFields = findViewById<LinearLayout>(R.id.layoutEmployeeFields)
        val editEmpCount = findViewById<EditText>(R.id.editEmpCount)
        val editEmpSalary = findViewById<EditText>(R.id.editEmpSalary)

        val cbPolitique = findViewById<CheckBox>(R.id.checkPrivacy)
        val btnRegister = findViewById<Button>(R.id.btnRegister)

        findViewById<TextView>(R.id.txtLoginLink)?.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        // Afficher ou masquer les sections dynamiques (Loyer/Volume et Employés) selon l'activité
        rgActivite.setOnCheckedChangeListener { _, checkedId ->
            val isLocal = (checkedId == R.id.rbLocalSolo || checkedId == R.id.rbLocalStaff)
            layoutRentField.visibility = if (isLocal) View.VISIBLE else View.GONE
            layoutEmployeeFields.visibility = if (checkedId == R.id.rbLocalStaff) View.VISIBLE else View.GONE
        }

        btnRegister.setOnClickListener {
            val prenom = etPrenom.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString()

            val checkedId = rgActivite.checkedRadioButtonId
            if (checkedId == -1) {
                Toast.makeText(this, "Choisis ton activité", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Clés normalisées pour la base de données
            val activiteCode = when (checkedId) {
                R.id.rbHome -> "domicile"
                R.id.rbLocalSolo -> "sans_employe"
                R.id.rbLocalStaff -> "avec_employe"
                else -> "domicile"
            }

            val rentAmount = if (activiteCode != "domicile") {
                editRent.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            } else 0.0

            val volumeMensuel = if (activiteCode != "domicile") {
                editMonthlyVolume.text.toString().toIntOrNull() ?: 300
            } else 300

            val nbEmployes = if (activiteCode == "avec_employe") {
                editEmpCount.text.toString().toIntOrNull() ?: 0
            } else 0

            val salaireEmploye = if (activiteCode == "avec_employe") {
                editEmpSalary.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
            } else 0.0

            if (activiteCode != "domicile" && rentAmount <= 0.0) {
                Toast.makeText(this, "Indique le montant du loyer mensuel", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (activiteCode != "domicile" && volumeMensuel <= 0) {
                Toast.makeText(this, "Indique une estimation du volume mensuel", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (activiteCode == "avec_employe" && nbEmployes <= 0) {
                Toast.makeText(this, "Indique le nombre d'employés", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val r = repo.inscrire(
                    prenom = prenom,
                    email = email,
                    motDePasse = password,
                    activite = activiteCode,
                    loyerMensuel = rentAmount,
                    volumeMensuel = volumeMensuel,
                    nombreEmployes = nbEmployes,
                    salaireMoyenEmploye = salaireEmploye,
                    politiqueAcceptee = cbPolitique.isChecked
                )

                when (r) {
                    is AuthResult.Success -> {
                        session.saveUser(r.userId)
                        startActivity(Intent(this@RegisterActivity, MainActivity::class.java))
                        finishAffinity()
                    }
                    is AuthResult.Error -> {
                        Toast.makeText(this@RegisterActivity, r.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
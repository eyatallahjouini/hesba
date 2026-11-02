package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hesba.auth.AuthRepository
import com.example.hesba.auth.AuthResult
import com.example.hesba.auth.SessionManager
import com.example.hesba.data.HesbaDatabase
import kotlinx.coroutines.launch
import android.widget.RadioButton
import android.widget.RadioGroup
import android.view.View

class RegisterActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)
        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
        }

        val repo = AuthRepository(HesbaDatabase.getInstance(this).utilisateurDao())
        val session = SessionManager(this)

        val etPrenom = findViewById<EditText>(R.id.editName)          // <- ID
        val etEmail = findViewById<EditText>(R.id.editEmail)            // <- ID
        val etPassword = findViewById<EditText>(R.id.editPassword)      // <- ID
        val rgActivite = findViewById<RadioGroup>(R.id.rgActivity)   // <- ID (ou Spinner)     // <- ID (ou Spinner)
        val cbPolitique = findViewById<CheckBox>(R.id.checkPrivacy)   // <- ID
        val btnRegister = findViewById<Button>(R.id.btnRegister)        // <- ID

        btnRegister.setOnClickListener {
            if (rgActivite.checkedRadioButtonId == -1) {
                Toast.makeText(this, "Choisis ton activité", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val r = repo.inscrire(
                    prenom = etPrenom.text.toString(),
                    email = etEmail.text.toString(),
                    motDePasse = etPassword.text.toString(),
                    activite = rgActivite.findViewById<RadioButton>(rgActivite.checkedRadioButtonId)
                        ?.text?.toString() ?: "",
                    politiqueAcceptee = cbPolitique.isChecked
                )
                when (r) {
                    is AuthResult.Success -> {
                        session.saveUser(r.userId)
                        startActivity(Intent(this@RegisterActivity, MainActivity::class.java))
                        finishAffinity()
                    }
                    is AuthResult.Error ->
                        Toast.makeText(this@RegisterActivity, r.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
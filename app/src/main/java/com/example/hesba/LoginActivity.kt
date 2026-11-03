package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.hesba.auth.AuthRepository
import com.example.hesba.auth.AuthResult
import com.example.hesba.auth.SessionManager
import com.example.hesba.data.HesbaDatabase
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val session = SessionManager(this)

        // Si déjà connecté (avec un compte ou en mode invité déjà initialisé), rediriger directement
        if (session.isLoggedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val repo = AuthRepository(HesbaDatabase.getInstance(this).utilisateurDao())

        val etEmail = findViewById<EditText>(R.id.editEmail)
        val etPassword = findViewById<EditText>(R.id.editPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnNoAccount = findViewById<Button>(R.id.btnNoAccount)

        // 1. Connexion avec Compte Utilisateur
        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Veuillez remplir tous les champs", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                when (val r = repo.login(email, password)) {
                    is AuthResult.Success -> {
                        // Enregistre l'ID réel de l'utilisateur dans SharedPreferences
                        session.saveUser(r.userId)
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        finish()
                    }
                    is AuthResult.Error ->
                        Toast.makeText(this@LoginActivity, r.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 2. Mode Essai sans Compte (Invité)
        btnNoAccount.setOnClickListener {
            // Sauvegarde de l'ID invité (-1L) dans SessionManager
            session.saveUser(SessionManager.ID_UTILISATEUR_INVITE)

            Toast.makeText(this, "Mode essai activé", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        // 3. Navigation vers Inscription
        findViewById<TextView>(R.id.txtCreateAccount).setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        // 4. Mot de passe oublié
        findViewById<TextView>(R.id.txtForgot).setOnClickListener {
            Toast.makeText(this, "Fonctionnalité bientôt disponible", Toast.LENGTH_SHORT).show()
        }
    }
}
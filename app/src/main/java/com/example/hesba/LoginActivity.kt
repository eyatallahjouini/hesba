package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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
import android.widget.TextView
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
        if (session.isLoggedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val repo = AuthRepository(HesbaDatabase.getInstance(this).utilisateurDao())

        val etEmail = findViewById<EditText>(R.id.editEmail)
        val etPassword = findViewById<EditText>(R.id.editPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)

        btnLogin.setOnClickListener {
            lifecycleScope.launch {
                when (val r = repo.login(etEmail.text.toString(), etPassword.text.toString())) {
                    is AuthResult.Success -> {
                        session.saveUser(r.userId)
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        finish()
                    }
                    is AuthResult.Error ->
                        Toast.makeText(this@LoginActivity, r.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
        val btnNoAccount = findViewById<Button>(R.id.btnNoAccount)
        btnNoAccount.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        findViewById<TextView>(R.id.txtCreateAccount).setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        findViewById<TextView>(R.id.txtForgot).setOnClickListener {
            Toast.makeText(this, "Fonctionnalité bientôt disponible", Toast.LENGTH_SHORT).show()
        }
    }
}
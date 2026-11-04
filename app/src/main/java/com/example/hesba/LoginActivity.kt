package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.hesba.auth.AuthRepository
import com.example.hesba.auth.AuthResult
import com.example.hesba.auth.SessionManager
import com.example.hesba.databinding.ActivityLoginBinding
import com.example.hesba.ui.BaseActivity
import kotlinx.coroutines.launch

/** Connexion à un compte local, ou essai sans compte (données gardées sur le téléphone sous l'identifiant -1). */
class LoginActivity : BaseActivity() {

    override val requiresSession = false
    private lateinit var b: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (session.isLoggedIn()) {
            goHome()
            return
        }
        b = ActivityLoginBinding.inflate(layoutInflater)
        setScreen(b.root)

        val auth = AuthRepository(db.utilisateurDao())
        b.editEmail.doAfterTextChanged { b.tilEmail.error = null }
        b.editPassword.doAfterTextChanged { b.tilPassword.error = null }
        b.editPassword.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { b.btnLogin.performClick(); true } else false
        }

        b.btnLogin.setOnClickListener {
            b.btnLogin.isEnabled = false
            lifecycleScope.launch {
                when (val r = auth.login(b.editEmail.text.toString(), b.editPassword.text.toString())) {
                    is AuthResult.Success -> { session.saveUser(r.userId); goHome() }
                    is AuthResult.Error -> {
                        b.btnLogin.isEnabled = true
                        val field = if (r.field == AuthResult.Field.EMAIL) b.tilEmail else b.tilPassword
                        field.error = getString(r.message)
                    }
                }
            }
        }
        b.btnCreateAccount.setOnClickListener { startActivity(Intent(this, RegisterActivity::class.java)) }
        b.btnNoAccount.setOnClickListener {
            session.saveUser(SessionManager.ID_UTILISATEUR_INVITE)
            goHome()
        }
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}

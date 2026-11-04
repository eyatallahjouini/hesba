package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.hesba.auth.AuthRepository
import com.example.hesba.auth.AuthResult
import com.example.hesba.auth.SessionManager
import com.example.hesba.data.HesbaRepository
import com.example.hesba.databinding.ActivityRegisterBinding
import com.example.hesba.ui.BaseActivity
import kotlinx.coroutines.launch

/**
 * Création d'un compte local. Les charges (loyer, employés…) se règlent ensuite dans le Profil.
 * Si l'utilisateur essayait l'appli sans compte, ses données sont rattachées au nouveau compte.
 */
class RegisterActivity : BaseActivity() {

    override val requiresSession = false
    private lateinit var b: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRegisterBinding.inflate(layoutInflater)
        setScreen(b.root)
        setTitleText("")

        val auth = AuthRepository(db.utilisateurDao())
        val guestRepo = HesbaRepository(db, SessionManager.ID_UTILISATEUR_INVITE)
        lifecycleScope.launch {
            if (guestRepo.guestDataCount() > 0) b.txtGuestTransfer.visibility = View.VISIBLE
        }

        b.editName.doAfterTextChanged { b.tilName.error = null }
        b.editEmail.doAfterTextChanged { b.tilEmail.error = null }
        b.editPassword.doAfterTextChanged { b.tilPassword.error = null }
        b.checkPrivacy.setOnCheckedChangeListener { _, _ -> b.txtPrivacyError.visibility = View.GONE }

        b.btnRegister.setOnClickListener {
            val activity = when (b.groupActivity.checkedChipId) {
                R.id.chipSolo -> "sans_employe"
                R.id.chipStaff -> "avec_employe"
                else -> "domicile"
            }
            b.btnRegister.isEnabled = false
            lifecycleScope.launch {
                val r = auth.inscrire(
                    prenom = b.editName.text.toString(),
                    nomCommerce = b.editBusiness.text.toString(),
                    email = b.editEmail.text.toString(),
                    motDePasse = b.editPassword.text.toString(),
                    activite = activity,
                    politiqueAcceptee = b.checkPrivacy.isChecked
                )
                when (r) {
                    is AuthResult.Success -> {
                        val moved = guestRepo.transferGuestDataTo(r.userId)
                        session.saveUser(r.userId)
                        val intent = Intent(this@RegisterActivity, HomeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            .putExtra(HomeActivity.EXTRA_WELCOME, true)
                            .putExtra(HomeActivity.EXTRA_MOVED, moved)
                        startActivity(intent)
                        finish()
                    }
                    is AuthResult.Error -> {
                        b.btnRegister.isEnabled = true
                        val message = getString(r.message)
                        when (r.field) {
                            AuthResult.Field.NAME -> b.tilName.error = message
                            AuthResult.Field.EMAIL -> b.tilEmail.error = message
                            AuthResult.Field.PASSWORD -> b.tilPassword.error = message
                            AuthResult.Field.PRIVACY -> b.txtPrivacyError.visibility = View.VISIBLE
                            AuthResult.Field.NONE -> snack(message)
                        }
                    }
                }
            }
        }
    }
}

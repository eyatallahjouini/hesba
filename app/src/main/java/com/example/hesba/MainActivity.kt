package com.example.hesba

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.hesba.auth.SessionManager
import com.example.hesba.ui.Settings

/**
 * Point d'entrée : Accueil si une session existe ; sinon le premier lancement (une seule fois),
 * puis la connexion. (L'ancien écran Bilan est retiré.)
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = when {
            SessionManager(this).isLoggedIn() -> HomeActivity::class.java
            !Settings.onboardingDone(this) -> OnboardingActivity::class.java
            else -> LoginActivity::class.java
        }
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        finish()
        BottomNav.noTransition(this)
    }
}

package com.example.hesba

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.Utilisateur
import kotlinx.coroutines.launch

// Onglet « Profil » : prénom, email et activité lus dans la table utilisateur
class ProfilActivity : AppCompatActivity() {

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

        val db = HesbaDatabase.getInstance(this)
        lifecycleScope.launch {
            // pas encore de session : utilisateurCourant() renvoie le premier utilisateur enregistré
            db.utilisateurDao().utilisateurCourant().collect { show(it) }
        }
    }

    private fun show(user: Utilisateur?) {
        val card = findViewById<View>(R.id.cardProfile)
        val empty = findViewById<View>(R.id.txtNoProfile)
        if (user == null) {
            card.visibility = View.GONE
            empty.visibility = View.VISIBLE
            return
        }
        card.visibility = View.VISIBLE
        empty.visibility = View.GONE
        findViewById<TextView>(R.id.txtInitial).text = user.prenom.trim().take(1).uppercase().ifEmpty { "?" }
        findViewById<TextView>(R.id.txtFirstName).text = user.prenom
        findViewById<TextView>(R.id.txtEmail).text = user.email
        findViewById<TextView>(R.id.txtActivity).text = user.activite
    }
}

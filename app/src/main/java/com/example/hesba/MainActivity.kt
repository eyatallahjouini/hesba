package com.example.hesba

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

// Point d'entrée après la connexion. L'écran Bilan a été retiré :
// on ouvre directement l'onglet Recettes.
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, RecettesActivity::class.java))
        finish()
    }
}

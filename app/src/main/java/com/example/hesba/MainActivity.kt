package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_dashboard)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // barre du bas de l'ancien écran Bilan : vers les nouveaux onglets
        findViewById<View>(R.id.navRecettes).setOnClickListener {
            startActivity(Intent(this, RecettesActivity::class.java))
        }
        findViewById<View>(R.id.navIngredients).setOnClickListener {
            startActivity(Intent(this, IngredientsActivity::class.java))
        }
    }
}
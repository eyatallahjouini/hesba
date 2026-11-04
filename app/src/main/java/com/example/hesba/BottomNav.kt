package com.example.hesba

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Barre du bas partagée (layout/view_bottom_nav.xml) : Accueil, Mes recettes, Ingrédients, Profil.
 * Changer d'onglet remplace l'écran (pas de pile d'onglets) ; « retour » depuis un onglet ramène à l'Accueil,
 * puis quitte l'appli depuis l'Accueil.
 */
object BottomNav {

    enum class Tab(val itemId: Int, val target: Class<out Activity>) {
        HOME(R.id.tab_home, HomeActivity::class.java),
        RECIPES(R.id.tab_recipes, RecettesActivity::class.java),
        INGREDIENTS(R.id.tab_ingredients, IngredientsActivity::class.java),
        PROFILE(R.id.tab_profile, ProfilActivity::class.java)
    }

    fun setup(activity: ComponentActivity, current: Tab) {
        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.selectedItemId = current.itemId
        nav.setOnItemSelectedListener { item ->
            val tab = Tab.entries.first { it.itemId == item.itemId }
            if (tab != current) open(activity, tab)
            true
        }
        nav.setOnItemReselectedListener { }
        if (current != Tab.HOME) {
            activity.onBackPressedDispatcher.addCallback(activity) { open(activity, Tab.HOME) }
        }
    }

    fun open(activity: Activity, tab: Tab) {
        activity.startActivity(Intent(activity, tab.target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        activity.finish()
        noTransition(activity)
    }

    @Suppress("DEPRECATION")
    fun noTransition(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            activity.overridePendingTransition(0, 0)
        }
    }
}

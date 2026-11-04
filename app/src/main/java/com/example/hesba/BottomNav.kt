package com.example.hesba

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import com.example.hesba.ui.widget.NavBar

/**
 * Barre du bas partagée (layout/view_bottom_nav.xml → [NavBar]) : Accueil, Mes recettes, Ingrédients, Profil.
 * Changer d'onglet remplace l'écran (pas de pile d'onglets) ; « retour » depuis un onglet ramène à l'Accueil,
 * puis quitte l'appli depuis l'Accueil. L'onglet de départ est transmis pour faire glisser l'indicateur.
 */
object BottomNav {

    private const val EXTRA_FROM = "hb_nav_from"

    enum class Tab(val icon: Int, val label: Int, val target: Class<out Activity>) {
        HOME(R.drawable.ic_home, R.string.tab_home, HomeActivity::class.java),
        RECIPES(R.drawable.ic_recipes, R.string.tab_recipes, RecettesActivity::class.java),
        INGREDIENTS(R.drawable.ic_ingredients, R.string.tab_ingredients, IngredientsActivity::class.java),
        PROFILE(R.drawable.ic_profile, R.string.tab_profile, ProfilActivity::class.java)
    }

    fun setup(activity: ComponentActivity, current: Tab) {
        val nav = activity.findViewById<NavBar>(R.id.bottomNav)
        nav.setItems(Tab.entries.map { NavBar.Item(it.icon, it.label) })
        val from = activity.intent.getIntExtra(EXTRA_FROM, -1).takeIf { it >= 0 }
        activity.intent.removeExtra(EXTRA_FROM)   // pas de nouveau glissement après une rotation
        nav.select(current.ordinal, from)
        nav.onSelect = { i -> open(activity, Tab.entries[i], current) }
        if (current != Tab.HOME) {
            activity.onBackPressedDispatcher.addCallback(activity) { open(activity, Tab.HOME, current) }
        }
    }

    fun open(activity: Activity, tab: Tab, from: Tab? = null) {
        val intent = Intent(activity, tab.target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (from != null) intent.putExtra(EXTRA_FROM, from.ordinal)
        activity.startActivity(intent)
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

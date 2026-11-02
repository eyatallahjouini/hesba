package com.example.hesba

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.TextView

// Barre du bas partagée (layout/view_bottom_nav.xml) : Recettes, Ingrédients, Profil
object BottomNav {

    enum class Tab { RECETTES, INGREDIENTS, PROFIL }

    fun setup(activity: Activity, current: Tab) {
        bind(activity, current, Tab.RECETTES, R.id.navRecettes, R.id.navRecettesLabel, RecettesActivity::class.java)
        bind(activity, current, Tab.INGREDIENTS, R.id.navIngredients, R.id.navIngredientsLabel, IngredientsActivity::class.java)
        bind(activity, current, Tab.PROFIL, R.id.navProfil, R.id.navProfilLabel, ProfilActivity::class.java)
    }

    private fun bind(
        activity: Activity,
        current: Tab,
        tab: Tab,
        itemId: Int,
        labelId: Int,
        target: Class<out Activity>
    ) {
        val item = activity.findViewById<View>(itemId)
        val label = activity.findViewById<TextView>(labelId)
        if (tab == current) {
            // onglet actif : fond safran + texte en gras, pas de clic
            item.setBackgroundResource(R.drawable.bg_nav_active)
            label.setTypeface(null, Typeface.BOLD)
            item.setOnClickListener(null)
        } else {
            item.background = null
            label.setTypeface(null, Typeface.NORMAL)
            item.setOnClickListener {
                // on remplace la page actuelle : le bouton retour quitte l'appli au lieu d'empiler les onglets
                val intent = Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                activity.startActivity(intent)
                activity.finish()
            }
        }
    }
}

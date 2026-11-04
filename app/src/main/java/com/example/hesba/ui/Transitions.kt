package com.example.hesba.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.Window
import androidx.core.content.ContextCompat
import com.example.hesba.R
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback

/**
 * Transformation de conteneur (Material) entre deux écrans :
 * - le bouton carré « + » s'ouvre en formulaire d'ajout ;
 * - une ligne de liste (recette, ingrédient) s'ouvre en fiche.
 * Si les animations sont désactivées sur le téléphone, l'écran s'ouvre normalement.
 */
object Transitions {
    private const val EXTRA = "hb_container_transform"
    private const val TARGET_NAME = "hb_container"

    /** Dans [BaseActivity.onCreate], avant super.onCreate : tous les écrans peuvent être source et cible. */
    fun install(activity: Activity) {
        activity.window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        activity.setExitSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        activity.window.sharedElementsUseOverlay = false
        if (!activity.intent.getBooleanExtra(EXTRA, false)) return
        activity.setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        activity.window.sharedElementEnterTransition = transform(activity, Motion.LONG)
        activity.window.sharedElementReturnTransition = transform(activity, Motion.MEDIUM)
    }

    /** Après setContentView : la racine de l'écran cible porte le nom partagé. */
    fun markTarget(activity: Activity) {
        if (activity.intent.getBooleanExtra(EXTRA, false)) {
            activity.findViewById<View>(android.R.id.content).transitionName = TARGET_NAME
        }
    }

    /** Ouvre [intent] en faisant grandir [from] jusqu'au nouvel écran. */
    fun open(activity: Activity, from: View, intent: Intent, sourceName: String) {
        if (!Motion.enabled(activity)) { activity.startActivity(intent); return }
        from.transitionName = sourceName
        intent.putExtra(EXTRA, true)
        val options = ActivityOptions.makeSceneTransitionAnimation(activity, from, TARGET_NAME)
        activity.startActivity(intent, options.toBundle())
    }

    private fun transform(activity: Activity, ms: Long) = MaterialContainerTransform().apply {
        addTarget(android.R.id.content)
        duration = ms
        interpolator = Motion.standard
        scrimColor = Color.TRANSPARENT
        containerColor = ContextCompat.getColor(activity, R.color.hb_bg)
        fadeMode = MaterialContainerTransform.FADE_MODE_THROUGH
    }
}

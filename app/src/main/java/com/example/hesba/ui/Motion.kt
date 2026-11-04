package com.example.hesba.ui

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.RecyclerView

/**
 * Règles de mouvement HESBA : court, naturel, jamais décoratif.
 * - Si l'utilisateur a désactivé les animations (Options pour les développeurs ou Accessibilité
 *   « Supprimer les animations »), [enabled] renvoie false : chaque écran affiche directement l'état final.
 * - Les listes n'animent leur entrée qu'au premier chargement ([enterOnce]), jamais pendant le défilement.
 */
object Motion {
    const val SHORT = 160L
    const val MEDIUM = 280L
    const val LONG = 420L
    const val STAGGER = 40L

    /** Courbe « décélération marquée » (Material emphasized decelerate). */
    val decelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val standard = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun enabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) return false
        val scale = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        } catch (_: Exception) { 1f }
        return scale > 0f
    }

    /** Apparition douce : légère montée + fondu. */
    fun riseIn(view: View, delay: Long = 0, distanceDp: Float = 12f) {
        if (!enabled(view.context)) { view.alpha = 1f; view.translationY = 0f; return }
        view.alpha = 0f
        view.translationY = distanceDp * view.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(MEDIUM).setInterpolator(decelerate).start()
    }

    /**
     * Entrée en cascade des lignes visibles, une seule fois par écran : appelée après le premier
     * submitList non vide. Les lignes qui arrivent ensuite par défilement ne sont pas animées.
     */
    fun enterOnce(list: RecyclerView, state: Flag) {
        if (state.done) return
        state.done = true
        if (!enabled(list.context)) return
        list.doOnNextLayout {
            val count = minOf(list.childCount, 12)
            for (i in 0 until count) riseIn(list.getChildAt(i), i * STAGGER, 10f)
        }
    }

    class Flag(var done: Boolean = false)

    /** Petit retour haptique (curseur, sélection). Respecte le réglage système du téléphone. */
    fun tick(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    fun confirm(view: View) {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private inline fun View.doOnNextLayout(crossinline action: () -> Unit) {
        addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                v.removeOnLayoutChangeListener(this)
                action()
            }
        })
        if (this is ViewGroup) requestLayout()
    }
}

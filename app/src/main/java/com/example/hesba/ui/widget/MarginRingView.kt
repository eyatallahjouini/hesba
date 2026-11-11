package com.example.hesba.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.hesba.R
import com.example.hesba.ui.Motion
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Anneau de la marge moyenne (Accueil) : piste, arc rempli de 0 à 100 %, petit trait = objectif.
 * L'arc se remplit en douceur, sauf si les animations sont désactivées sur le téléphone.
 * Décoratif pour TalkBack : la carte annonce déjà la marge en texte.
 */
class MarginRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val stroke = 8f * density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke
        color = ContextCompat.getColor(context, R.color.hb_bento_ring_track)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.hb_bento_ring)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.hb_bento_ring_tick)
    }
    private val oval = RectF()
    private var shown = 0f
    private var target: Float? = null
    private var animator: ValueAnimator? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** [percent] et [targetPercent] de 0 à 100 ; null = rien à montrer. */
    fun set(percent: Float?, targetPercent: Float?, animate: Boolean) {
        target = targetPercent?.coerceIn(0f, 100f)
        val end = (percent ?: 0f).coerceIn(0f, 100f)
        animator?.cancel()
        if (animate && Motion.enabled(context) && end != shown) {
            animator = ValueAnimator.ofFloat(shown, end).apply {
                duration = 900
                interpolator = Motion.decelerate
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            shown = end
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val r = (size - stroke) / 2f
        val cx = width / 2f
        val cy = height / 2f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawOval(oval, track)
        if (shown > 0f) canvas.drawArc(oval, -90f, 360f * shown / 100f, false, arc)
        target?.let { t ->
            val a = Math.toRadians((360f * t / 100f - 90f).toDouble())
            val inner = r - stroke / 2f - 2f * density
            val outer = r + stroke / 2f + 2f * density
            canvas.drawLine(
                cx + inner * cos(a).toFloat(), cy + inner * sin(a).toFloat(),
                cx + outer * cos(a).toFloat(), cy + outer * sin(a).toFloat(), tick
            )
        }
    }
}

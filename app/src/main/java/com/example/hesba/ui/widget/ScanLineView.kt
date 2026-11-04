package com.example.hesba.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.hesba.R
import com.example.hesba.ui.Motion

/**
 * Ligne de lecture posée sur l'image de la facture. Elle ne bouge QUE pendant que l'OCR travaille
 * vraiment : l'écran appelle [start] quand la lecture commence et [stop] dès qu'elle se termine
 * (succès ou échec). Aucune fausse progression : pas de pourcentage, pas de durée inventée.
 * Si les animations sont désactivées, la vue reste invisible (l'indicateur de chargement suffit).
 */
class ScanLineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.hb_accent)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = 2.5f * density }
    private val trail = Paint()
    private var y = 0f
    private var down = true
    private var animator: ValueAnimator? = null
    private val area = android.graphics.RectF()

    /** Zone de l'image affichée (coordonnées de cette vue) : la ligne ne sort pas de la facture. */
    fun limitTo(rect: android.graphics.RectF) { area.set(rect); invalidate() }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    val isRunning: Boolean get() = animator?.isRunning == true

    fun start() {
        if (isRunning) return
        if (!Motion.enabled(context)) { visibility = INVISIBLE; return }
        visibility = VISIBLE
        var last = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = Motion.standard
            addUpdateListener {
                val v = it.animatedValue as Float
                down = v >= last
                last = v
                y = v
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        visibility = INVISIBLE
    }

    override fun onDraw(canvas: Canvas) {
        if (area.isEmpty) area.set(0f, 0f, width.toFloat(), height.toFloat())
        val py = area.top + y * area.height()
        // courte traînée derrière la ligne, dans le sens du mouvement
        val len = 36 * density
        val from = if (down) py - len else py + len
        trail.shader = LinearGradient(0f, from, 0f, py, ColorUtils.setAlphaComponent(accent, 0), ColorUtils.setAlphaComponent(accent, 70), Shader.TileMode.CLAMP)
        canvas.save()
        canvas.clipRect(area)
        canvas.drawRect(area.left, minOf(from, py), area.right, maxOf(from, py), trail)
        canvas.restore()
        canvas.drawLine(area.left, py, area.right, py, line)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}

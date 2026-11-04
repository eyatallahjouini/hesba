package com.example.hesba.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.hesba.R

/**
 * Fine barre de marge sous une recette : remplissage = marge réelle (0 à 100 %),
 * petit trait vertical = marge voulue. Vert si la marge atteint l'objectif, rouge sinon,
 * gris si le coût est incomplet ou le prix inconnu. Respecte le sens de lecture (RTL).
 * Décorative pour TalkBack : la ligne parente annonce déjà la marge en texte.
 */
class MarginBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class State { GOOD, BAD, UNKNOWN }

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.hb_line) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.hb_ink) }
    private val rect = RectF()
    private var actual = 0f
    private var target = 0f
    private var state = State.UNKNOWN

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** [actualPercent] peut être null (prix inconnu, coût incomplet) ou négatif (vente à perte). */
    fun set(actualPercent: Float?, targetPercent: Float) {
        target = targetPercent.coerceIn(0f, 100f)
        if (actualPercent == null) {
            actual = 0f; state = State.UNKNOWN
        } else {
            actual = actualPercent.coerceIn(0f, 100f)
            state = if (actualPercent >= targetPercent) State.GOOD else State.BAD
        }
        fill.color = ContextCompat.getColor(context, when (state) {
            State.GOOD -> R.color.hb_good
            State.BAD -> R.color.hb_bad
            State.UNKNOWN -> R.color.hb_line_strong
        })
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), resolveSize((8 * density).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val h = 3f * density
        val top = (height - h) / 2f
        val w = width.toFloat()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        rect.set(0f, top, w, top + h)
        canvas.drawRoundRect(rect, h, h, track)
        if (state != State.UNKNOWN && actual > 0f) {
            val fw = w * actual / 100f
            if (rtl) rect.set(w - fw, top, w, top + h) else rect.set(0f, top, fw, top + h)
            canvas.drawRoundRect(rect, h, h, fill)
        }
        if (target > 0f) {
            val tx = if (rtl) w - w * target / 100f else w * target / 100f
            val tw = 1.5f * density
            canvas.drawRect(tx - tw / 2, 0f, tx + tw / 2, height.toFloat(), tick)
        }
    }
}

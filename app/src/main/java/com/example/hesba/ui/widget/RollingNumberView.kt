package com.example.hesba.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.example.hesba.R
import com.example.hesba.ui.Motion

/**
 * Montant à « chiffres qui roulent » (conseiller de marge) : seuls les chiffres qui changent défilent,
 * l'ancien monte et s'efface, le nouveau arrive du dessous. Le texte affiché ne contient que le nombre
 * (chiffres, séparateurs, signe) : la devise est dans une vue à côté, pour ne jamais découper un mot arabe.
 * Le bloc est aligné sur la fin de la ligne (droite en français, gauche en arabe) et les chiffres sont
 * ancrés sur leur bord droit : les millimes restent immobiles quand le nombre de chiffres change.
 */
class RollingNumberView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var current = ""
    private var previous = ""
    private var progress = 1f
    private var animator: ValueAnimator? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.RollingNumberView)
        val appearance = a.getResourceId(R.styleable.RollingNumberView_android_textAppearance, R.style.TextAppearance_Hesba_Money_Large)
        a.recycle()
        applyAppearance(appearance)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun applyAppearance(style: Int) {
        val t = context.obtainStyledAttributes(style, intArrayOf(android.R.attr.textSize, android.R.attr.textColor, android.R.attr.fontFamily, android.R.attr.letterSpacing))
        paint.textSize = t.getDimension(0, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 28f, resources.displayMetrics))
        paint.color = t.getColorStateList(1)?.defaultColor ?: 0xFF000000.toInt()
        val font = t.getResourceId(2, 0)
        paint.typeface = if (font != 0 && !isInEditMode) ResourcesCompat.getFont(context, font) else Typeface.MONOSPACE
        paint.letterSpacing = t.getFloat(3, 0f)
        t.recycle()
    }

    fun setTextColor(color: Int) { paint.color = color; invalidate() }

    /** Change le nombre ; [animate] = false pour le premier affichage. */
    fun setNumber(text: String, animate: Boolean = true) {
        val clean = text.filterNot { it in BIDI_MARKS }
        if (clean == current) return
        animator?.cancel()
        previous = current
        current = clean
        contentDescription = clean
        if (!animate || previous.isEmpty() || !Motion.enabled(context)) {
            progress = 1f
            requestLayout(); invalidate()
            return
        }
        requestLayout()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.MEDIUM
            interpolator = Motion.decelerate
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = paint.fontMetrics
        val w = (paint.measureText(current) + paddingLeft + paddingRight).toInt()
        val h = (fm.descent - fm.ascent + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val fm = paint.fontMetrics
        val lineH = fm.descent - fm.ascent
        val baseline = paddingTop - fm.ascent
        val textW = paint.measureText(current)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        // bord droit du bloc de chiffres
        val right = if (rtl) paddingLeft + textW else width - paddingRight.toFloat()
        if (progress >= 1f) {
            canvas.drawText(current, right - textW, baseline, paint)
            return
        }
        canvas.save()
        canvas.clipRect(0f, paddingTop.toFloat(), width.toFloat(), paddingTop + lineH)
        val alpha = paint.alpha
        var xNew = right
        var xOld = right
        val n = maxOf(current.length, previous.length)
        for (k in 1..n) {
            val c = current.getOrNull(current.length - k)
            val o = previous.getOrNull(previous.length - k)
            val cw = c?.let { paint.measureText(it.toString()) } ?: 0f
            val ow = o?.let { paint.measureText(it.toString()) } ?: 0f
            xNew -= cw; xOld -= ow
            if (c != null && c == o) {
                canvas.drawText(c.toString(), xNew, baseline, paint)
            } else {
                if (o != null) {
                    paint.alpha = (alpha * (1f - progress)).toInt()
                    canvas.drawText(o.toString(), xOld, baseline - lineH * 0.6f * progress, paint)
                }
                if (c != null) {
                    paint.alpha = (alpha * progress).toInt()
                    canvas.drawText(c.toString(), xNew, baseline + lineH * 0.6f * (1f - progress), paint)
                }
                paint.alpha = alpha
            }
        }
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        val BIDI_MARKS = setOf('‎', '‏', '‪', '‫', '‬', '‭', '‮', '⁦', '⁧', '⁨', '⁩')
    }
}

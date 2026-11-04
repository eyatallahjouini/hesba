package com.example.hesba.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.hesba.R

/**
 * Pointillés de carte de menu / d'ardoise entre un libellé et son prix : « Farine ········ 1,200 ».
 * Les points sont posés sur une grille régulière (pas en partant du bord) pour que deux lignes voisines
 * forment des colonnes alignées. Décoratif : ignoré par TalkBack.
 */
class DotLeaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gap = 6f * density
    private val radius = 1.1f * density

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val a = context.obtainStyledAttributes(attrs, R.styleable.DotLeaderView)
        paint.color = a.getColor(R.styleable.DotLeaderView_dotColor, ContextCompat.getColor(context, R.color.hb_line_strong))
        a.recycle()
    }

    fun setDotColor(color: Int) { paint.color = color; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize((8 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        if (width < gap * 2) return
        val loc = IntArray(2).also { getLocationInWindow(it) }
        val y = height - radius - paddingBottom
        // première position alignée sur la grille de la fenêtre → colonnes régulières d'une ligne à l'autre
        var x = (gap - (loc[0] % gap)) % gap + radius
        val end = width - radius
        while (x <= end) {
            canvas.drawCircle(x, y, radius, paint)
            x += gap
        }
    }
}

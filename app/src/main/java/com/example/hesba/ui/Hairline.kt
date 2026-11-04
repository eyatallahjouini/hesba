package com.example.hesba.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.hesba.R

/** Filet fin entre les lignes d'une liste (pas après la dernière) : la liste reste une liste, pas une pile de cartes. */
class Hairline(context: Context, colorRes: Int = R.color.hb_line) : RecyclerView.ItemDecoration() {
    private val paint = Paint().apply {
        color = ContextCompat.getColor(context, colorRes)
        strokeWidth = context.resources.displayMetrics.density
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val last = (parent.adapter?.itemCount ?: 0) - 1
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (parent.getChildAdapterPosition(child) == last) continue
            val y = child.bottom + child.translationY
            paint.alpha = (255 * child.alpha).toInt()
            c.drawLine(child.left.toFloat(), y, child.right.toFloat(), y, paint)
        }
    }
}

package com.example.hesba.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.example.hesba.R
import com.example.hesba.ui.Motion

/**
 * Barre du bas HESBA : 4 onglets (icône + libellé) et un indicateur safran qui glisse d'un onglet à l'autre.
 * Chaque onglet est un écran à part : le nouvel écran dessine l'indicateur sur l'ancien onglet
 * puis le fait glisser jusqu'au sien ([select] avec from). La barre étant au même endroit sur tous les écrans,
 * le mouvement paraît continu.
 */
class NavBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    data class Item(val icon: Int, val label: Int)

    private val density = resources.displayMetrics.density
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val tabs = mutableListOf<View>()
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.hb_accent) }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.hb_accent_soft) }
    private val linePaint = Paint().apply { color = ContextCompat.getColor(context, R.color.hb_line) }
    private val rect = RectF()
    private var position = 0f
    private var selected = 0
    var onSelect: ((Int) -> Unit)? = null

    init {
        setWillNotDraw(false)
        setBackgroundColor(ContextCompat.getColor(context, R.color.hb_surface))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, (64 * density).toInt()))
    }

    fun setItems(items: List<Item>) {
        row.removeAllViews(); tabs.clear()
        val inflater = LayoutInflater.from(context)
        items.forEachIndexed { i, item ->
            val tab = inflater.inflate(R.layout.view_nav_tab, row, false)
            tab.findViewById<ImageView>(R.id.navIcon).setImageResource(item.icon)
            tab.findViewById<TextView>(R.id.navLabel).setText(item.label)
            tab.contentDescription = context.getString(item.label)
            tab.setOnClickListener { if (i != selected) onSelect?.invoke(i) }
            tab.accessibilityDelegate = object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Tab"
                    info.isSelected = i == selected
                    info.collectionItemInfo = AccessibilityNodeInfo.CollectionItemInfo.obtain(0, 1, i, 1, false, i == selected)
                }
            }
            row.addView(tab, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            tabs += tab
        }
    }

    /** Sélectionne [index] ; si [from] est donné, l'indicateur glisse depuis cet onglet. */
    fun select(index: Int, from: Int? = null) {
        selected = index
        tabs.forEachIndexed { i, t -> style(t, i == index) }
        if (from == null || from == index || !Motion.enabled(context)) {
            position = index.toFloat(); invalidate(); return
        }
        position = from.toFloat()
        android.animation.ValueAnimator.ofFloat(from.toFloat(), index.toFloat()).apply {
            duration = Motion.MEDIUM
            interpolator = Motion.decelerate
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun style(tab: View, on: Boolean) {
        val color = ContextCompat.getColor(context, if (on) R.color.hb_ink else R.color.hb_ink_soft)
        tab.findViewById<ImageView>(R.id.navIcon).setColorFilter(color)
        tab.findViewById<TextView>(R.id.navLabel).apply {
            setTextColor(color)
            typeface = ResourcesCompat.getFont(context, if (on) R.font.hb_sans_semibold else R.font.hb_sans)
        }
        tab.isSelected = on
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), density, linePaint)
        if (tabs.isNotEmpty()) {
            val tabW = width.toFloat() / tabs.size
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val center = if (rtl) width - tabW * (position + 0.5f) else tabW * (position + 0.5f)
            // pastille douce derrière l'icône
            val pw = 56 * density; val ph = 30 * density; val top = 8 * density
            rect.set(center - pw / 2, top, center + pw / 2, top + ph)
            canvas.drawRoundRect(rect, ph / 2, ph / 2, pillPaint)
            // trait safran sur le bord haut de la barre
            val iw = 28 * density
            rect.set(center - iw / 2, 0f, center + iw / 2, 3 * density)
            canvas.drawRoundRect(rect, 1.5f * density, 1.5f * density, indicatorPaint)
        }
        super.dispatchDraw(canvas)
    }
}

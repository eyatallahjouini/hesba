package com.example.hesba.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.example.hesba.R
import com.example.hesba.ui.Motion

/**
 * Le « ticket » HESBA : papier avec bord dentelé (déchiré) en haut et/ou en bas, filet fin autour.
 * [print] le fait sortir comme d'une imprimante de caisse : le papier descend depuis le haut,
 * les premières lignes imprimées apparaissent en premier. Aucune animation si elles sont désactivées.
 */
class ReceiptLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val tooth = 7f * density
    private val toothH = 5f * density
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.hb_paper) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = ContextCompat.getColor(context, R.color.hb_line_strong)
    }
    private val path = Path()
    private var edgeTop = false
    private var edgeBottom = true
    /** Design Minimal : carte plate arrondie (fond surface, filet fin) au lieu du papier de caisse. */
    private var flat = false
    private val radius = resources.getDimension(R.dimen.radius_card)
    private var revealed = 1f
    private var animator: ValueAnimator? = null

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        val a = context.obtainStyledAttributes(attrs, R.styleable.ReceiptLayout)
        edgeTop = a.getBoolean(R.styleable.ReceiptLayout_edgeTop, false)
        edgeBottom = a.getBoolean(R.styleable.ReceiptLayout_edgeBottom, true)
        flat = a.getBoolean(R.styleable.ReceiptLayout_flat, false)
        a.recycle()
        if (flat) {
            edgeTop = false; edgeBottom = false
            paper.color = ContextCompat.getColor(context, R.color.hesba_surface)
            edge.color = ContextCompat.getColor(context, R.color.hesba_border)
        }
        // place pour les dents
        setPadding(paddingLeft, paddingTop + if (edgeTop) toothH.toInt() else 0, paddingRight, paddingBottom + if (edgeBottom) toothH.toInt() else 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildPath(w.toFloat(), h.toFloat())
    }

    private fun buildPath(w: Float, h: Float) {
        path.reset()
        val half = edge.strokeWidth / 2
        if (flat) {
            path.addRoundRect(half, half, w - half, h - half, radius, radius, Path.Direction.CW)
            return
        }
        val count = maxOf(1, (w / tooth).toInt())
        val step = w / count
        if (edgeTop) {
            path.moveTo(half, toothH)
            for (i in 0 until count) {
                path.lineTo(i * step + step / 2, half)
                path.lineTo((i + 1) * step - if (i == count - 1) half else 0f, toothH)
            }
        } else {
            path.moveTo(half, half); path.lineTo(w - half, half)
        }
        if (edgeBottom) {
            path.lineTo(w - half, h - toothH)
            for (i in count - 1 downTo 0) {
                path.lineTo(i * step + step / 2, h - half)
                path.lineTo(i * step + if (i == 0) half else 0f, h - toothH)
            }
        } else {
            path.lineTo(w - half, h - half); path.lineTo(half, h - half)
        }
        path.close()
    }

    override fun draw(canvas: Canvas) {
        if (revealed >= 1f) { super.draw(canvas); return }
        val h = height.toFloat()
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), h * revealed)
        canvas.translate(0f, -h * (1f - revealed))
        super.draw(canvas)
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, paper)
        canvas.drawPath(path, edge)
        super.onDraw(canvas)
    }

    /** Fait « imprimer » le ticket. Durée proportionnelle à sa hauteur, plafonnée. */
    fun print(onEnd: (() -> Unit)? = null) {
        animator?.cancel()
        if (!Motion.enabled(context)) { revealed = 1f; invalidate(); onEnd?.invoke(); return }
        revealed = 0f
        invalidate()
        post {
            val ms = (height / density * 2.2f).toLong().coerceIn(Motion.MEDIUM, 900L)
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ms
                interpolator = Motion.standard
                addUpdateListener { revealed = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { onEnd?.invoke() }
                })
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        revealed = 1f
        super.onDetachedFromWindow()
    }
}

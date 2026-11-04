package com.example.hesba

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.core.content.res.ResourcesCompat
import com.example.hesba.databinding.ActivityOnboardingBinding
import com.example.hesba.databinding.ItemLanguageBinding
import com.example.hesba.ui.BaseActivity
import com.example.hesba.ui.Fmt
import com.example.hesba.ui.Motion
import com.example.hesba.ui.Settings
import com.example.hesba.ui.widget.DotLeaderView
import kotlin.math.abs

/**
 * Premier lancement (une seule fois, voir [Settings.onboardingDone]) :
 * 1. l'histoire : trois phrases, des ingrédients et leurs prix tombent sur un ticket, puis le total ;
 * 2. la langue : Français / English / العربية, appliquée tout de suite (l'écran se recrée, RTL en arabe) ;
 * 3. le compte : créer un compte ou se connecter.
 * Les montants du ticket sont un exemple calculé (500 g de farine à 1,350 DT/kg, etc.), pas des données.
 */
class OnboardingActivity : BaseActivity() {

    companion object {
        const val EXTRA_PAGE = "page"
        private const val STATE_PAGE = "page"
        private const val STATE_STORY_SEEN = "story_seen"
    }

    override val requiresSession = false
    private lateinit var b: ActivityOnboardingBinding
    private var page = 0
    private var storySeen = false
    private var arrowAnim: ObjectAnimator? = null

    // exemple du ticket, en millimes : 500 g × 1,350 ; 250 g × 19,500/kg ; 200 g × 1,600 ; 4 × 0,450
    private val sample = listOf(R.string.onb_r1 to 675L, R.string.onb_r2 to 4875L, R.string.onb_r3 to 320L, R.string.onb_r4 to 1800L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOnboardingBinding.inflate(layoutInflater)
        setScreen(b.root)
        page = savedInstanceState?.getInt(STATE_PAGE) ?: intent.getIntExtra(EXTRA_PAGE, 0)
        storySeen = savedInstanceState?.getBoolean(STATE_STORY_SEEN) ?: false

        buildReceipt()
        buildLanguages()

        b.btnSkip.setOnClickListener { show(1) }
        b.btnNext.setOnClickListener { show(1) }
        b.pageStory.setOnClickListener { if (storySeen) show(1) }
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                if (abs(dx) < 80 * resources.displayMetrics.density || abs(vx) < abs(vy)) return false
                val forward = if (isRtl()) dx > 0 else dx < 0
                if (forward) show(1)
                return forward
            }
        })
        b.pageStory.setOnTouchListener { v, e -> swipe.onTouchEvent(e) || run { if (e.action == MotionEvent.ACTION_UP) v.performClick(); false } }

        b.btnLangNext.setOnClickListener { show(2) }
        b.btnCreate.setOnClickListener { finishTo(create = true) }
        b.btnLogin.setOnClickListener { finishTo(create = false) }
        onBackPressedDispatcher.addCallback(this) { if (page > 0) show(page - 1) else finish() }

        show(page, animate = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, page)
        outState.putBoolean(STATE_STORY_SEEN, storySeen)
    }

    private fun isRtl() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private fun show(target: Int, animate: Boolean = true) {
        val pages = listOf(b.pageStory, b.pageLanguage, b.pageAccount)
        val from = page
        page = target
        pages.forEachIndexed { i, v -> if (i != target && i != from) v.visibility = View.GONE }
        val incoming = pages[target]
        val outgoing = pages[from]
        if (animate && from != target && Motion.enabled(this)) {
            val dir = (if (target > from) 1 else -1) * (if (isRtl()) -1 else 1)
            val shift = 32 * resources.displayMetrics.density * dir
            outgoing.animate().alpha(0f).translationX(-shift).setDuration(Motion.SHORT).withEndAction {
                outgoing.visibility = View.GONE; outgoing.alpha = 1f; outgoing.translationX = 0f
            }.start()
            incoming.visibility = View.VISIBLE
            incoming.alpha = 0f; incoming.translationX = shift
            incoming.animate().alpha(1f).translationX(0f).setStartDelay(Motion.SHORT / 2).setDuration(Motion.MEDIUM).setInterpolator(Motion.decelerate).start()
        } else {
            if (from != target) outgoing.visibility = View.GONE
            incoming.visibility = View.VISIBLE
        }
        if (target == 0) playStory() else arrowAnim?.cancel()
        incoming.post { incoming.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) }
    }

    // ---------- 1. histoire ----------

    private fun ticketLine(label: String, value: String, strong: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
        }
        val style = if (strong) R.style.TextAppearance_Hesba_Ticket_Strong else R.style.TextAppearance_Hesba_Ticket
        row.addView(TextView(this).apply { setTextAppearance(style); text = label; textAlignment = View.TEXT_ALIGNMENT_VIEW_START },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply { setTextAppearance(style); text = value })
        return row
    }

    private fun buildReceipt() {
        sample.forEach { (label, mill) -> b.storyLines.addView(ticketLine(getString(label), Fmt.amount(this, mill), false)) }
        val total = sample.sumOf { it.second }
        b.storyTotals.addView(ticketLine(getString(R.string.onb_total), Fmt.money(this, total), true))
        b.storyTotals.addView(ticketLine(getString(R.string.onb_per_part), Fmt.money(this, Math.round(total / 12.0)), false))
    }

    private fun playStory() {
        val lines = (0 until b.storyLines.childCount).map { b.storyLines.getChildAt(it) }
        val totals = listOf<View>(b.storyRule, b.storyTotals)
        val all = listOf(b.line1, b.line2, b.line3, b.nextRow) + lines + totals
        if (storySeen || !Motion.enabled(this)) {
            all.forEach { it.alpha = 1f; it.translationY = 0f }
            storySeen = true
            nudgeArrow()
            return
        }
        all.forEach { it.alpha = 0f }
        val d = resources.displayMetrics.density
        b.line1.animate().alpha(1f).setStartDelay(300).setDuration(Motion.LONG).start()
        lines.forEachIndexed { i, v ->
            v.translationY = -36 * d
            v.animate().alpha(1f).translationY(0f).setStartDelay(900L + i * 320).setDuration(Motion.MEDIUM)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        }
        b.line2.animate().alpha(1f).setStartDelay(2300).setDuration(Motion.LONG).start()
        totals.forEachIndexed { i, v ->
            v.translationY = 8 * d
            v.animate().alpha(1f).translationY(0f).setStartDelay(2900L + i * 200).setDuration(Motion.MEDIUM).setInterpolator(Motion.decelerate).start()
        }
        b.line3.animate().alpha(1f).setStartDelay(3800).setDuration(Motion.LONG).start()
        b.nextRow.animate().alpha(1f).setStartDelay(4400).setDuration(Motion.MEDIUM).withEndAction {
            storySeen = true
            nudgeArrow()
        }.start()
    }

    /** La flèche avance doucement vers la suite (dans le sens de lecture), en boucle. */
    private fun nudgeArrow() {
        if (!Motion.enabled(this)) return
        arrowAnim?.cancel()
        val dist = 6 * resources.displayMetrics.density * (if (isRtl()) -1 else 1)
        arrowAnim = ObjectAnimator.ofFloat(b.btnNext, View.TRANSLATION_X, 0f, dist, 0f).apply {
            duration = 1200
            startDelay = 400
            repeatCount = ValueAnimator.INFINITE
            interpolator = Motion.standard
            start()
        }
    }

    override fun onDestroy() {
        arrowAnim?.cancel()
        super.onDestroy()
    }

    // ---------- 2. langue ----------

    private fun buildLanguages() {
        val current = resources.configuration.locales[0].language
        val options = listOf(
            Triple("fr", R.string.lang_fr_native, R.string.lang_fr_hint),
            Triple("en", R.string.lang_en_native, R.string.lang_en_hint),
            Triple("ar", R.string.lang_ar_native, R.string.lang_ar_hint)
        )
        options.forEach { (tag, name, hint) ->
            val v = ItemLanguageBinding.inflate(LayoutInflater.from(this), b.langOptions, false)
            v.langName.setText(name)
            v.langHint.setText(hint)
            if (tag == "ar") v.langName.typeface = ResourcesCompat.getFont(this, R.font.hb_sans_semibold)
            val on = current == tag || (tag == "fr" && current !in setOf("en", "ar"))
            v.root.isSelected = on
            v.langCheck.visibility = if (on) View.VISIBLE else View.INVISIBLE
            v.root.contentDescription = getString(name) + ", " + getString(hint)
            v.root.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.RadioButton"
                    info.isCheckable = true
                    info.isChecked = on
                }
            }
            v.root.setOnClickListener {
                if (on) return@setOnClickListener
                Motion.tick(it)
                Settings.setLanguage(tag)   // AppCompat recrée l'écran dans la nouvelle langue (et en RTL pour l'arabe)
            }
            b.langOptions.addView(v.root)
        }
    }

    // ---------- 3. compte ----------

    private fun finishTo(create: Boolean) {
        Settings.setOnboardingDone(this)
        val login = Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (create) startActivities(arrayOf(login, Intent(this, RegisterActivity::class.java)))
        else startActivity(login)
        finish()
    }
}

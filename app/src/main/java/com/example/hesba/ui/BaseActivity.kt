package com.example.hesba.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.hesba.LoginActivity
import com.example.hesba.R
import com.example.hesba.auth.SessionManager
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.HesbaRepository
import com.google.android.material.snackbar.Snackbar

/** Base de tous les écrans : bord à bord, marges système/clavier, session, base de données, messages. */
abstract class BaseActivity : AppCompatActivity() {

    val db: HesbaDatabase by lazy { HesbaDatabase.getInstance(this) }
    val session: SessionManager by lazy { SessionManager(this) }
    val repo: HesbaRepository by lazy { HesbaRepository(db, session.getUserId()) }

    /** Les écrans protégés renvoient vers la connexion si aucune session n'existe. */
    open val requiresSession: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (requiresSession && !session.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
    }

    /** Applique les marges des barres système (et du clavier) à la vue racine @id/main. */
    protected fun setScreen(layout: Int) {
        setContentView(layout)
        applyInsets(findViewById(R.id.main))
    }

    protected fun setScreen(view: View) {
        setContentView(view)
        applyInsets(view)
    }

    private fun applyInsets(root: View) {
        val left = root.paddingLeft
        val right = root.paddingRight
        val top = root.paddingTop
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                left = bars.left + left,
                right = bars.right + right,
                top = bars.top + top,
                bottom = maxOf(bars.bottom, ime.bottom) + bottom
            )
            WindowInsetsCompat.CONSUMED
        }
        findViewById<View?>(R.id.btnBack)?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    protected fun setTitleText(title: CharSequence) {
        findViewById<TextView?>(R.id.txtTitle)?.text = title
    }

    fun snack(message: CharSequence, long: Boolean = false) {
        Snackbar.make(findViewById(R.id.main), message, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT).show()
    }

    fun snack(@StringRes message: Int, long: Boolean = false) = snack(getString(message), long)
}

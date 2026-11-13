package com.example.hesba

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.hesba.auth.AuthRepository
import com.example.hesba.auth.AuthResult
import com.example.hesba.data.BackupManager
import com.example.hesba.data.Utilisateur
import com.example.hesba.databinding.ActivityProfilBinding
import com.example.hesba.databinding.DialogPasswordBinding
import com.example.hesba.databinding.ItemLinkBinding
import com.example.hesba.domain.Money
import com.example.hesba.domain.NumberParser
import com.example.hesba.ui.BaseActivity
import com.example.hesba.ui.Dialogs
import com.example.hesba.ui.Fmt
import com.example.hesba.ui.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Profil : identité, commerce et charges (loyer, employés — repris d'Aya, sans valeurs par défaut inventées),
 * langue, thème, sauvegarde/restauration, factures, mot de passe, déconnexion.
 */
class ProfilActivity : BaseActivity() {

    private lateinit var b: ActivityProfilBinding
    private var user: Utilisateur? = null

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { BackupManager.export(this@ProfilActivity, db, uri) }
            snack(if (ok) R.string.backup_done else R.string.backup_failed, long = true)
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        Dialogs.confirm(this, getString(R.string.restore_confirm_title), getString(R.string.restore_confirm_body), R.string.action_restore, destructive = true) {
            lifecycleScope.launch {
                when (withContext(Dispatchers.IO) { BackupManager.restore(this@ProfilActivity, uri) }) {
                    BackupManager.RestoreResult.Ok -> {
                        session.logout()
                        Toasts.long(this@ProfilActivity, getString(R.string.restore_done))
                        startActivity(Intent(this@ProfilActivity, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                        finish()
                    }
                    BackupManager.RestoreResult.NotHesba -> snack(R.string.restore_not_hesba, long = true)
                    BackupManager.RestoreResult.TooNew -> snack(R.string.restore_too_new, long = true)
                    BackupManager.RestoreResult.Unreadable -> snack(R.string.restore_unreadable, long = true)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        b = ActivityProfilBinding.inflate(layoutInflater)
        setScreen(b.root)
        BottomNav.setup(this, BottomNav.Tab.PROFILE)

        link(b.rowLanguage, R.drawable.ic_language, R.string.pref_language, languageLabel()) { chooseLanguage() }
        link(b.rowTheme, R.drawable.ic_theme, R.string.pref_theme, themeLabel()) { chooseTheme() }
        link(b.rowBackup, R.drawable.ic_backup, R.string.pref_backup, getString(R.string.pref_backup_sub)) {
            exportLauncher.launch("hesba-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".db")
        }
        link(b.rowRestore, R.drawable.ic_restore, R.string.pref_restore, getString(R.string.pref_restore_sub)) {
            importLauncher.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "application/vnd.sqlite3", "*/*"))
        }
        link(b.rowInvoices, R.drawable.ic_invoice, R.string.tool_invoices, getString(R.string.pref_invoices_sub)) {
            startActivity(Intent(this, InvoicesActivity::class.java))
        }
        link(b.rowPassword, R.drawable.ic_lock, R.string.pref_password, getString(R.string.pref_password_sub)) { changePassword() }

        b.btnLogout.setText(if (session.isGuest()) R.string.action_leave_guest else R.string.action_logout)
        b.btnLogout.setOnClickListener { logout() }
        b.btnCreateAccount.setOnClickListener { startActivity(Intent(this, RegisterActivity::class.java)) }
        b.btnSaveProfile.setOnClickListener { saveProfile() }
        b.groupActivity.setOnCheckedStateChangeListener { _, _ -> updateChargesFields(); preview() }
        listOf(b.editRent, b.editVolume, b.editEmployees, b.editSalary, b.editHours).forEach { it.doAfterTextChanged { preview() } }
        b.editVolume.doAfterTextChanged { b.tilVolume.error = null }
        b.editHours.doAfterTextChanged { b.tilHours.error = null }
        b.txtVersion.text = getString(R.string.app_version, BuildConfigInfo.versionName(this))

        lifecycleScope.launch {
            user = repo.currentUser()
            show(user, savedInstanceState == null)
        }
    }

    private fun link(row: ItemLinkBinding, icon: Int, title: Int, sub: String, onClick: () -> Unit) {
        row.icon.setImageResource(icon)
        row.title.setText(title)
        row.subtitle.text = sub
        row.rowClick.setOnClickListener { onClick() }
    }

    private fun show(u: Utilisateur?, fillFields: Boolean) {
        val guest = u == null
        b.blockGuest.visibility = if (guest) View.VISIBLE else View.GONE
        b.blockBusiness.visibility = if (guest) View.GONE else View.VISIBLE
        b.rowPassword.root.visibility = if (guest) View.GONE else View.VISIBLE
        b.txtMonogram.text = u?.prenom?.trim()?.take(1)?.uppercase() ?: "?"
        b.txtName.text = u?.prenom ?: getString(R.string.profile_guest_name)
        b.txtEmail.text = u?.email ?: getString(R.string.profile_guest_email)
        if (u == null || !fillFields) { updateChargesFields(); preview(); return }
        b.editFirstName.setText(u.prenom)
        b.editBusiness.setText(u.nomCommerce)
        b.editCity.setText(u.ville)
        b.groupActivity.check(when (u.activite) { "sans_employe" -> R.id.chipSolo; "avec_employe" -> R.id.chipStaff; else -> R.id.chipHome })
        if (u.loyerMensuelMillimes > 0) b.editRent.setText(Fmt.inputAmount(this, u.loyerMensuelMillimes))
        if (u.volumeMensuel > 0) b.editVolume.setText(u.volumeMensuel.toString())
        if (u.nombreEmployes > 0) b.editEmployees.setText(u.nombreEmployes.toString())
        if (u.salaireMoyenMillimes > 0) b.editSalary.setText(Fmt.inputAmount(this, u.salaireMoyenMillimes))
        if (u.heuresTravailMois > 0) b.editHours.setText(u.heuresTravailMois.toString())
        updateChargesFields()
        preview()
    }

    private fun activity(): String = when (b.groupActivity.checkedChipId) {
        R.id.chipSolo -> "sans_employe"
        R.id.chipStaff -> "avec_employe"
        else -> "domicile"
    }

    private fun updateChargesFields() {
        b.tilEmployees.visibility = if (activity() == "avec_employe") View.VISIBLE else View.GONE
        b.tilSalary.hint = getString(if (activity() == "avec_employe") R.string.field_salary else R.string.field_salary_self)
    }

    private fun draftUser(): Utilisateur? {
        val u = user ?: return null
        val act = activity()
        return u.copy(
            prenom = b.editFirstName.text.toString().trim().ifEmpty { u.prenom },
            nomCommerce = b.editBusiness.text.toString().trim(),
            ville = b.editCity.text.toString().trim(),
            activite = act,
            loyerMensuelMillimes = b.editRent.text?.toString()?.let { Money.parseMillimes(it) } ?: 0,
            volumeMensuel = NumberParser.parseCount(b.editVolume.text?.toString()) ?: 0,
            nombreEmployes = if (act == "avec_employe") NumberParser.parseCount(b.editEmployees.text?.toString()) ?: 0 else 0,
            salaireMoyenMillimes = b.editSalary.text?.toString()?.let { Money.parseMillimes(it) } ?: 0,
            heuresTravailMois = NumberParser.parseCount(b.editHours.text?.toString()) ?: 0
        )
    }

    /** Montre tout de suite ce que les charges ajoutent par part et par heure. */
    private fun preview() {
        val u = draftUser() ?: return
        val fixed = RecipeCosting.fixedPerPortion(u)
        val perHour = RecipeCosting.laborEstimate(u, 60)
        val parts = mutableListOf<String>()
        parts += if (fixed > 0) getString(R.string.charges_preview_fixed, Fmt.money(this, fixed)) else getString(R.string.charges_preview_fixed_none)
        parts += if (perHour != null) getString(R.string.charges_preview_labor, Fmt.money(this, perHour)) else getString(R.string.charges_preview_labor_none)
        b.txtChargesPreview.text = parts.joinToString("\n")
    }

    private fun saveProfile() {
        val u = draftUser() ?: return
        if (u.prenom.isBlank()) { b.tilFirstName.error = getString(R.string.error_name_required); return }
        // « 2,5 » ou un nombre trop grand : erreur sur le champ au lieu d'un 2 ou d'un 0 enregistré en silence
        var counts = true
        for ((edit, til) in listOf(b.editVolume to b.tilVolume, b.editEmployees to b.tilEmployees, b.editHours to b.tilHours)) {
            val bad = !edit.text.isNullOrBlank() && NumberParser.parseCount(edit.text.toString()) == null
            til.error = if (bad) getString(R.string.error_whole_number) else null
            if (bad) counts = false
        }
        if (!counts) return
        if (u.activite == "avec_employe" && u.nombreEmployes <= 0 && u.salaireMoyenMillimes > 0) {
            b.tilEmployees.error = getString(R.string.error_employees); return
        }
        b.tilEmployees.error = null
        lifecycleScope.launch {
            repo.updateUser(u)
            user = u
            show(u, false)
            snack(R.string.profile_saved)
        }
    }

    private fun languageLabel(): String = when (Settings.language()) {
        "fr" -> getString(R.string.lang_fr)
        "en" -> getString(R.string.lang_en)
        "ar" -> getString(R.string.lang_ar)
        else -> getString(R.string.lang_system)
    }

    private fun chooseLanguage() {
        val tags = arrayOf("", "fr", "ar", "en")
        val labels = arrayOf(getString(R.string.lang_system), getString(R.string.lang_fr), getString(R.string.lang_ar), getString(R.string.lang_en))
        val current = tags.indexOf(Settings.language()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pref_language)
            .setSingleChoiceItems(labels, current) { d, which -> d.dismiss(); Settings.setLanguage(tags[which]) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun themeLabel(): String = when (Settings.theme(this)) {
        Settings.THEME_LIGHT -> getString(R.string.theme_light)
        Settings.THEME_DARK -> getString(R.string.theme_dark)
        else -> getString(R.string.theme_system)
    }

    private fun chooseTheme() {
        val values = arrayOf(Settings.THEME_SYSTEM, Settings.THEME_LIGHT, Settings.THEME_DARK)
        val labels = arrayOf(getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pref_theme)
            .setSingleChoiceItems(labels, values.indexOf(Settings.theme(this)).coerceAtLeast(0)) { d, which -> d.dismiss(); Settings.setTheme(this, values[which]) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun changePassword() {
        val u = user ?: return
        val v = DialogPasswordBinding.inflate(LayoutInflater.from(this))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pref_password)
            .setView(v.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        v.editCurrent.doAfterTextChanged { v.tilCurrent.error = null }
        v.editNew.doAfterTextChanged { v.tilNew.error = null }
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            lifecycleScope.launch {
                when (val r = AuthRepository(db.utilisateurDao()).changePassword(u, v.editCurrent.text.toString(), v.editNew.text.toString())) {
                    is AuthResult.Success -> { dialog.dismiss(); user = repo.currentUser(); snack(R.string.password_changed) }
                    is AuthResult.Error -> if (r.message == R.string.error_password_wrong) v.tilCurrent.error = getString(r.message) else v.tilNew.error = getString(r.message)
                }
            }
        }
    }

    private fun logout() {
        val message = getString(if (session.isGuest()) R.string.logout_guest_body else R.string.logout_body)
        Dialogs.confirm(this, getString(R.string.logout_title), message, if (session.isGuest()) R.string.action_leave_guest else R.string.action_logout) {
            session.logout()
            startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
    }
}

object BuildConfigInfo {
    fun versionName(context: android.content.Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
}

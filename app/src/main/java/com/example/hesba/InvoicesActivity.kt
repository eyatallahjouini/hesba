package com.example.hesba

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hesba.data.Facture
import com.example.hesba.databinding.ActivityListScreenBinding
import com.example.hesba.databinding.ItemLinkBinding
import com.example.hesba.ui.BaseActivity
import com.example.hesba.ui.Dialogs
import com.example.hesba.ui.Fmt
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Factures importées. Les images restent dans le stockage privé de l'appli (filesDir/factures),
 * ne sont jamais envoyées, et l'utilisateur peut les supprimer une par une ou toutes.
 */
class InvoicesActivity : BaseActivity() {

    private lateinit var b: ActivityListScreenBinding
    private var invoices: List<Facture> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        b = ActivityListScreenBinding.inflate(layoutInflater)
        setScreen(b.root)
        setTitleText(getString(R.string.tool_invoices))
        b.txtIntro.setText(R.string.invoices_intro)
        b.btnFooter.setText(R.string.invoices_delete_images)
        b.btnFooter.setOnClickListener { deleteAllImages() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.invoices().collect { invoices = it; render() }
            }
        }
    }

    private fun render() {
        b.content.removeAllViews()
        b.empty.root.visibility = if (invoices.isEmpty()) View.VISIBLE else View.GONE
        b.footer.visibility = if (invoices.any { it.imagePath != null }) View.VISIBLE else View.GONE
        if (invoices.isEmpty()) {
            b.empty.emptyIcon.setImageResource(R.drawable.ic_invoice)
            b.empty.emptyTitle.setText(R.string.invoices_empty_title)
            b.empty.emptyBody.setText(R.string.invoices_empty_body)
            b.empty.emptyAction.setText(R.string.action_scan_invoice)
            b.empty.emptyAction.setOnClickListener { startActivity(Intent(this, ScanActivity::class.java)) }
            return
        }
        b.content.addView(View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.hairline)).apply { topMargin = resources.getDimensionPixelSize(R.dimen.sp_4) }
            setBackgroundColor(getColor(R.color.hb_line))
        })
        for (f in invoices) {
            val v = ItemLinkBinding.inflate(LayoutInflater.from(this), b.content, false)
            v.icon.setImageResource(R.drawable.ic_invoice)
            v.title.text = f.fournisseur.ifBlank { getString(R.string.invoice_unknown_supplier) }
            val date = f.dateFacture.ifBlank { Fmt.date(this, f.dateImport) }
            v.subtitle.text = getString(R.string.invoice_row, date, resources.getQuantityString(R.plurals.lines_count, f.nombreLignes, f.nombreLignes)) +
                if (f.imagePath == null) " · " + getString(R.string.invoice_no_image) else ""
            if (f.totalMillimes != null) { v.value.visibility = View.VISIBLE; v.value.text = Fmt.money(this, f.totalMillimes) }
            v.rowClick.setOnClickListener { open(f) }
            b.content.addView(v.root)
        }
    }

    private fun open(f: Facture) {
        val items = mutableListOf<String>()
        if (f.imagePath != null) { items += getString(R.string.invoice_view_image); items += getString(R.string.invoice_delete_image) }
        items += getString(R.string.invoice_delete)
        MaterialAlertDialogBuilder(this)
            .setTitle(f.fournisseur.ifBlank { getString(R.string.invoice_unknown_supplier) })
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    getString(R.string.invoice_view_image) -> showImage(f)
                    getString(R.string.invoice_delete_image) -> lifecycleScope.launch { deleteImage(f) }
                    else -> Dialogs.confirm(this, getString(R.string.invoice_delete), getString(R.string.invoice_delete_body), R.string.action_delete, destructive = true) {
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { f.imagePath?.let { File(it).delete() } }
                            repo.deleteInvoice(f)
                        }
                    }
                }
            }
            .show()
    }

    private fun showImage(f: Facture) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                f.imagePath?.let { path ->
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, o)
                    var s = 1
                    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1600) s *= 2
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
                }
            }
            if (bmp == null) { snack(R.string.invoice_image_missing); return@launch }
            val iv = ImageView(this@InvoicesActivity).apply { setImageBitmap(bmp); adjustViewBounds = true; contentDescription = getString(R.string.cd_invoice_image) }
            MaterialAlertDialogBuilder(this@InvoicesActivity).setView(iv).setPositiveButton(R.string.action_close, null).show()
        }
    }

    private suspend fun deleteImage(f: Facture) {
        withContext(Dispatchers.IO) { f.imagePath?.let { File(it).delete() } }
        repo.updateInvoice(f.copy(imagePath = null))
        snack(R.string.invoice_image_deleted)
    }

    private fun deleteAllImages() {
        Dialogs.confirm(this, getString(R.string.invoices_delete_images), getString(R.string.invoices_delete_images_body), R.string.action_delete, destructive = true) {
            lifecycleScope.launch { invoices.filter { it.imagePath != null }.forEach { deleteImage(it) } }
        }
    }
}

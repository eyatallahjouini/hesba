package com.example.hesba.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hesba.R
import com.example.hesba.databinding.DialogPickBinding
import com.example.hesba.domain.TextNormalizer
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Liste de choix avec recherche (ingrédients, recettes).
 * [onCreate] (facultatif) affiche « Créer … » avec le texte tapé.
 */
object Picker {

    data class Option<T>(val value: T, val title: String, val subtitle: String)

    fun <T> show(
        context: Context,
        title: String,
        options: List<Option<T>>,
        emptyText: String,
        onCreate: ((String) -> Unit)? = null,
        createLabel: (String) -> String = { context.getString(R.string.action_create_named, it) },
        onPick: (T) -> Unit
    ) {
        val b = DialogPickBinding.inflate(LayoutInflater.from(context))
        lateinit var dialog: AlertDialog
        var filtered = options

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pick, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun getItemCount() = filtered.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val o = filtered[position]
                holder.itemView.findViewById<TextView>(R.id.pickTitle).text = o.title
                holder.itemView.findViewById<TextView>(R.id.pickSubtitle).apply {
                    text = o.subtitle
                    visibility = if (o.subtitle.isEmpty()) View.GONE else View.VISIBLE
                }
                holder.itemView.setOnClickListener { dialog.dismiss(); onPick(o.value) }
            }
        }
        b.list.layoutManager = LinearLayoutManager(context)
        b.list.adapter = adapter
        b.search.editSearch.hint = context.getString(R.string.search_hint)

        fun refresh(q: String) {
            filtered = if (q.isBlank()) options else options.filter { TextNormalizer.matches(it.title, q) || TextNormalizer.matches(it.subtitle, q) }
            @Suppress("NotifyDataSetChanged") adapter.notifyDataSetChanged()
            b.txtEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            b.txtEmpty.text = emptyText
            b.search.btnClear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
            if (onCreate != null) {
                b.btnCreate.visibility = View.VISIBLE
                b.btnCreate.text = if (q.isBlank()) context.getString(R.string.action_new_ingredient) else createLabel(q.trim())
            }
        }
        b.search.editSearch.doAfterTextChanged { refresh(it?.toString().orEmpty()) }
        b.search.btnClear.setOnClickListener { b.search.editSearch.text.clear() }
        b.btnCreate.setOnClickListener { dialog.dismiss(); onCreate?.invoke(b.search.editSearch.text.toString().trim()) }
        refresh("")

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(b.root)
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}

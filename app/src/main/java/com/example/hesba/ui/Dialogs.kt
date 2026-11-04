package com.example.hesba.ui

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import androidx.annotation.StringRes
import androidx.core.widget.doAfterTextChanged
import com.example.hesba.R
import com.example.hesba.databinding.DialogInputBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object Dialogs {

    fun confirm(
        context: Context,
        title: String,
        message: String,
        @StringRes positive: Int,
        destructive: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        if (destructive) dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(context.getColor(R.color.hb_bad))
    }

    fun info(context: Context, title: String, message: String) {
        MaterialAlertDialogBuilder(context).setTitle(title).setMessage(message).setPositiveButton(R.string.action_ok, null).show()
    }

    /**
     * Saisie d'une valeur. [validate] renvoie un message d'erreur (affiché sous le champ) ou null si c'est bon ;
     * la boîte reste ouverte tant que la valeur n'est pas valable.
     */
    fun input(
        context: Context,
        title: String,
        hint: String,
        initial: String = "",
        money: Boolean = false,
        suffix: String? = null,
        helper: String? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        validate: (String) -> String? = { null },
        onOk: (String) -> Unit
    ) {
        val b = DialogInputBinding.inflate(LayoutInflater.from(context))
        b.til.hint = hint
        b.til.suffixText = suffix
        b.til.helperText = helper
        b.edit.inputType = if (money) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else inputType
        if (money) b.edit.fontFeatureSettings = "tnum"
        b.edit.setText(initial)
        b.edit.setSelection(initial.length)
        b.edit.doAfterTextChanged { b.til.error = null }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(b.root)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        b.edit.requestFocus()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val value = b.edit.text.toString().trim()
            val error = validate(value)
            if (error != null) b.til.error = error else { dialog.dismiss(); onOk(value) }
        }
    }
}

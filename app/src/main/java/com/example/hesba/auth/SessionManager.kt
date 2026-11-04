package com.example.hesba.auth

import android.content.Context
import androidx.core.content.edit

/** Session locale : l'identifiant du compte connecté (ou du mode essai, -1) est gardé dans les préférences privées. */
class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    companion object {
        const val ID_UTILISATEUR_INVITE = -1L
        private const val KEY_USER_ID = "user_id"
    }

    fun saveUser(id: Long) = prefs.edit { putLong(KEY_USER_ID, id) }
    fun getUserId(): Long = prefs.getLong(KEY_USER_ID, ID_UTILISATEUR_INVITE)
    fun isLoggedIn(): Boolean = prefs.contains(KEY_USER_ID)
    fun isGuest(): Boolean = getUserId() == ID_UTILISATEUR_INVITE
    fun logout() = prefs.edit { clear() }
}

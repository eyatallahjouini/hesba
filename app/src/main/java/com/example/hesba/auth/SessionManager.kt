package com.example.hesba.auth

import android.content.Context

class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    companion object {
        const val ID_UTILISATEUR_INVITE = -1L // <-- DOIT ÊTRE EXACTEMENT DANS LE COMPANION OBJECT
        private const val KEY_USER_ID = "user_id"
    }
    fun saveUser(id: Long) = prefs.edit().putLong("user_id", id).apply()
    fun getUserId(): Long = prefs.getLong("user_id", -1L)
    fun isLoggedIn() : Boolean = prefs.contains(KEY_USER_ID)
    fun logout() = prefs.edit().clear().apply()
}
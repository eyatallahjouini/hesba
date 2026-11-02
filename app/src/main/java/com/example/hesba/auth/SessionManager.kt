package com.example.hesba.auth

import android.content.Context

class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun saveUser(id: Long) = prefs.edit().putLong("user_id", id).apply()
    fun getUserId(): Long = prefs.getLong("user_id", -1L)
    fun isLoggedIn() = getUserId() != -1L
    fun logout() = prefs.edit().clear().apply()
}
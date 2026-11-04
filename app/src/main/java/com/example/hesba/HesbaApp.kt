package com.example.hesba

import android.app.Application
import com.example.hesba.ui.Settings

class HesbaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // thème clair / sombre / système choisi dans le Profil (la langue est gérée par AppCompat)
        Settings.applyTheme(Settings.theme(this))
    }
}

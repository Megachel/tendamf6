package com.megachel.tendamf6

import android.app.Application
import com.megachel.tendamf6.data.Prefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.appContext = applicationContext
    }
}

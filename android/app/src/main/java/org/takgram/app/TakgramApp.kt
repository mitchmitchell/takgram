package org.takgram.app

import android.app.Application

class TakgramApp : Application() {
    lateinit var tg: Tg
        private set

    override fun onCreate() {
        super.onCreate()
        tg = Tg(this)
    }
}

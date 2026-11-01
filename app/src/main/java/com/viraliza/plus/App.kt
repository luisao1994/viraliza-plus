package com.viraliza.plus

import android.app.Application

/** Inicia o registro de erros assim que o app (ou o robô) sobe. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Store.trace("processo iniciado")
    }
}

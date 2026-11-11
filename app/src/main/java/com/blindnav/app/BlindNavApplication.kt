package com.blindnav.app

import android.app.Application
import com.blindnav.app.crash.GlobalExceptionHandler

/**
 * BlindNav Application
 * 
 * Aplicación de navegación para invidentes con detección de obstáculos offline.
 * Optimizada para montaje en pecho con procesamiento de baja latencia.
 * 
 * 🚨 ROBUSTEZ: GlobalExceptionHandler instalado para interceptar crashes
 */
class BlindNavApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 🚨 CRÍTICO: Instalar handler global de crashes
        GlobalExceptionHandler.install(this)
    }

    companion object {
        lateinit var instance: BlindNavApplication
            private set
    }
}

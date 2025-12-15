package com.blindnav.app

import android.app.Application

/**
 * BlindNav Application
 * 
 * Aplicación de navegación para invidentes con detección de obstáculos offline.
 * Optimizada para montaje en pecho con procesamiento de baja latencia.
 */
class BlindNavApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: BlindNavApplication
            private set
    }
}

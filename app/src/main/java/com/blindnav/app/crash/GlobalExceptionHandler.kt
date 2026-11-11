package com.blindnav.app.crash

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 🚨 GlobalExceptionHandler - Interceptor de Crashes
 * 
 * En lugar de cerrar la app con una pantalla negra, captura el crash
 * y muestra una ErrorActivity con el stacktrace completo.
 * 
 * BENEFICIOS:
 * - Usuario ve POR QUÉ crasheó (no pantalla negra)
 * - Desarrollador obtiene logs detallados
 * - Mejor experiencia en producción
 */
class GlobalExceptionHandler(
    private val applicationContext: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "GlobalExceptionHandler"

        /**
         * Instala el handler global en la Application
         */
        fun install(context: Context) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            val globalHandler = GlobalExceptionHandler(context.applicationContext, defaultHandler)
            Thread.setDefaultUncaughtExceptionHandler(globalHandler)
            Log.d(TAG, "✓ GlobalExceptionHandler instalado correctamente")
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            Log.e(TAG, "❌ CRASH INTERCEPTADO en thread: ${thread.name}", throwable)

            // Extraer stacktrace completo
            val stacktrace = getStackTraceString(throwable)

            // Abrir ErrorActivity con el stacktrace
            val intent = Intent(applicationContext, ErrorActivity::class.java).apply {
                putExtra(ErrorActivity.EXTRA_ERROR_MESSAGE, throwable.message ?: "Error desconocido")
                putExtra(ErrorActivity.EXTRA_STACKTRACE, stacktrace)
                putExtra(ErrorActivity.EXTRA_THREAD_NAME, thread.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

            applicationContext.startActivity(intent)

            // Terminar proceso después de mostrar error
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)

        } catch (e: Exception) {
            Log.e(TAG, "Error mostrando ErrorActivity, delegando a handler por defecto", e)
            // Si falla, delegar al handler original
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Convierte Throwable a String con stacktrace completo
     */
    private fun getStackTraceString(throwable: Throwable): String {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        throwable.printStackTrace(printWriter)
        return stringWriter.toString()
    }
}

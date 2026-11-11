package com.blindnav.app.crash

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.blindnav.app.R

/**
 * 🚨 ErrorActivity - Pantalla de Error Amigable
 * 
 * Muestra el stacktrace completo cuando la app crashea,
 * en lugar de cerrar con pantalla negra.
 * 
 * USUARIO VE:
 * - Mensaje de error principal
 * - Stacktrace completo (scrollable)
 * - Botón para cerrar la app
 */
class ErrorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_STACKTRACE = "stacktrace"
        const val EXTRA_THREAD_NAME = "thread_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Layout simple programático (no requiere XML)
        val scrollView = ScrollView(this)
        val textView = TextView(this).apply {
            textSize = 12f
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true) // Permitir copiar el texto
        }
        scrollView.addView(textView)
        setContentView(scrollView)

        // Extraer datos del crash
        val errorMessage = intent.getStringExtra(EXTRA_ERROR_MESSAGE) ?: "Error desconocido"
        val stacktrace = intent.getStringExtra(EXTRA_STACKTRACE) ?: "Sin stacktrace"
        val threadName = intent.getStringExtra(EXTRA_THREAD_NAME) ?: "unknown"

        // Mostrar información del crash
        val errorText = buildString {
            appendLine("🚨 LA APLICACIÓN HA FALLADO")
            appendLine("=" .repeat(50))
            appendLine()
            appendLine("ERROR: $errorMessage")
            appendLine()
            appendLine("THREAD: $threadName")
            appendLine()
            appendLine("=" .repeat(50))
            appendLine("STACKTRACE COMPLETO:")
            appendLine("=" .repeat(50))
            appendLine()
            appendLine(stacktrace)
            appendLine()
            appendLine("=" .repeat(50))
            appendLine()
            appendLine("Por favor, reporte este error al desarrollador.")
            appendLine("Puede copiar este texto manteniendo presionado.")
        }

        textView.text = errorText

        // Soporte para el botón "back"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Error de Aplicación"
    }

    override fun onBackPressed() {
        // Cerrar la app completamente
        finishAffinity()
        super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}

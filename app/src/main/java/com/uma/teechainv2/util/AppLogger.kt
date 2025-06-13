package com.uma.teechainv2.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object AppLogger {

    private const val LOG_FILENAME = "app_log.txt"

    /**
     * Registra un mensaje en el archivo de log interno de la app.
     * El mensaje se guarda con una marca de tiempo.
     *
     * @param context Contexto necesario para acceder al directorio interno de archivos.
     * @param message Mensaje que se quiere registrar.
     */
    fun log(context: Context, message: String) {
        try {
            // Usa el Locale actual del sistema en cada llamada
            val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val timestamp = timestampFormat.format(Date())
            val line = "[$timestamp] $message\n"

            val logFile = File(context.filesDir, LOG_FILENAME)
            logFile.appendText(line)
        } catch (e: Exception) {
            Log.e("AppLogger", "Error writing log: ${e.message}")
        }
    }

    /**
     * Devuelve el contenido actual del archivo de log como texto.
     */
    fun readLogs(context: Context): String {
        return try {
            val logFile = File(context.filesDir, LOG_FILENAME)
            if (logFile.exists()) logFile.readText() else ""
        } catch (e: Exception) {
            Log.e("AppLogger", "Error reading log: ${e.message}")
            ""
        }
    }

    /**
     * Elimina el archivo de log si existe.
     */
    fun clearLogs(context: Context): Boolean {
        return try {
            val logFile = File(context.filesDir, LOG_FILENAME)
            logFile.delete()
        } catch (e: Exception) {
            Log.e("AppLogger", "Error deleting log: ${e.message}")
            false
        }
    }

    /**
     * Devuelve la ruta absoluta del archivo de log para exportación o debug.
     */
    fun getLogFilePath(context: Context): String {
        return File(context.filesDir, LOG_FILENAME).absolutePath
    }
}

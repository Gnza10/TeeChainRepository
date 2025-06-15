package com.uma.teechainv2

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.uma.teechainv2.util.AppLogger
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    // Declaración de vistas de la interfaz
    private lateinit var editTextIp: EditText
    private lateinit var editTextPort: EditText
    private lateinit var buttonConnect: Button
    private lateinit var buttonLogs: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicialización de vistas
        editTextIp = findViewById(R.id.editTextIp)
        editTextPort = findViewById(R.id.editTextPort)
        buttonConnect = findViewById(R.id.buttonConnect)
        buttonLogs = findViewById(R.id.buttonLog)

        // Registro de log de inicio de sesión exitoso
        AppLogger.log(this, getString(R.string.loginSucces))

        // Acción al pulsar el botón de conectar
        buttonConnect.setOnClickListener {
            val ip = editTextIp.text.toString().trim()
            val port = editTextPort.text.toString().trim()

            // Validación de entradas
            if (!validateInputs(ip, port)) return@setOnClickListener

            logAndToast(getString(R.string.log_checking_connection))
            buttonConnect.isEnabled = false

            // Comprobación de disponibilidad del nodo
            checkNodeAvailability(ip, port) { isAvailable ->
                runOnUiThread {
                    buttonConnect.isEnabled = true

                    if (isAvailable) {
                        // Si el nodo está disponible, se registra el log y se abre la siguiente actividad
                        AppLogger.log(
                            this,
                            getString(R.string.log_connection_success, ip, port)
                        )
                        startActivity(Intent(this, ConnectionActivity::class.java).apply {
                            putExtra("ip", ip)
                            putExtra("port", port)
                        })
                    } else {
                        // En caso de fallo, se informa mediante log y toast
                        logAndToast(getString(R.string.connection_failed), long = true)
                    }
                }
            }
        }

        // Acción al pulsar el botón de ver logs
        buttonLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    /**
     * Valida que los campos de IP y puerto no estén vacíos y tengan formato correcto.
     */
    private fun validateInputs(ip: String, port: String): Boolean {
        if (ip.isBlank() || port.isBlank()) {
            logAndToast(getString(R.string.empty_ip_port_error))
            return false
        }

        // Regex para validar una IP o nombre de host
        val ipOrHostRegex = Regex(
            "^(([a-zA-Z0-9][-a-zA-Z0-9]*\\.)+[a-zA-Z]{2,}|localhost|" +
                    "((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}" +
                    "(25[0-5]|2[0-4]\\d|[01]?\\d\\d?))$"
        )

        if (!ipOrHostRegex.matches(ip)) {
            logAndToast(getString(R.string.invalid_ip_format))
            return false
        }

        // Comprobación de que el puerto esté entre 1 y 65535
        val portNumber = port.toIntOrNull()
        if (portNumber == null || portNumber !in 1..65535) {
            logAndToast(getString(R.string.invalid_port))
            return false
        }

        return true
    }

    /**
     * Comprueba si el nodo Ethereum está accesible a través de la URL formada por IP y puerto.
     * Se llama al callback con true si está disponible, false si no.
     */
    private fun checkNodeAvailability(ip: String, port: String, callback: (Boolean) -> Unit) {
        Thread {
            try {
                val url = URL("http://$ip:$port/eth/v2/beacon/blocks/head")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.requestMethod = "GET"
                callback(connection.responseCode in 200..299)
            } catch (e: Exception) {
                // Si hay error en la conexión, se considera el nodo no disponible
                callback(false)
            }
        }.start()
    }

    /**
     * Muestra un mensaje por pantalla (Toast) y registra el log de forma centralizada.
     * @param message Mensaje a mostrar y registrar.
     * @param long Indica si el Toast debe mostrarse por más tiempo.
     */
    private fun logAndToast(message: String, long: Boolean = false) {
        AppLogger.log(this, message)
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}

package com.uma.teechainv2

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.uma.teechainv2.util.AppLogger
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var editTextIp: EditText
    private lateinit var editTextPort: EditText
    private lateinit var buttonConnect: Button
    private lateinit var buttonLogs: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editTextIp = findViewById(R.id.editTextIp)
        editTextPort = findViewById(R.id.editTextPort)
        buttonConnect = findViewById(R.id.buttonConnect)
        buttonLogs = findViewById(R.id.buttonLog)

        buttonConnect.setOnClickListener {
            val ip = editTextIp.text.toString().trim()
            val port = editTextPort.text.toString().trim()

            if (!validateInputs(ip, port)) return@setOnClickListener

            logAndToast(getString(R.string.log_checking_connection))
            buttonConnect.isEnabled = false

            checkNodeAvailability(ip, port) { isAvailable ->
                runOnUiThread {
                    buttonConnect.isEnabled = true

                    if (isAvailable) {
                        AppLogger.log(
                            this,
                            getString(R.string.log_connection_success, ip, port)
                        )
                        startActivity(Intent(this, ConnectionActivity::class.java).apply {
                            putExtra("ip", ip)
                            putExtra("port", port)
                        })
                    } else {
                        logAndToast(getString(R.string.connection_failed), long = true)
                    }
                }
            }
        }

        buttonLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    private fun validateInputs(ip: String, port: String): Boolean {
        if (ip.isBlank() || port.isBlank()) {
            logAndToast(getString(R.string.empty_ip_port_error))
            return false
        }

        val ipOrHostRegex = Regex(
            "^(([a-zA-Z0-9][-a-zA-Z0-9]*\\.)+[a-zA-Z]{2,}|localhost|" +
                    "((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}" +
                    "(25[0-5]|2[0-4]\\d|[01]?\\d\\d?))$"
        )

        if (!ipOrHostRegex.matches(ip)) {
            logAndToast(getString(R.string.invalid_ip_format))
            return false
        }

        val portNumber = port.toIntOrNull()
        if (portNumber == null || portNumber !in 1..65535) {
            logAndToast(getString(R.string.invalid_port))
            return false
        }

        return true
    }

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
                callback(false)
            }
        }.start()
    }

    private fun logAndToast(message: String, long: Boolean = false) {
        AppLogger.log(this, message)
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}

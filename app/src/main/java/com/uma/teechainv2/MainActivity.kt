package com.uma.teechainv2

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.http.HttpService
import java.util.concurrent.Executors
import android.util.Log

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var editTextIp: EditText
    private lateinit var editTextPort: EditText
    private lateinit var buttonConnect: Button
    private lateinit var buttonDisconnect: Button
    private lateinit var textViewStatus: TextView
    private lateinit var textViewLog: TextView
    private lateinit var groupConnectionForm: View
    private lateinit var scrollLogs: ScrollView

    // Conexión
    private var web3j: Web3j? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var running = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)



        // Vincular vistas
        editTextIp = findViewById(R.id.editTextIp)
        editTextPort = findViewById(R.id.editTextPort)
        buttonConnect = findViewById(R.id.buttonConnect)
        buttonDisconnect = findViewById(R.id.buttonDisconnect)
        textViewStatus = findViewById(R.id.textViewStatus)
        textViewLog = findViewById(R.id.textViewLog)
        scrollLogs = findViewById(R.id.scrollLogs)
        groupConnectionForm = findViewById(R.id.groupConnectionForm)

        SoftwareTEE.enterTEE {
            val prefs = getSharedPreferences("SecurePrefs", MODE_PRIVATE)
            val existingKey = prefs.getString("encrypted_private_key", null)

            if (existingKey == null) {
                SoftwareTEE.generateAndStoreKey(this)
                runOnUiThread {
                    appendLog("🔐 Nueva clave BLS generada y almacenada de manera segura.")
                }
            } else {
                runOnUiThread {
                    appendLog("🔐 Clave BLS ya existente, no se regenera.")
                }
            }
        }

        // Acciones
        buttonConnect.setOnClickListener {
            val ip = editTextIp.text.toString()
            val port = editTextPort.text.toString()
            if (ip.isNotBlank() && port.isNotBlank()) {
                val url = "http://$ip:$port"
                connectToNode(url)
            } else {
                Toast.makeText(this, "Introduce IP y puerto válidos", Toast.LENGTH_SHORT).show()
            }
        }

        buttonDisconnect.setOnClickListener {
            disconnectFromNode()
        }
    }

    private fun connectToNode(endpoint: String) {
        appendLog("Conectando a $endpoint...")

        executor.execute {
            try {
                web3j = Web3j.build(HttpService(endpoint))
                val version = web3j?.web3ClientVersion()?.send()
                val clientName = version?.web3ClientVersion ?: "desconocido"

                runOnUiThread {
                    groupConnectionForm.visibility = View.GONE
                    textViewStatus.visibility = View.VISIBLE
                    scrollLogs.visibility = View.VISIBLE
                    buttonDisconnect.visibility = View.VISIBLE
                    textViewStatus.text = "✅ Conectado a nodo: $clientName"
                }

                // Poll de bloques
                while (running) {
                    val latestBlock = web3j?.ethGetBlockByNumber(
                        DefaultBlockParameterName.LATEST, false
                    )?.send()

                    val blockNumber = latestBlock?.block?.number
                    appendLog("🧱 Nuevo bloque: $blockNumber")
                    Thread.sleep(10_000)
                }

            } catch (e: Exception) {
                appendLog("❌ Error: ${e.message}")
                runOnUiThread {
                    Log.e("Web3Signer", "Error al conectar", e)

                }
            }
        }
    }

    private fun disconnectFromNode() {
        appendLog("🔌 Desconectando del nodo...")
        running = false
        web3j = null
        executor.shutdownNow()

        // Restaurar vista inicial
        runOnUiThread {
            textViewStatus.visibility = View.GONE
            scrollLogs.visibility = View.GONE
            buttonDisconnect.visibility = View.GONE
            groupConnectionForm.visibility = View.VISIBLE
            textViewLog.text = ""
            running = true
        }
    }

    private fun appendLog(text: String) {
        runOnUiThread {
            textViewLog.append("» $text\n")
            scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
        }
    }
}

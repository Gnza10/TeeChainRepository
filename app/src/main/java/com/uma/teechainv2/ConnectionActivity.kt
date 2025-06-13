package com.uma.teechainv2

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.text.SimpleDateFormat
import java.util.*

class ConnectionActivity : AppCompatActivity() {

    private lateinit var textViewStatus: TextView
    private lateinit var textViewLogBlocks: TextView
    private lateinit var scrollLogsBlocks: ScrollView
    private lateinit var buttonDisconnect: Button

    private var ip: String? = null
    private var port: String? = null
    private var returningFromAuth = false
    private var appInBackground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection)

        ip = intent.getStringExtra("ip")
        port = intent.getStringExtra("port")

        if (ip == null || port == null) {
            finish()
            return
        }

        textViewStatus = findViewById(R.id.textViewStatus)
        textViewLogBlocks = findViewById(R.id.textViewLogBlocks)
        scrollLogsBlocks = findViewById(R.id.scrollLogsBlock)
        buttonDisconnect = findViewById(R.id.buttonDisconnect)

        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver())

        textViewStatus.text = getString(R.string.connection_success_status, ip, port)

        SoftwareTEE.enterTEE {
            SoftwareTEE.generateAndStoreKey(this)
            appendLogBlocks(getString(R.string.bls_key_created))
        }

        ConsensusBlockHandler.start(
            context = this,
            ip = ip!!,
            port = port!!,
            logCallback = { appendLogBlocks(it) }
        )

        buttonDisconnect.setOnClickListener {
            ConsensusBlockHandler.stop()
            appendLogBlocks(getString(R.string.log_desconexion_registrada, getCurrentTime()))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()

        if (returningFromAuth && ip != null && port != null) {
            returningFromAuth = false
            ConsensusBlockHandler.start(
                context = this,
                ip = ip!!,
                port = port!!,
                logCallback = { appendLogBlocks(it) }
            )
        }
    }

    private fun appendLogBlocks(msg: String) {
        runOnUiThread {
            textViewLogBlocks.append("» $msg\n")
            scrollLogsBlocks.post { scrollLogsBlocks.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    inner class AppLifecycleObserver : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            appInBackground = true
        }

        override fun onStart(owner: LifecycleOwner) {
            if (appInBackground && !returningFromAuth && ip != null && port != null) {
                appInBackground = false
                returningFromAuth = true
                val intent = Intent(this@ConnectionActivity, AuthenticationActivity::class.java)
                startActivity(intent)
            } else {
                returningFromAuth = false
            }
        }
    }
}

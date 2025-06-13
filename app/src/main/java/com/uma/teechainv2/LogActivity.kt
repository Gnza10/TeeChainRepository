package com.uma.teechainv2


import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.uma.teechainv2.util.AppLogger


class LogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_log)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val logTextView = findViewById<TextView>(R.id.textViewLogBlocks)
        val scrollView = findViewById<ScrollView>(R.id.scrollLogsBlock)
        val goBackButton = findViewById<Button>(R.id.buttonDisconnect)

        // Leer el archivo de logs
        val logs = AppLogger.readLogs(this)
        logTextView.text = logs

        // Scroll automático al final
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }

        goBackButton.setOnClickListener {
            finish() // Vuelve atrás
        }
    }


}

package com.uma.teechainv2

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class AuthenticationErrorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_authentication_error)

        val buttonSettings = findViewById<Button>(R.id.buttonSettings)
        val buttonRetry = findViewById<Button>(R.id.buttonRetry)

        buttonSettings.setOnClickListener {
            // Abrir configuración de seguridad directamente
            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            startActivity(intent)
        }

        buttonRetry.setOnClickListener {
            // Volver a intentar la autenticación
            val intent = Intent(this, AuthenticationActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}

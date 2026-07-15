package com.uma.teechainv2

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.uma.teechainv2.util.AppLogger
import com.uma.teechainv2.util.ExperimentDataLogger
import java.util.concurrent.Executor

/**
 * Pantalla encargada de gestionar la autenticación biométrica del usuario
 * usando huella, rostro o credenciales del dispositivo.
 */
class AuthenticationActivity : AppCompatActivity() {

    // Executor usado para ejecutar las respuestas del prompt biométrico en el hilo principal
    private lateinit var biometricExecutor: Executor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ExperimentDataLogger.archivePreviousRun(this)
        setContentView(R.layout.activity_authentication)

        // Inicializa el executor principal
        biometricExecutor = ContextCompat.getMainExecutor(this)

        // Verifica si se puede usar biometría; si es así, lanza el prompt
        if (checkBiometricAvailability()) {
            showBiometricPrompt()
        }
    }

    /**
     * Verifica la disponibilidad de autenticación biométrica fuerte o credenciales del dispositivo.
     * Si no está disponible, redirige a la pantalla de error.
     */
    private fun checkBiometricAvailability(): Boolean {
        val biometricManager = BiometricManager.from(this)

        return when (biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {

            BiometricManager.BIOMETRIC_SUCCESS -> true

            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                goToAuthenticationError()
                false
            }

            else -> {
                goToAuthenticationError()
                false
            }
        }
    }

    /**
     * Muestra el prompt de autenticación biométrica al usuario.
     * Maneja los resultados de éxito, fallo y error.
     */
    private fun showBiometricPrompt() {
        val biometricPrompt = BiometricPrompt(this, biometricExecutor, object : BiometricPrompt.AuthenticationCallback() {

            // Si la autenticación es exitosa, redirige al MainActivity
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Toast.makeText(this@AuthenticationActivity, getString(R.string.loginSucces), Toast.LENGTH_SHORT).show()
                goToMainActivity()
            }

            // Si la autenticación falla sin error grave, solo notifica al usuario
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(this@AuthenticationActivity, getString(R.string.loginFailed), Toast.LENGTH_SHORT).show()
                AppLogger.log(this@AuthenticationActivity, getString(R.string.loginFailed))
            }

            // Si ocurre un error (cancelación, hardware, etc.), notifica y cierra
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                val text = getString(R.string.loginError) + errString
                Toast.makeText(this@AuthenticationActivity, text, Toast.LENGTH_LONG).show()
                finish()
            }
        })

        // Configura el mensaje del prompt que verá el usuario
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.login_phrase))
            .setSubtitle(getString(R.string.login_subphrase))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    /**
     * Navega a MainActivity con una animación de transición.
     */
    private fun goToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)

        val options = ActivityOptions.makeCustomAnimation(
            this,
            R.anim.fade_in, // Animación de entrada
            R.anim.fade_out // Animación de salida
        )

        startActivity(intent, options.toBundle())
        finish()
    }

    /**
     * Navega a la pantalla de error si no hay hardware o biometría disponible.
     */
    private fun goToAuthenticationError() {
        val intent = Intent(this, AuthenticationErrorActivity::class.java)

        val options = ActivityOptions.makeCustomAnimation(
            this,
            R.anim.fade_in,
            R.anim.fade_out
        )

        startActivity(intent, options.toBundle())
        finish()
    }
}

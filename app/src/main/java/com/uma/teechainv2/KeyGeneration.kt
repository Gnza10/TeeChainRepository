package com.uma.teechainv2

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.withLock

/**
 * SoftwareTEE simula un entorno de ejecución confiable (TEE) para el manejo seguro
 * de claves privadas en Android, usando AndroidKeyStore y cifrado AES-GCM.
 */
object SoftwareTEE {

    private const val KEY_ALIAS = "TEE_AES_KEY" // Alias usado en el KeyStore
    private const val ANDROID_KEYSTORE = "AndroidKeyStore" // Proveedor seguro de claves
    private const val AES_MODE = "AES/GCM/NoPadding" // Modo de cifrado usado

    private val lock = ReentrantLock() // Control de concurrencia

    /**
     * Entra en una sección crítica de ejecución segura.
     * Utiliza un lock para garantizar exclusión mutua.
     */
    fun enterTEE(onSecureAction: () -> Unit) {
        lock.withLock {
            onSecureAction()
        }
    }

    /**
     * Genera una clave privada BLS aleatoria (32 bytes) y la cifra con AES-GCM,
     * almacenando el resultado cifrado en SharedPreferences.
     */
    fun generateAndStoreKey(context: Context) {
        val secureRandom = SecureRandom()
        val privateKeyBytes = ByteArray(32) // 256 bits para clave BLS
        secureRandom.nextBytes(privateKeyBytes)

        // Cifra la clave generada y la guarda
        val encryptedKey = encryptData(context, privateKeyBytes)
        val prefs = context.getSharedPreferences("SecurePrefs", Context.MODE_PRIVATE)
        prefs.edit {
            putString("encrypted_private_key", encryptedKey)
        }

        // Borra la clave en memoria
        privateKeyBytes.fill(0)
    }

    /**
     * Cifra datos arbitrarios usando una clave simétrica almacenada en AndroidKeyStore.
     * El IV generado aleatoriamente se concatena al principio del resultado.
     */
    private fun encryptData(context: Context, data: ByteArray): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        // Genera clave si no existe
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }

        val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val iv = cipher.iv // Vector de inicialización aleatorio
        val encrypted = cipher.doFinal(data)

        // Retorna IV + datos cifrados en Base64
        return Base64.encodeToString(iv + encrypted, Base64.DEFAULT)
    }

    /**
     * Carga la clave privada almacenada en SharedPreferences y la descifra.
     * Retorna null si no se encuentra ninguna clave.
     */
    fun loadPrivateKey(context: Context): ByteArray? {
        val prefs = context.getSharedPreferences("SecurePrefs", Context.MODE_PRIVATE)
        val encryptedKey = prefs.getString("encrypted_private_key", null) ?: return null
        return decryptData(context, encryptedKey)
    }

    /**
     * Descifra datos cifrados con AES-GCM, extrayendo el IV de los primeros 12 bytes.
     */
    private fun decryptData(context: Context, encrypted: String): ByteArray {
        val decoded = Base64.decode(encrypted, Base64.DEFAULT)
        val iv = decoded.sliceArray(0 until 12)
        val ciphertext = decoded.sliceArray(12 until decoded.size)

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey

        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))

        return cipher.doFinal(ciphertext)
    }
}

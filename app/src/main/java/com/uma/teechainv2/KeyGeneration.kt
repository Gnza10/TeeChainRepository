package com.uma.teechainv2

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom
import androidx.core.content.edit

object SoftwareTEE {

    private const val KEY_ALIAS = "TEE_AES_KEY"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_MODE = "AES/GCM/NoPadding"

    private var inSecureMode = false

    fun enterTEE(onSecureAction: () -> Unit) {
        if (inSecureMode) throw IllegalStateException("Already inside TEE.")
        inSecureMode = true
        try {
            onSecureAction()
        } finally {
            inSecureMode = false
        }
    }

    fun generateAndStoreKey(context: Context) {
        if (!inSecureMode) throw IllegalStateException("Must be inside TEE to generate keys.")

        // 1. Generar clave secreta aleatoria (para BLS privada)
        val secureRandom = SecureRandom()
        val privateKeyBytes = ByteArray(32) // 256 bits para BLS
        secureRandom.nextBytes(privateKeyBytes)

        // 2. Cifrar la clave usando AndroidKeyStore
        val encryptedKey = encryptData(context, privateKeyBytes)

        // 3. Guardar la clave cifrada de manera persistente
        val prefs = context.getSharedPreferences("SecurePrefs", Context.MODE_PRIVATE)
        prefs.edit { putString("encrypted_private_key", encryptedKey) }

        // 4. Limpieza en memoria
        privateKeyBytes.fill(0)
    }

    private fun encryptData(context: Context, data: ByteArray): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

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

        val secretKey = (keyStore.getKey(KEY_ALIAS, null) as SecretKey)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)
        return Base64.encodeToString(iv + encrypted, Base64.DEFAULT)
    }

    fun loadPrivateKey(context: Context): ByteArray? {
        if (!inSecureMode) throw IllegalStateException("Must be inside TEE to load keys.")

        val prefs = context.getSharedPreferences("SecurePrefs", Context.MODE_PRIVATE)
        val encryptedKey = prefs.getString("encrypted_private_key", null) ?: return null
        return decryptData(context, encryptedKey)
    }

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

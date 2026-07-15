package com.uma.teechainv2

import android.content.Context
import com.chiachat.kbls.bls.keys.PrivateKey
import com.chiachat.kbls.bls.schemes.BasicSchemeMPL
import com.uma.teechainv2.util.AppLogger
import com.uma.teechainv2.util.ExperimentDataLogger
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manejador de bloques de consenso: se encarga de obtener el bloque actual de la capa de consenso,
 * firmarlo con la clave BLS privada almacenada en el dispositivo, y enviarlo al nodo local.
 */
object ConsensusBlockHandler {

    private var executor: ExecutorService? = null
    private var running = false
    private var lastSlotProcessed: String? = null

    /**
     * Inicia la rutina de monitoreo de bloques. Si ya está corriendo, no hace nada.
     * @param context Contexto de la app, necesario para logs y acceso a recursos.
     * @param ip Dirección IP del nodo Ethereum.
     * @param port Puerto del nodo Ethereum.
     * @param logCallback Callback para imprimir mensajes de log en la interfaz.
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    fun start(
        context: Context,
        ip: String,
        port: String,
        logCallback: (String) -> Unit
    ) {
        if (running) return
        running = true

        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logBoth(context, context.getString(R.string.log_conexion_registrada, ip, port, timestamp), logCallback)

        val blockUrl = "http://$ip:$port/eth/v2/beacon/blocks/head"
        val postUrl = "http://$ip:$port/eth/v2/beacon/blocks"

        executor = Executors.newSingleThreadExecutor()
        executor!!.execute {
            val client = OkHttpClient()
            logBoth(context, context.getString(R.string.log_connection_attempt, ip, port), logCallback)
            var retryDelay = 4000L
            var activeRequestId: String? = null

            while (running) {
                try {
                    // Obtiene el bloque más reciente
                    var requestArrivalEpochMs = 0L
                    var requestArrivalElapsedRealtimeMs = 0L
                    val blockJson = fetchLatestBlock(client, blockUrl) { epochMs, elapsedRealtimeMs ->
                        requestArrivalEpochMs = epochMs
                        requestArrivalElapsedRealtimeMs = elapsedRealtimeMs
                    }

                    if (blockJson == null) {
                        // Reintenta con backoff progresivo
                        logBoth(context, context.getString(R.string.log_reconnection_delay, retryDelay / 1000), logCallback)
                        retryDelay = (retryDelay * 1.5).toLong().coerceAtMost(60000L)
                        continue
                    }

                    retryDelay = 4000L

                    val dataObject = blockJson.getJSONObject("data")
                    val messageJson = dataObject.getJSONObject("message")
                    val slot = messageJson.optString("slot", "unknown")

                    // Ignora bloques ya procesados
                    if (!shouldProcessSlot(slot)) {
                        Thread.sleep(4000)
                        continue
                    }

                    lastSlotProcessed = slot
                    val requestId = ExperimentDataLogger.startRequest(
                        slot = slot,
                        arrivedAtEpochMs = requestArrivalEpochMs,
                        arrivedAtElapsedRealtimeMs = requestArrivalElapsedRealtimeMs
                    )
                    activeRequestId = requestId
                    ExperimentDataLogger.recordValidationFinished(requestId)

                    // Entra al enclave seguro para firmar el bloque
                    SoftwareTEE.enterTEE {
                        var signingErrorType = "SIGNING_FAILED"
                        var signingErrorDetail: String? = null
                        ExperimentDataLogger.recordSigningStarted(requestId)
                        var signatureGenerated = false
                        val signedBlock = try {
                            signBlock(
                                context = context,
                                messageJson = messageJson,
                                slot = slot,
                                logCallback = logCallback,
                                onSigningError = { errorType, errorDetail ->
                                    signingErrorType = errorType
                                    signingErrorDetail = errorDetail
                                }
                            ).also {
                                signatureGenerated = it != null
                            }
                        } finally {
                            ExperimentDataLogger.recordSigningFinished(
                                requestId = requestId,
                                signatureGenerated = signatureGenerated
                            )
                        }
                        if (signedBlock != null) {
                            sendSignedBlockWithRetries(
                                context,
                                client,
                                postUrl,
                                signedBlock,
                                slot,
                                logCallback,
                                requestId
                            )
                        } else {
                            ExperimentDataLogger.finishRequestFailure(
                                requestId = requestId,
                                errorType = signingErrorType,
                                errorDetail = signingErrorDetail
                            )
                        }
                    }
                    activeRequestId = null

                    Thread.sleep(1000)

                } catch (e: InterruptedException) {
                    activeRequestId?.let {
                        ExperimentDataLogger.finishRequestFailure(
                            it,
                            "PROCESSING_INTERRUPTED",
                            e.message
                        )
                    }
                    activeRequestId = null
                    logBoth(context, context.getString(R.string.log_thread_interrupted), logCallback)
                    return@execute
                } catch (e: IOException) {
                    activeRequestId?.let {
                        ExperimentDataLogger.finishRequestFailure(
                            it,
                            "NETWORK_IO_EXCEPTION",
                            e.message
                        )
                    }
                    activeRequestId = null
                    ExperimentDataLogger.recordConnectionUnavailable(
                        e.javaClass.simpleName,
                        e.message
                    )
                    logBoth(context, context.getString(R.string.log_temporary_network_exception, e.message ?: ""), logCallback)
                    logBoth(context, context.getString(R.string.log_attempting_reconnect), logCallback)
                    Thread.sleep(retryDelay)
                    retryDelay = (retryDelay * 1.5).toLong().coerceAtMost(60000L)
                } catch (e: Exception) {
                    activeRequestId?.let {
                        ExperimentDataLogger.finishRequestFailure(
                            it,
                            e.javaClass.simpleName,
                            e.message
                        )
                    }
                    activeRequestId = null
                    logBoth(context, context.getString(R.string.log_exception, e.message ?: ""), logCallback)
                    Thread.sleep(4000)
                }
            }
        }
    }

    /**
     * Detiene el proceso de monitoreo y firma.
     */
    fun stop() {
        running = false
        executor?.shutdownNow()
        executor = null
    }

    /**
     * Realiza una petición HTTP para obtener el bloque más reciente de la capa de consenso.
     */
    private fun fetchLatestBlock(
        client: OkHttpClient,
        url: String,
        onResponseBodyReceived: (Long, Long) -> Unit
    ): JSONObject? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                ExperimentDataLogger.recordConnectionAvailable()
                if (!response.isSuccessful) {
                    ExperimentDataLogger.recordNodeResponseError(
                        operation = "fetch_latest_block",
                        errorType = "HTTP_${response.code}",
                        errorDetail = response.message
                    )
                    return null
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    ExperimentDataLogger.recordNodeResponseError(
                        operation = "fetch_latest_block",
                        errorType = "EMPTY_RESPONSE_BODY",
                        errorDetail = null
                    )
                    return null
                }
                onResponseBodyReceived(
                    System.currentTimeMillis(),
                    android.os.SystemClock.elapsedRealtime()
                )
                JSONObject(body)
            }
        } catch (e: Exception) {
            if (e is IOException) {
                ExperimentDataLogger.recordConnectionUnavailable(
                    e.javaClass.simpleName,
                    e.message
                )
            } else {
                ExperimentDataLogger.recordNodeResponseError(
                    operation = "fetch_latest_block",
                    errorType = e.javaClass.simpleName,
                    errorDetail = e.message
                )
            }
            null
        }
    }

    /**
     * Determina si el slot debe procesarse (evita procesar el mismo bloque más de una vez).
     */
    private fun shouldProcessSlot(slot: String?): Boolean {
        return slot != null && slot != lastSlotProcessed
    }

    /**
     * Firma el bloque usando la clave BLS almacenada de forma segura.
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    private fun signBlock(
        context: Context,
        messageJson: JSONObject,
        slot: String,
        logCallback: (String) -> Unit,
        onSigningError: (String, String?) -> Unit
    ): JSONObject? {
        val keyBytes = SoftwareTEE.loadPrivateKey(context)
        if (keyBytes == null) {
            onSigningError("PRIVATE_KEY_NOT_FOUND", null)
            logBoth(context, context.getString(R.string.log_no_private_key), logCallback)
            return null
        }

        return try {
            val privateKey = PrivateKey.fromBytes(keyBytes.toUByteArray())
            val rootBytes = messageJson.toString().toByteArray()

            logBoth(context, context.getString(R.string.log_firmando_bloque, slot), logCallback)

            val signature = BasicSchemeMPL.sign(privateKey, rootBytes.toUByteArray())
            val hexSig = signature.toHex()

            val timestamp = System.currentTimeMillis()
            val hash = messageJson.toString().hashCode()

            JSONObject().apply {
                put("message", messageJson)
                put("signature", hexSig)
                put("timestamp", timestamp)
                put("hash", hash)
            }

        } catch (e: Exception) {
            onSigningError(e.javaClass.simpleName, e.message)
            logBoth(context, context.getString(R.string.log_firma_bloque_fallida, slot, e.message ?: ""), logCallback)
            null
        }
    }

    /**
     * Envía el bloque firmado al nodo con reintentos en caso de fallo.
     * También verifica que el bloque no esté caducado ni modificado.
     */
    private fun sendSignedBlockWithRetries(
        context: Context,
        client: OkHttpClient,
        postUrl: String,
        signedBlockJson: JSONObject,
        slot: String,
        logCallback: (String) -> Unit,
        requestId: String
    ) {
        val currentTime = System.currentTimeMillis()
        val ttl = 20_000L

        // Verifica que el bloque no esté caducado
        if (currentTime - signedBlockJson.getLong("timestamp") > ttl) {
            logBoth(context, context.getString(R.string.log_signed_block_skipped), logCallback)
            ExperimentDataLogger.finishRequestFailure(
                requestId,
                "SIGNED_BLOCK_EXPIRED",
                "The signed block exceeded the configured TTL"
            )
            return
        }

        // Verifica integridad del bloque antes de enviarlo
        val expectedHash = signedBlockJson.getInt("hash")
        val actualHash = signedBlockJson.getJSONObject("message").toString().hashCode()
        if (expectedHash != actualHash) {
            logBoth(context, context.getString(R.string.log_integrity_failed), logCallback)
            ExperimentDataLogger.finishRequestFailure(
                requestId,
                "INTEGRITY_CHECK_FAILED",
                null
            )
            return
        }

        val signedBlockOnly = JSONObject().apply {
            put("message", signedBlockJson.getJSONObject("message"))
            put("signature", signedBlockJson.getString("signature"))
        }

        val publishPayload = JSONObject().apply {
            put("signed_block", signedBlockOnly)
            put("kzg_proofs", org.json.JSONArray())
            put("blobs", org.json.JSONArray())
        }

        val mediaType = "application/json".toMediaType()
        val requestBody = publishPayload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(postUrl)
            .addHeader("Accept", "application/json")
            .post(requestBody)
            .build()

        val maxAttempts = 3
        var attempt = 0
        var success = false
        var lastErrorType: String? = null
        var lastErrorDetail: String? = null

        // Reintenta el envío del bloque firmado hasta 3 veces
        while (!success && attempt < maxAttempts) {
            attempt++
            ExperimentDataLogger.recordResponseSendStarted(requestId, attempt)

            try {
                client.newCall(request).execute().use { response ->
                    ExperimentDataLogger.recordConnectionAvailable()
                    ExperimentDataLogger.recordResponseReceived(
                        requestId = requestId,
                        attempt = attempt,
                        httpStatus = response.code,
                        successful = response.isSuccessful
                    )
                    if (response.isSuccessful) {
                        logBoth(context, context.getString(R.string.log_block_signed, slot), logCallback)
                        success = true
                        ExperimentDataLogger.finishRequestSuccess(requestId)
                    } else {
                        val responseBody = response.body?.string()
                        lastErrorType = "HTTP_${response.code}"
                        lastErrorDetail = responseBody
                        ExperimentDataLogger.recordRequestAttemptError(
                            requestId,
                            attempt,
                            lastErrorType!!,
                            lastErrorDetail
                        )
                        logBoth(context, context.getString(
                            R.string.log_block_send_error,
                            response.code,
                            responseBody
                        ), logCallback)
                    }
                }
            } catch (e: Exception) {
                lastErrorType = e.javaClass.simpleName
                lastErrorDetail = e.message
                ExperimentDataLogger.recordRequestAttemptError(
                    requestId,
                    attempt,
                    lastErrorType!!,
                    lastErrorDetail
                )
                if (e is IOException) {
                    ExperimentDataLogger.recordConnectionUnavailable(
                        e.javaClass.simpleName,
                        e.message
                    )
                }
                logBoth(context, context.getString(R.string.log_block_post_exception, attempt, e.message ?: ""), logCallback)
            }

            if (!success && attempt < maxAttempts) {
                ExperimentDataLogger.recordRetry(
                    requestId = requestId,
                    nextAttempt = attempt + 1,
                    reason = lastErrorType
                )
                logBoth(context, context.getString(R.string.log_retrying_block_post, attempt), logCallback)
                Thread.sleep(2000)
            }
        }

        if (!success) {
            logBoth(context, context.getString(R.string.log_max_retries_failed, maxAttempts), logCallback)
            ExperimentDataLogger.finishRequestFailure(
                requestId = requestId,
                errorType = lastErrorType ?: "MAX_SEND_ATTEMPTS_REACHED",
                errorDetail = lastErrorDetail
            )
        }
    }

    /**
     * Registra el mensaje en el log interno de la app y lo reenvía a la interfaz de usuario.
     */
    fun logBoth(context: Context, message: String, logCallback: (String) -> Unit) {
        logCallback(message)
        AppLogger.log(context, message)
    }
}

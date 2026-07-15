package com.uma.teechainv2.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Registra datos crudos de una sesión de conexión sin intervenir en su lógica.
 *
 * El archivo usa JSON Lines: cada línea es un objeto JSON independiente.
 */
object ExperimentDataLogger {

    private const val DATA_FILENAME = "datos.txt"
    private const val BACKUP_FILENAME = "CopiaSeguridad.txt"
    private const val TAG = "ExperimentDataLogger"
    private const val SCHEMA_VERSION = 1

    private data class Moment(
        val epochMs: Long,
        val elapsedRealtimeMs: Long
    )

    private data class BatterySnapshot(
        val percentage: Double?,
        val charging: Boolean?
    )

    private data class RequestMetrics(
        val requestId: String,
        val slot: String,
        val arrivedAt: Moment,
        var validationFinishedAt: Moment? = null,
        var signingStartedAt: Moment? = null,
        var signingFinishedAt: Moment? = null,
        var firstResponseSentAt: Moment? = null,
        var lastResponseSentAt: Moment? = null,
        var retryCount: Int = 0,
        var signatureGenerated: Boolean = false
    )

    private var dataFile: File? = null
    private var writer: ExecutorService? = null
    private var startupRotationDone = false
    private var active = false
    private var sessionId = ""
    private var sessionStartedAt: Moment? = null
    private var requestSequence = 0
    private var totalRequests = 0
    private var successfulSignatures = 0
    private var successfulRequests = 0
    private var failedRequests = 0
    private var totalRetries = 0
    private var totalReconnections = 0
    private var totalConnectionDrops = 0
    private var totalNodeResponseErrors = 0
    private var connectionAvailable = true
    private var emulator = false
    private var initialBattery: BatterySnapshot? = null
    private val requests = LinkedHashMap<String, RequestMetrics>()

    /**
     * Conserva los datos del arranque anterior y prepara datos.txt para la nueva ejecución.
     * La guarda evita repetir la rotación cuando se abre AuthenticationActivity para reautenticar.
     */
    @Synchronized
    fun archivePreviousRun(context: Context) {
        if (startupRotationDone) return
        startupRotationDone = true

        val previousDataFile = File(context.filesDir, DATA_FILENAME)
        if (!previousDataFile.exists() || previousDataFile.length() == 0L) return

        val backupFile = File(context.filesDir, BACKUP_FILENAME)
        val previousBackupLength = if (backupFile.exists()) backupFile.length() else 0L
        try {
            previousDataFile.inputStream().use { input ->
                FileOutputStream(backupFile, true).buffered().use { output ->
                    input.copyTo(output)
                }
            }
            previousDataFile.writeText("", Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error archiving $DATA_FILENAME", e)
            try {
                if (backupFile.exists()) {
                    RandomAccessFile(backupFile, "rw").use {
                        it.setLength(previousBackupLength)
                    }
                }
            } catch (rollbackError: Exception) {
                Log.e(TAG, "Error rolling back $BACKUP_FILENAME", rollbackError)
            }
        }
    }

    @Synchronized
    fun startSession(context: Context, ip: String, port: String) {
        val start = now()
        dataFile = File(context.filesDir, DATA_FILENAME)
        writer = Executors.newSingleThreadExecutor()
        active = true
        sessionId = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(start.epochMs))
        sessionStartedAt = start
        requestSequence = 0
        totalRequests = 0
        successfulSignatures = 0
        successfulRequests = 0
        failedRequests = 0
        totalRetries = 0
        totalReconnections = 0
        totalConnectionDrops = 0
        totalNodeResponseErrors = 0
        connectionAvailable = true
        requests.clear()

        emulator = isProbablyEmulator()
        initialBattery = if (emulator) null else readBattery(context)

        val record = baseRecord("session_start", start, includeResourceSnapshot = true).apply {
            put("node_ip", ip)
            put("node_port", port)
            put("device_manufacturer", Build.MANUFACTURER)
            put("device_model", Build.MODEL)
            put("device_product", Build.PRODUCT)
            put("environment", if (emulator) "android_emulator" else "physical_device")
            put("android_version", Build.VERSION.RELEASE)
            put("android_sdk", Build.VERSION.SDK_INT)
            put("backend", "OP-TEE")
            put(
                "backend_detail",
                "Android Keystore protects the AES wrapping key; BLS signing runs in the app process inside the SoftwareTEE critical section"
            )
            put(
                "validation_scope",
                "JSON response parsing and new-slot check; the app performs no independent protocol or signature validation"
            )
            put("cpu_metric", "process_elapsed_cpu_time_ms (cumulative)")
            put("memory_metric", "process_pss_kb")
            put("resource_sampling", "session_start, request_result and session_summary records")
            put(
                "temperature_measurement",
                "omitted: Android has no reliable standard app API for CPU temperature and emulator values are not physical measurements"
            )
            if (emulator) {
                put("battery_measurement", "omitted: emulator battery and charging values are simulated and cannot measure application energy consumption")
            } else if (initialBattery != null) {
                put("battery_measurement", "collected from ACTION_BATTERY_CHANGED")
                putNullable("initial_battery_percent", initialBattery?.percentage)
                putNullable("initial_battery_charging", initialBattery?.charging)
            } else {
                put("battery_measurement", "omitted: battery state unavailable")
            }
        }
        write(record)
    }

    @Synchronized
    fun startRequest(
        slot: String,
        arrivedAtEpochMs: Long,
        arrivedAtElapsedRealtimeMs: Long
    ): String {
        if (!active) return ""

        requestSequence++
        totalRequests++
        val requestId = "$sessionId-R${requestSequence.toString().padStart(6, '0')}"
        val arrivedAt = Moment(arrivedAtEpochMs, arrivedAtElapsedRealtimeMs)
        requests[requestId] = RequestMetrics(
            requestId = requestId,
            slot = slot,
            arrivedAt = arrivedAt
        )

        write(requestEvent(requestId, "request_arrived", arrivedAt))
        return requestId
    }

    @Synchronized
    fun recordValidationFinished(requestId: String) {
        val request = requests[requestId] ?: return
        val moment = now()
        request.validationFinishedAt = moment
        write(requestEvent(requestId, "validation_finished", moment))
    }

    @Synchronized
    fun recordSigningStarted(requestId: String) {
        val request = requests[requestId] ?: return
        val moment = now()
        request.signingStartedAt = moment
        write(requestEvent(requestId, "signing_started", moment))
    }

    @Synchronized
    fun recordSigningFinished(requestId: String, signatureGenerated: Boolean) {
        val request = requests[requestId] ?: return
        val moment = now()
        request.signingFinishedAt = moment
        if (signatureGenerated && !request.signatureGenerated) {
            request.signatureGenerated = true
            successfulSignatures++
        }
        write(requestEvent(requestId, "signing_finished", moment).apply {
            put("signature_generated", signatureGenerated)
        })
    }

    @Synchronized
    fun recordResponseSendStarted(requestId: String, attempt: Int) {
        val request = requests[requestId] ?: return
        val moment = now()
        if (request.firstResponseSentAt == null) {
            request.firstResponseSentAt = moment
        }
        request.lastResponseSentAt = moment
        write(requestEvent(requestId, "response_send_started", moment).apply {
            put("attempt", attempt)
        })
    }

    @Synchronized
    fun recordResponseReceived(
        requestId: String,
        attempt: Int,
        httpStatus: Int,
        successful: Boolean
    ) {
        if (!requests.containsKey(requestId)) return
        write(requestEvent(requestId, "response_received", now()).apply {
            put("attempt", attempt)
            put("http_status", httpStatus)
            put("successful", successful)
        })
    }

    @Synchronized
    fun recordRequestAttemptError(
        requestId: String,
        attempt: Int,
        errorType: String,
        errorDetail: String?
    ) {
        if (!requests.containsKey(requestId)) return
        write(requestEvent(requestId, "response_attempt_failed", now()).apply {
            put("attempt", attempt)
            put("error_type", errorType)
            putNullable("error_detail", errorDetail)
        })
    }

    @Synchronized
    fun recordRetry(requestId: String, nextAttempt: Int, reason: String?) {
        val request = requests[requestId] ?: return
        request.retryCount++
        totalRetries++
        write(requestEvent(requestId, "request_retry", now()).apply {
            put("retry_number", request.retryCount)
            put("next_attempt", nextAttempt)
            putNullable("reason", reason)
        })
    }

    @Synchronized
    fun finishRequestSuccess(requestId: String) {
        finishRequest(requestId, "success", null, null, now())
    }

    @Synchronized
    fun finishRequestFailure(
        requestId: String,
        errorType: String,
        errorDetail: String? = null
    ) {
        finishRequest(requestId, "failure", errorType, errorDetail, now())
    }

    @Synchronized
    fun recordConnectionUnavailable(errorType: String, errorDetail: String?) {
        if (!active) return

        val event = if (connectionAvailable) {
            connectionAvailable = false
            totalConnectionDrops++
            "connection_drop"
        } else {
            "connection_still_unavailable"
        }

        write(baseRecord("connection_event", now()).apply {
            put("event", event)
            put("error_type", errorType)
            putNullable("error_detail", errorDetail)
            put("connection_available", false)
        })
    }

    @Synchronized
    fun recordConnectionAvailable() {
        if (!active || connectionAvailable) return

        connectionAvailable = true
        totalReconnections++
        write(baseRecord("connection_event", now()).apply {
            put("event", "reconnection")
            put("connection_available", true)
            put("reconnection_number", totalReconnections)
        })
    }

    @Synchronized
    fun recordNodeResponseError(operation: String, errorType: String, errorDetail: String?) {
        if (!active) return
        totalNodeResponseErrors++
        write(baseRecord("node_response_error", now()).apply {
            put("operation", operation)
            put("error_type", errorType)
            putNullable("error_detail", errorDetail)
            put("connection_available", connectionAvailable)
        })
    }

    @Synchronized
    fun finishSession(context: Context) {
        if (!active) return

        val end = now()
        requests.keys.toList().forEach { requestId ->
            finishRequest(
                requestId = requestId,
                status = "failure",
                errorType = "SESSION_DISCONNECTED",
                errorDetail = "The session ended while the request was still in progress",
                finishedAt = end
            )
        }

        val finalBattery = if (emulator) null else readBattery(context)
        val start = sessionStartedAt
        write(baseRecord("session_summary", end, includeResourceSnapshot = true).apply {
            put("session_finished_at_epoch_ms", end.epochMs)
            put("session_finished_at_iso", formatTimestamp(end.epochMs))
            put("real_duration_ms", if (start == null) 0 else end.elapsedRealtimeMs - start.elapsedRealtimeMs)
            put("total_requests", totalRequests)
            put("total_successful_requests", successfulRequests)
            put("total_successful_signatures", successfulSignatures)
            put(
                "successful_signatures_definition",
                "BasicSchemeMPL.sign returned without exception; no independent cryptographic verification is performed"
            )
            put("total_failures", failedRequests)
            put("total_retries", totalRetries)
            put("total_reconnections", totalReconnections)
            put("total_connection_drops", totalConnectionDrops)
            put("total_node_response_errors", totalNodeResponseErrors)
            put(
                "incident_records",
                "See failed request_result, connection_event and node_response_error records in this session"
            )
            if (emulator) {
                put("battery_measurement", "omitted: emulator values are simulated")
            } else {
                putNullable("initial_battery_percent", initialBattery?.percentage)
                putNullable("final_battery_percent", finalBattery?.percentage)
                putNullable("initial_battery_charging", initialBattery?.charging)
                putNullable("final_battery_charging", finalBattery?.charging)
            }
        })

        active = false
        requests.clear()
        initialBattery = null
        sessionStartedAt = null
        writer?.shutdown()
        try {
            writer?.awaitTermination(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        writer = null
        dataFile = null
    }

    private fun finishRequest(
        requestId: String,
        status: String,
        errorType: String?,
        errorDetail: String?,
        finishedAt: Moment
    ) {
        val request = requests.remove(requestId) ?: return
        if (status == "success") {
            successfulRequests++
        } else {
            failedRequests++
        }

        write(baseRecord("request_result", finishedAt, includeResourceSnapshot = true).apply {
            put("request_id", request.requestId)
            put("slot", request.slot)
            putMoment("arrived_at", request.arrivedAt)
            putMoment("validation_finished_at", request.validationFinishedAt)
            putMoment("signing_started_at", request.signingStartedAt)
            putMoment("signing_finished_at", request.signingFinishedAt)
            putMoment("first_response_sent_at", request.firstResponseSentAt)
            putMoment("last_response_sent_at", request.lastResponseSentAt)
            put("final_status", status)
            putNullable("error_type", errorType)
            putNullable("error_detail", errorDetail)
            put("retry_count", request.retryCount)
            put("signature_generated", request.signatureGenerated)
        })
    }

    private fun requestEvent(requestId: String, event: String, moment: Moment): JSONObject {
        val request = requests[requestId]
        return baseRecord("request_event", moment).apply {
            put("request_id", requestId)
            putNullable("slot", request?.slot)
            put("event", event)
            put("connection_available", connectionAvailable)
        }
    }

    private fun baseRecord(
        recordType: String,
        moment: Moment,
        includeResourceSnapshot: Boolean = false
    ): JSONObject {
        val start = sessionStartedAt
        return JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("record_type", recordType)
            put("session_id", sessionId)
            put("timestamp_epoch_ms", moment.epochMs)
            put("timestamp_iso", formatTimestamp(moment.epochMs))
            put("elapsed_realtime_ms", moment.elapsedRealtimeMs)
            put(
                "session_elapsed_ms",
                if (start == null) 0 else moment.elapsedRealtimeMs - start.elapsedRealtimeMs
            )
            if (includeResourceSnapshot) {
                put("process_elapsed_cpu_time_ms", android.os.Process.getElapsedCpuTime())
                put("process_pss_kb", Debug.getPss())
            }
        }
    }

    private fun JSONObject.putMoment(prefix: String, moment: Moment?) {
        putNullable("${prefix}_epoch_ms", moment?.epochMs)
        putNullable("${prefix}_iso", moment?.let { formatTimestamp(it.epochMs) })
        putNullable("${prefix}_elapsed_realtime_ms", moment?.elapsedRealtimeMs)
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun write(record: JSONObject) {
        val file = dataFile ?: return
        val line = record.toString() + "\n"
        try {
            writer?.execute {
                try {
                    file.appendText(line, Charsets.UTF_8)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing $DATA_FILENAME", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling write to $DATA_FILENAME", e)
        }
    }

    private fun readBattery(context: Context): BatterySnapshot? {
        return try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val percentage = if (level >= 0 && scale > 0) {
                level * 100.0 / scale
            } else {
                null
            }
            val charging = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL -> true
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                else -> null
            }
            BatterySnapshot(percentage, charging)
        } catch (e: Exception) {
            Log.w(TAG, "Battery state unavailable", e)
            null
        }
    }

    private fun isProbablyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase(Locale.US)
        val model = Build.MODEL.lowercase(Locale.US)
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.US)
        val brand = Build.BRAND.lowercase(Locale.US)
        val device = Build.DEVICE.lowercase(Locale.US)
        val product = Build.PRODUCT.lowercase(Locale.US)

        return fingerprint.startsWith("generic") ||
                fingerprint.contains("emulator") ||
                fingerprint.contains("unknown") ||
                model.contains("google_sdk") ||
                model.contains("emulator") ||
                model.contains("android sdk built for") ||
                manufacturer.contains("genymotion") ||
                (brand.startsWith("generic") && device.startsWith("generic")) ||
                product.contains("sdk_gphone") ||
                product.contains("emulator") ||
                product.contains("simulator")
    }

    private fun now(): Moment {
        return Moment(
            epochMs = System.currentTimeMillis(),
            elapsedRealtimeMs = SystemClock.elapsedRealtime()
        )
    }

    private fun formatTimestamp(epochMs: Long): String {
        return SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            Locale.US
        ).format(Date(epochMs))
    }
}

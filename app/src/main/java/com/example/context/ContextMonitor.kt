package com.example.context

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.*

object ContextMonitor {

    fun getBatteryLevel(context: Context): Int {
        val batteryStatus: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            50
        }
    }

    fun isCharging(context: Context): Boolean {
        val batteryStatus: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun getNetworkStatus(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "Unknown network state"
        val activeNetwork = connectivityManager.activeNetwork ?: return "No active internet connection (Offline)"
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return "Disconnected"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Connected to Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Connected to Mobile Data"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Connected via Ethernet"
            else -> "Connected (Other source)"
        }
    }

    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("EEEE, h:mm a (z)", Locale.getDefault())
        return sdf.format(Date())
    }

    fun getLocationPermissionStatus(context: Context): String {
        val fineLocation = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        return if (fineLocation == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "Granted"
        } else {
            "Denied (Dynamic tool mapping requires ACCESS_FINE_LOCATION)"
        }
    }

    fun getDeviceStatusReport(context: Context): String {
        return """
            [Current Device State Report]
            - Time: ${getFormattedTime()}
            - Battery: ${getBatteryLevel(context)}% (Charging: ${isCharging(context)})
            - Network: ${getNetworkStatus(context)}
            - GPS Permission: ${getLocationPermissionStatus(context)}
        """.trimIndent()
    }
}

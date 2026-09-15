package dk.lasse.karatecliprecorder.training

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.StatFs

data class ResourceSnapshot(val batteryPercent: Int, val charging: Boolean, val freeBytes: Long)
object ProcessingPolicy {
    const val HARD_STORAGE_BYTES = 256L * 1024 * 1024
    const val WARN_STORAGE_BYTES = 1024L * 1024 * 1024
    fun mayStart(battery: ResourceSnapshot, threshold: Int) = battery.charging || battery.batteryPercent >= threshold.coerceAtLeast(5)
    fun recordingBlock(resources: ResourceSnapshot, threshold: Int): String? = when {
        !mayStart(resources, threshold) -> "Connect a charger: battery is below $threshold%."
        resources.freeBytes < HARD_STORAGE_BYTES -> "Not enough safe storage to start a recording. Free some space."
        else -> null
    }
    fun mayProcess(background: Boolean, foreground: Boolean, manual: Boolean) = background || foreground && manual
    fun snapshot(context: Context): ResourceSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return ResourceSnapshot(if (level >= 0 && scale > 0) level * 100 / scale else 0,
            (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0, StatFs(context.filesDir.path).availableBytes)
    }
}

class ProcessingPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("processing_policy", Context.MODE_PRIVATE)
    var background: Boolean
        get() = prefs.getBoolean("background", true)
        set(value) { prefs.edit().putBoolean("background", value).apply() }
    var minimumBattery: Int
        get() = prefs.getInt("minimum_battery", 20).coerceIn(5, 100)
        set(value) { prefs.edit().putInt("minimum_battery", value.coerceIn(5, 100)).apply() }
}

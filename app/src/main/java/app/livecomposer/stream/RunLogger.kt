package app.livecomposer.stream

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 运行指标采样：内存、温度、热状态。用于状态栏显示与长稳测试日志。 */
class DeviceMetrics(private val context: Context) {
    data class Sample(val pssMb: Int, val javaMb: Int, val batteryTempC: Float, val thermal: String)

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun sample(): Sample {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        val rt = Runtime.getRuntime()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val thermal = when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "正常"
            PowerManager.THERMAL_STATUS_LIGHT -> "轻微"
            PowerManager.THERMAL_STATUS_MODERATE -> "中等"
            PowerManager.THERMAL_STATUS_SEVERE -> "严重"
            PowerManager.THERMAL_STATUS_CRITICAL -> "危急"
            PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "即将关机"
            else -> "未知"
        }
        return Sample(mi.totalPss / 1024, ((rt.totalMemory() - rt.freeMemory()) / 1048576).toInt(), temp, thermal)
    }
}

/**
 * 直播期间每 5 秒记录一行 CSV（时长、帧率、码率、丢帧、重连、网页采集、内存、温度）。
 * 停止推流后复制到 下载/LiveComposer/ 目录，便于填写真机测试报告。
 */
class RunLogger(private val context: Context) {
    private var file: File? = null
    private var lines = 0

    fun begin(header: String) {
        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val name = "run-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv"
        file = File(dir, name).also {
            it.writeText("# $header\n" +
                "time,elapsed_s,state,out_fps,kbps,dropped,render_late,reconnects,web_capture_ms,web_fps,pss_mb,java_mb,battery_c,thermal\n")
        }
        lines = 0
    }

    fun append(row: List<Any>) {
        val f = file ?: return
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        f.appendText(t + "," + row.joinToString(",") + "\n")
        lines++
    }

    /** 结束并导出到“下载”目录，返回导出后的显示路径。 */
    fun finish(): String? {
        val f = file ?: return null
        file = null
        if (lines == 0) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LiveComposer")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return f.absolutePath
            context.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            "下载/LiveComposer/${f.name}"
        } catch (e: Exception) {
            Log.w("RunLogger", "export", e)
            f.absolutePath
        }
    }
}

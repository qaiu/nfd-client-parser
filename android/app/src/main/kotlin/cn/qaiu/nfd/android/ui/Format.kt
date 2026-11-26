package cn.qaiu.nfd.android.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble()
    val units = arrayOf("KB", "MB", "GB", "TB")
    var unit = -1
    do {
        value /= 1024.0
        unit++
    } while (value >= 1024 && unit < units.size - 1)
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

fun formatTime(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))

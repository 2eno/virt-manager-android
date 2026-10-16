package dev.virtmanager.android.ui.components

import java.util.Locale

object Format {
    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB")
        var v = bytes.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun kib(kib: Long?): String = kib?.let { bytes(it * 1024) } ?: "—"

    fun rate(bytesPerSec: Double): String = bytes(bytesPerSec.toLong()) + "/s"

    fun percent(p: Double): String = String.format(Locale.US, "%.0f%%", p)
}

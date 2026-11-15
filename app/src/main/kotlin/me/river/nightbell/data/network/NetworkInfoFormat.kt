package me.river.nightbell.data.network

import java.util.Locale

/** Byte counts are shown with binary prefixes but short, locale-free labels. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "-"
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (value >= 100.0) String.format(Locale.US, "%.0f %s", value, units[unit])
    else String.format(Locale.US, "%.1f %s", value, units[unit])
}

/** A rate is a byte count per second, so it goes through [formatBytes]. */
fun formatRate(bytesPerSecond: Long): String = formatBytes(bytesPerSecond.coerceAtLeast(0)) + "/s"

/**
 * Wi-Fi channel numbers follow three different formulas. 2.4 GHz channels are
 * 5 MHz apart starting at 2412, 5 GHz are 4-digit multiples of 5, and 6 GHz
 * starts at 5955. A frequency outside those bands has no meaningful channel.
 */
fun wifiChannel(frequencyMhz: Int): Int? = when {
    frequencyMhz in 2412..2472 && (frequencyMhz - 2407) % 5 == 0 -> (frequencyMhz - 2407) / 5
    frequencyMhz == 2484 -> 14
    frequencyMhz in 5000..5895 && frequencyMhz % 5 == 0 -> (frequencyMhz - 5000) / 5
    frequencyMhz in 5955..7115 && frequencyMhz % 5 == 0 -> (frequencyMhz - 5950) / 5
    else -> null
}

fun wifiBand(frequencyMhz: Int): String = when {
    frequencyMhz in 2400..2500 -> "2.4 GHz"
    frequencyMhz in 5000..5900 -> "5 GHz"
    frequencyMhz in 5900..7200 -> "6 GHz"
    frequencyMhz > 0 -> "${frequencyMhz} MHz"
    else -> "-"
}

/** WifiInfo.getWifiStandard() constants, mapped to the names people recognise. */
fun wifiStandardLabel(standard: Int): String = when (standard) {
    4 -> "802.11n / Wi-Fi 4"
    5 -> "802.11ac / Wi-Fi 5"
    6 -> "802.11ax / Wi-Fi 6"
    7 -> "802.11ax / Wi-Fi 6E"
    8 -> "802.11be / Wi-Fi 7"
    1 -> "802.11a/b/g"
    else -> "-"
}

/** TelephonyManager.getDataNetworkType() constants. */
fun dataNetworkTypeLabel(type: Int): String = when (type) {
    1 -> "GPRS"
    2 -> "EDGE"
    3 -> "UMTS"
    4 -> "CDMA"
    5 -> "EVDO 0"
    6 -> "EVDO A"
    7 -> "1xRTT"
    8 -> "HSDPA"
    9 -> "HSUPA"
    10 -> "HSPA"
    11 -> "iDEN"
    12 -> "EVDO B"
    13 -> "LTE"
    14 -> "eHRPD"
    15 -> "HSPA+"
    16 -> "GSM"
    17 -> "TD-SCDMA"
    18 -> "IWLAN"
    19 -> "LTE-CA"
    20 -> "5G NR"
    0 -> "Unknown"
    else -> "-"
}

/** Keeps a reported signal level inside the bar range the UI can draw. */
fun signalBars(level: Int, maxBars: Int): Int = level.coerceIn(0, maxBars)

/** RSSI in dBm -> a 0..4 grade, used when calculateSignalLevel is unavailable. */
fun rssiGrade(rssi: Int): Int = when {
    rssi >= -55 -> 4
    rssi >= -67 -> 3
    rssi >= -78 -> 2
    rssi >= -90 -> 1
    else -> 0
}

/** Human-readable quality word for a dBm reading; kept language-neutral here. */
fun signalQuality(grade: Int): String = when {
    grade >= 4 -> "Excellent"
    grade >= 3 -> "Good"
    grade >= 2 -> "Fair"
    grade >= 1 -> "Weak"
    else -> "None"
}

/** IPv4 written by DhcpInfo is a little-endian int. */
fun intToIpv4(value: Int): String {
    if (value == 0) return "-"
    return listOf(
        value and 0xff,
        (value shr 8) and 0xff,
        (value shr 16) and 0xff,
        (value shr 24) and 0xff,
    ).joinToString(".")
}

/** Extracts the first IP-looking token from a public-IP echo response. */
fun firstIpAddress(text: String): String? {
    val ipv4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b").find(text)?.value
    if (ipv4 != null) return ipv4
    val ipv6 = Regex("\\b(?:[0-9a-fA-F]{0,4}:){2,}[0-9a-fA-F]{0,4}\\b").find(text)?.value
    return ipv6
}

/** Two samples give a live rate; a clock jump backwards must not produce a spike. */
fun bytesPerSecond(now: Long, bytes: Long, beforeAt: Long, beforeBytes: Long): Long {
    val elapsed = now - beforeAt
    if (elapsed <= 0 || bytes < beforeBytes) return 0
    val delta = bytes - beforeBytes
    // Counters reset on some ROMs at 4 GB; ignore implausible jumps.
    if (delta > 1L shl 31) return 0
    return delta * 1000L / elapsed
}
